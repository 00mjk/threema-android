/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema for Android
 * Copyright (c) 2017-2020 Threema GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package ch.threema.app.voip.services;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;

import com.google.android.gms.common.data.FreezableUtils;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

import java.io.ByteArrayOutputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import androidx.annotation.AnyThread;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import ch.threema.app.R;
import ch.threema.app.messagereceiver.ContactMessageReceiver;
import ch.threema.app.messagereceiver.MessageReceiver;
import ch.threema.app.notifications.NotificationBuilderWrapper;
import ch.threema.app.services.ContactService;
import ch.threema.app.services.LifetimeService;
import ch.threema.app.services.MessageService;
import ch.threema.app.services.PreferenceService;
import ch.threema.app.services.RingtoneService;
import ch.threema.app.utils.ConfigUtils;
import ch.threema.app.utils.DNDUtil;
import ch.threema.app.utils.IdUtil;
import ch.threema.app.utils.MediaPlayerStateWrapper;
import ch.threema.app.utils.NameUtil;
import ch.threema.app.utils.RuntimeUtil;
import ch.threema.app.voip.CallState;
import ch.threema.app.voip.CallStateSnapshot;
import ch.threema.app.voip.Config;
import ch.threema.app.voip.activities.CallActivity;
import ch.threema.app.voip.managers.VoipListenerManager;
import ch.threema.app.voip.receivers.CallRejectReceiver;
import ch.threema.app.voip.receivers.VoipMediaButtonReceiver;
import ch.threema.app.voip.util.VoipUtil;
import ch.threema.base.ThreemaException;
import ch.threema.client.MessageQueue;
import ch.threema.client.voip.VoipCallAnswerData;
import ch.threema.client.voip.VoipCallAnswerMessage;
import ch.threema.client.voip.VoipCallHangupData;
import ch.threema.client.voip.VoipCallHangupMessage;
import ch.threema.client.voip.VoipCallOfferData;
import ch.threema.client.voip.VoipCallOfferMessage;
import ch.threema.client.voip.VoipCallRingingData;
import ch.threema.client.voip.VoipCallRingingMessage;
import ch.threema.client.voip.VoipICECandidatesData;
import ch.threema.client.voip.VoipICECandidatesMessage;
import ch.threema.client.voip.features.VideoFeature;
import ch.threema.storage.models.ContactModel;
import java8.util.concurrent.CompletableFuture;

import static ch.threema.app.ThreemaApplication.INCOMING_CALL_NOTIFICATION_ID;
import static ch.threema.app.notifications.NotificationBuilderWrapper.VIBRATE_PATTERN_INCOMING_CALL;
import static ch.threema.app.notifications.NotificationBuilderWrapper.VIBRATE_PATTERN_SILENT;
import static ch.threema.app.services.NotificationService.NOTIFICATION_CHANNEL_CALL;
import static ch.threema.app.voip.services.VoipCallService.ACTION_ICE_CANDIDATES;
import static ch.threema.app.voip.services.VoipCallService.EXTRA_ACTIVITY_MODE;
import static ch.threema.app.voip.services.VoipCallService.EXTRA_CALL_ID;
import static ch.threema.app.voip.services.VoipCallService.EXTRA_CANCEL_WEAR;
import static ch.threema.app.voip.services.VoipCallService.EXTRA_CANDIDATES;
import static ch.threema.app.voip.services.VoipCallService.EXTRA_CONTACT_IDENTITY;
import static ch.threema.app.voip.services.VoipCallService.EXTRA_IS_INITIATOR;

/**
 * The service keeping track of VoIP call state.
 *
 * This class is (intended to be) thread safe.
 */
@AnyThread
public class VoipStateService implements AudioManager.OnAudioFocusChangeListener {
	private static final Logger logger = LoggerFactory.getLogger(VoipStateService.class);
	private final static String TAG = "VoipStateService";

	public static final int VIDEO_RENDER_FLAG_NONE = 0x00;
	public static final int VIDEO_RENDER_FLAG_INCOMING = 0x01;
	public static final int VIDEO_RENDER_FLAG_OUTGOING = 0x02;

	// component type for wearable
	@Retention(RetentionPolicy.SOURCE)
	@IntDef({TYPE_NOTIFICATION, TYPE_ACTIVITY})
	public @interface Component {}
	public static final int TYPE_NOTIFICATION = 0;
	public static final int TYPE_ACTIVITY = 1;

	// system managers
	private final AudioManager audioManager;
	private final NotificationManagerCompat notificationManagerCompat;
	private final NotificationManager notificationManager;

	// Threema services
	private ContactService contactService;
	private RingtoneService ringtoneService;
	private PreferenceService preferenceService;
	private MessageService messageService;
	private LifetimeService lifetimeService;

	// Message sending
	private MessageQueue messageQueue;

	// App context
	private Context appContext;

	// State
	private volatile Boolean initiator = null;
	private CallState callState = new CallState();
	private Long callStartTimestamp = null;

	// Map that stores incoming offers
	private final HashMap<Long, VoipCallOfferData> offerMap = new HashMap<>();

	// Flag for designating current user configuration
	private int videoRenderMode = VIDEO_RENDER_FLAG_NONE;

	// Candidate cache
	private final Map<String, List<VoipICECandidatesData>> candidatesCache;

	// Notifications
	private final List<String> callNotificationTags = new ArrayList<>();
	private MediaPlayerStateWrapper ringtonePlayer;
	private @NonNull CompletableFuture<Void> ringtoneAudioFocusAbandoned = CompletableFuture.completedFuture(null);

	// Video
	private @Nullable VideoContext videoContext;
	private @NonNull CompletableFuture<VideoContext> videoContextFuture = new CompletableFuture<>();

	// Pending intents
	private @Nullable PendingIntent acceptIntent;

	// Connection status
	private boolean connectionAcquired = false;

	// Timeouts
	private static final int RINGING_TIMEOUT_SECONDS = 60;
	private static final int VOIP_CONNECTION_LINGER = 1000 * 5;

	private DataClient.OnDataChangedListener wearableListener = new DataClient.OnDataChangedListener() {
		@Override
		public void onDataChanged(@NonNull DataEventBuffer eventsBuffer) {
			logger.debug("onDataChanged Listener VoipState: " + eventsBuffer);
			final List<DataEvent> events = FreezableUtils.freezeIterable(eventsBuffer);
			for (DataEvent event : events) {
				if (event.getType() == DataEvent.TYPE_CHANGED) {
					String path = event.getDataItem().getUri().getPath();
					logger.debug("received datachange path " + path);
					if ("/accept-call".equals(path)) {
						logger.debug("accept call block entered");
						DataMapItem dataMapItem = DataMapItem.fromDataItem(event.getDataItem());
						long callId = dataMapItem.getDataMap().getLong(EXTRA_CALL_ID, 0L);
						String identity = dataMapItem.getDataMap().getString(EXTRA_CONTACT_IDENTITY);
						final Intent intent = createAcceptIntent(callId, identity);
						appContext.startService(intent);
						//Listen again for hang up
						Wearable.getDataClient(appContext).addListener(wearableListener);

					} if("/reject-call".equals(path)) {
						logger.debug("reject call block entered");
						DataMapItem dataMapItem = DataMapItem.fromDataItem(event.getDataItem());
						long callId = dataMapItem.getDataMap().getLong(EXTRA_CALL_ID, 0L);
						String identity = dataMapItem.getDataMap().getString(EXTRA_CONTACT_IDENTITY);
						final Intent rejectIntent = createRejectIntent(
							callId,
							identity,
							VoipCallAnswerData.RejectReason.REJECTED
						);
						CallRejectService.enqueueWork(appContext, rejectIntent);
					} if ("/disconnect-call".equals(path)){
						logger.debug("disconnect call block entered");
						VoipUtil.sendVoipCommand(appContext, VoipCallService.class, VoipCallService.ACTION_HANGUP);
					}
				}
			}
		}
	};

	public VoipStateService(ContactService contactService,
	                        RingtoneService ringtoneService,
	                        PreferenceService preferenceService,
	                        MessageService messageService,
	                        MessageQueue messageQueue,
	                        LifetimeService lifetimeService,
	                        final Context appContext) {
		this.contactService = contactService;
		this.ringtoneService = ringtoneService;
		this.preferenceService = preferenceService;
		this.messageService = messageService;
		this.lifetimeService = lifetimeService;
		this.messageQueue = messageQueue;
		this.appContext = appContext;
		this.candidatesCache = new HashMap<>();
		this.notificationManagerCompat = NotificationManagerCompat.from(appContext);
		this.notificationManager = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
		this.audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
	}

	//region State transitions

	/**
	 * Get the current call state as an immutable snapshot.
	 *
	 * Note: Does not require locking, since the {@link CallState}
	 * class is thread safe.
	 */
	public CallStateSnapshot getCallState() {
		return this.callState.getStateSnapshot();
	}

	/**
	 * Called for every state transition.
	 *
	 * Note: Most reactions to state changes should be done in the `setStateXXX` methods.
	 *       This method should only be used for actions that apply to multiple state transitions.
	 *
	 * @param oldState The previous call state.
	 * @param newState The new call state.
	 */
	private void onStateChange(
		@NonNull CallStateSnapshot oldState,
		@NonNull CallStateSnapshot newState
	) {
		logger.info(
			"Call state change from {}({}/{}) to {}({}/{})",
			oldState.getName(), oldState.getCallId(), oldState.getIncomingCallCounter(),
			newState.getName(), newState.getCallId(), newState.getIncomingCallCounter()
		);

		// Clear pending accept intent
		if (!newState.isRinging()) {
			this.acceptIntent = null;
			this.stopRingtone();
		}

		// Ensure bluetooth media button receiver is registered when a call starts
		if (newState.isRinging() || newState.isInitializing()) {
			audioManager.registerMediaButtonEventReceiver(new ComponentName(appContext, VoipMediaButtonReceiver.class));
		}

		// Ensure bluetooth media button receiver is deregistered when a call ends
		if (newState.isDisconnecting() || newState.isIdle()) {
			audioManager.unregisterMediaButtonEventReceiver(new ComponentName(appContext, VoipMediaButtonReceiver.class));
		}
	}

	/**
	 * Set the current call state to RINGING.
	 */
	public synchronized void setStateRinging(long callId) {
		if (this.callState.isRinging()) {
			return;
		}

		this.ringtoneAudioFocusAbandoned = new CompletableFuture<>();

		// Transition call state
		final CallStateSnapshot prevState = this.callState.getStateSnapshot();
		this.callState.setRinging(callId);
		this.onStateChange(prevState, this.callState.getStateSnapshot());
	}

	/**
	 * Set the current call state to INITIALIZING.
	 */
	public synchronized void setStateInitializing(long callId) {
		if (this.callState.isInitializing()) {
			return;
		}

		// Transition call state
		final CallStateSnapshot prevState = this.callState.getStateSnapshot();
		this.callState.setInitializing(callId);
		this.onStateChange(prevState, this.callState.getStateSnapshot());

		// Make sure connection is open
		if (!this.connectionAcquired) {
			this.lifetimeService.acquireConnection(TAG);
			this.connectionAcquired = true;
		}

		// Send cached candidates and clear cache
		synchronized (this.candidatesCache) {
			logger.info("{}: Processing cached candidates for {} ID(s)", callId, this.candidatesCache.size());

			// Note: We're sending all cached candidates. The broadcast receiver
			// is responsible for dropping the ones that aren't of interest.
			for (Map.Entry<String, List<VoipICECandidatesData>> entry : this.candidatesCache.entrySet()) {
				logger.info("{}: Broadcasting {} candidates data messages from {}",
					callId, entry.getValue().size(), entry.getKey());
				for (VoipICECandidatesData data : entry.getValue()) {
					// Broadcast candidates
					Intent intent = new Intent();
					intent.setAction(ACTION_ICE_CANDIDATES);
					intent.putExtra(EXTRA_CALL_ID, data.getCallIdOrDefault(0L));
					intent.putExtra(EXTRA_CONTACT_IDENTITY, entry.getKey());
					intent.putExtra(EXTRA_CANDIDATES, data);
					LocalBroadcastManager.getInstance(appContext).sendBroadcast(intent);
				}
			}
			this.clearCandidatesCache();
		}
	}

	/**
	 * Set the current call state to CALLING.
	 */
	public synchronized void setStateCalling(long callId) {
		if (this.callState.isCalling()) {
			return;
		}

		// Transition call state
		final CallStateSnapshot prevState = this.callState.getStateSnapshot();
		this.callState.setCalling(callId);
		this.onStateChange(prevState, this.callState.getStateSnapshot());

		// Record the start timestamp of the call.
		// The SystemClock.elapsedRealtime function (returning milliseconds)
		// is guaranteed to be monotonic.
		this.callStartTimestamp = SystemClock.elapsedRealtime();
	}

	/**
	 * Set the current call state to DISCONNECTING.
	 */
	public synchronized void setStateDisconnecting(long callId) {
		if (this.callState.isDisconnecting()) {
			return;
		}

		// Transition call state
		final CallStateSnapshot prevState = this.callState.getStateSnapshot();
		this.callState.setDisconnecting(callId);
		this.onStateChange(prevState, this.callState.getStateSnapshot());

		// Reset start timestamp
		this.callStartTimestamp = null;

		// Clear the candidates cache
		this.clearCandidatesCache();
	}

	/**
	 * Set the current call state to IDLE.
	 */
	public synchronized void setStateIdle() {
		if (this.callState.isIdle()) {
			return;
		}

		// Transition call state
		final CallStateSnapshot prevState = this.callState.getStateSnapshot();
		this.callState.setIdle();
		this.onStateChange(prevState, this.callState.getStateSnapshot());

		// Reset start timestamp
		this.callStartTimestamp = null;

		// Reset initiator flag
		this.initiator = null;

		// Remove offer data
		long callId = prevState.getCallId();
		logger.debug("Removing information for call {} from offerMap", callId);
		this.offerMap.remove(callId);

		// Release Threema connection
		if (this.connectionAcquired) {
			this.lifetimeService.releaseConnectionLinger(TAG, VOIP_CONNECTION_LINGER);
			this.connectionAcquired = false;
		}
	}

	//endregion

	/**
	 * Return whether the VoIP service is currently initialized as initiator or responder.
	 *
	 * Note: This is only initialized once a call is being set up. That means that the flag
	 * will be `null` when a call is ringing, but hasn't been accepted yet.
	 */
	@Nullable
	public Boolean isInitiator() {
		return this.initiator;
	}

	/**
	 * Return whether the VoIP service is currently initialized as initiator or responder.
	 */
	public void setInitiator(boolean isInitiator) {
		this.initiator = isInitiator;
	}

	/**
	 * Create a new accept intent for the specified call ID / identity.
	 */
	private Intent createAcceptIntent(long callId, @NonNull String identity) {
		final Intent intent = new Intent(appContext, VoipCallService.class);
		intent.putExtra(EXTRA_CALL_ID, callId);
		intent.putExtra(EXTRA_CONTACT_IDENTITY, identity);
		intent.putExtra(EXTRA_IS_INITIATOR, false);
		return intent;
	}

	/**
	 * Create a new reject intent for the specified call ID / identity.
	 */
	private Intent createRejectIntent(long callId, @NonNull String identity, byte rejectReason) {
		final Intent intent = new Intent(this.appContext, CallRejectReceiver.class);
		intent.putExtra(EXTRA_CALL_ID, callId);
		intent.putExtra(EXTRA_CONTACT_IDENTITY, identity);
		intent.putExtra(EXTRA_IS_INITIATOR, false);
		intent.putExtra(CallRejectService.EXTRA_REJECT_REASON, rejectReason);
		return intent;
	}

	/**
	 * Validate offer data, return true if it's valid.
	 */
	private boolean validateOfferData(@Nullable VoipCallOfferData.OfferData offer) {
		if (offer == null) {
			logger.error("Offer data is null");
			return false;
		}
		final String sdpType = offer.getSdpType();
		if (!sdpType.equals("offer")) {
			logger.error("Offer data is invalid: Sdp type is {}, not offer", sdpType);
			return false;
		}
		final String sdp = offer.getSdp();
		if (sdp == null) {
			logger.error("Offer data is invalid: Sdp is null");
			return false;
		}
		return true;
	}

	/**
	 * Return the {@link VoipCallOfferData} associated with this Call ID (if any).
	 */
	public @Nullable VoipCallOfferData getCallOffer(long callId) {
		return this.offerMap.get(callId);
	}

	//region Handle call messages

	/**
	 * Handle an incoming VoipCallOfferMessage.
	 * @return true if messages was successfully processed
	 */
	@WorkerThread
	public synchronized boolean handleCallOffer(final VoipCallOfferMessage msg) {
		// Unwrap data
		final VoipCallOfferData callOfferData = msg.getData();
		if (callOfferData == null) {
			logger.warn("VoipCallOfferMessage received. Data is null, ignoring.");
			return true;
		}
		final long callId = callOfferData.getCallIdOrDefault(0L);
		logger.info(
			"{}: VoipCallOfferMessage received from {} (Features: {})",
			callId, msg.getFromIdentity(), callOfferData.getFeatures()
		);
		logger.info("{}: {}", callId, callOfferData.getOfferData());

		// Get contact and receiver
		final ContactModel contact = this.contactService.getByIdentity(msg.getFromIdentity());
		if (contact == null) {
			logger.error("{}: Could not fetch contact for identity {}", callId, msg.getFromIdentity());
			return true;
		}

		// Handle some reasons for rejecting calls...
		Byte rejectReason = null; // Set to non-null in order to reject the call
		boolean silentReject = false; // Set to true if you don't want a "missed call" chat message
		if (!this.preferenceService.isVoipEnabled()) {
			// Calls disabled
			logger.info("{}: Rejecting call from {} (disabled)", callId, contact.getIdentity());
			rejectReason = VoipCallAnswerData.RejectReason.DISABLED;
			silentReject = true;
		} else if (!this.validateOfferData(callOfferData.getOfferData())) {
			// Offer invalid
			logger.warn("{}: Rejecting call from {} (invalid offer)", callId, contact.getIdentity());
			rejectReason = VoipCallAnswerData.RejectReason.UNKNOWN;
			silentReject = true;
		} else if (!this.callState.isIdle()) {
			// Another call is already active
			logger.info("{}: Rejecting call from {} (busy)", callId, contact.getIdentity());
			rejectReason = VoipCallAnswerData.RejectReason.BUSY;
		} else if (VoipUtil.isPSTNCallOngoing(this.appContext)) {
			// A PSTN call is ongoing
			logger.info("{}: Rejecting call from {} (PSTN call ongoing)", callId, contact.getIdentity());
			rejectReason = VoipCallAnswerData.RejectReason.BUSY;
		} else if (DNDUtil.getInstance().isMutedWork()) {
			// Called outside working hours
			logger.info("{}: Rejecting call from {} (called outside of working hours)", callId, contact.getIdentity());
			rejectReason = VoipCallAnswerData.RejectReason.OFF_HOURS;
		}
		if (rejectReason != null) {
			try {
				this.sendRejectCallAnswerMessage(contact, callId, rejectReason, !silentReject);
			} catch (ThreemaException e) {
				logger.error(callId + ": Could not send reject call message", e);
			}
			return true;
		}

		// Prefetch TURN servers
		Config.getTurnServerCache().prefetchTurnServers();

		// Reset fetch cache
		ch.threema.app.routines.UpdateFeatureLevelRoutine.removeTimeCache(contact);

		// Store offer in offer map
		logger.debug("Adding information for call {} to offerMap", callId);
		this.offerMap.put(callId, callOfferData);

		// If the call is accepted, let VoipCallService know
		// and set flag to cancel on watch to true as this call flow is initiated and handled from the Phone
		final Intent answerIntent = createAcceptIntent(callId, msg.getFromIdentity());
		Bundle bundle = new Bundle();
		bundle.putBoolean(EXTRA_CANCEL_WEAR, true);
		answerIntent.putExtras(bundle);
		final PendingIntent accept;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			accept = PendingIntent.getForegroundService(
					this.appContext,
					IdUtil.getTempId(contact),
					answerIntent,
					PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT
			);
		} else {
			accept = PendingIntent.getService(
					this.appContext,
					IdUtil.getTempId(contact),
					answerIntent,
					PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT
			);
		}
		this.acceptIntent = accept;

		// If the call is rejected, start the CallRejectService
		final Intent rejectIntent = this.createRejectIntent(
			callId,
			msg.getFromIdentity(),
			VoipCallAnswerData.RejectReason.REJECTED
		);
		final PendingIntent reject = PendingIntent.getBroadcast(
				this.appContext,
				-IdUtil.getTempId(contact),
				rejectIntent,
				PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT
		);

		final ContactMessageReceiver messageReceiver = this.contactService.createReceiver(contact);

		boolean isMuted = DNDUtil.getInstance().isMutedPrivate(messageReceiver, null);

		// Set state to RINGING
		this.setStateRinging(callId);

		// play ringtone
		this.playRingtone(messageReceiver, isMuted);

		// Show call notification
		this.showNotification(contact, callId, accept, reject, msg, callOfferData, isMuted);

		// Send "ringing" message to caller
		try {
			this.sendCallRingingMessage(contact, callId);
		} catch (ThreemaException e) {
			logger.error(callId + ": Could not send ringing message", e);
		}

		// Start timer to reject the call after a while
		final long originalCallCounter = this.callState.getIncomingCallCounter();
		final Runnable ringerTimeoutRunnable = () -> {
			final CallStateSnapshot currentCallState = this.getCallState();

			// Note: Because different incoming calls might have the same call ID (0),
			// as a transitional solution we're still using the call counter.

			// Only reject the call if the state is still initializing with the same call id.
			if (!currentCallState.isRinging()) {
				logger.info(
					"Ignoring ringer timeout for call #{} (state is {}, not RINGING)",
					originalCallCounter,
					currentCallState.getName()
				);
			} else if (currentCallState.getIncomingCallCounter() != originalCallCounter) {
				logger.info(
					"Ignoring ringer timeout for call #{} (current: #{})",
					originalCallCounter,
					currentCallState.getIncomingCallCounter()
				);
			} else {
				logger.info(
					"Ringer timeout for call #{} reached after {}s",
					originalCallCounter,
					RINGING_TIMEOUT_SECONDS
				);

				// Reject call
				final Intent rejectIntent1 = createRejectIntent(
					currentCallState.getCallId(),
					msg.getFromIdentity(),
					VoipCallAnswerData.RejectReason.TIMEOUT
				);
				CallRejectService.enqueueWork(appContext, rejectIntent1);
			}
		};
		(new Handler(Looper.getMainLooper()))
			.postDelayed(ringerTimeoutRunnable, RINGING_TIMEOUT_SECONDS * 1000);

		// Notify listeners
		VoipListenerManager.messageListener.handle(listener -> {
			final String identity = msg.getFromIdentity();
			if (listener.handle(identity)) {
				listener.onOffer(identity, msg.getData());
			}
		});
		VoipListenerManager.callEventListener.handle(listener -> listener.onRinging(msg.getFromIdentity()));

		return true;
	}

	/**
	 * Handle an incoming VoipCallAnswerMessage.
	 * @return true if messages was successfully processed
	 */
	@WorkerThread
	public synchronized boolean handleCallAnswer(final VoipCallAnswerMessage msg) {
		final VoipCallAnswerData callAnswerData = msg.getData();
		if (callAnswerData != null) {
			// Validate Call ID
			final long callId = callAnswerData.getCallIdOrDefault(0L);
			if (!this.isCallIdValid(callId)) {
				logger.info(
					"Received answer for an invalid call ID ({}, local={}), ignoring",
					callId, this.callState.getCallId()
				);
				return true;
			}

			// Ensure that action was set
			if (callAnswerData.getAction() == null) {
			    logger.error("Received answer without action, ignoring");
			    return true;
			}

			switch (callAnswerData.getAction()) {
				// Call was accepted
				case VoipCallAnswerData.Action.ACCEPT:
					logger.info(
						"{}: VoipCallAnswerMessage received: Accept => (Features: {})",
						callId, callAnswerData.getFeatures()
					);
					logger.info("{}: {}", callId, callAnswerData.getAnswerData());
					VoipUtil.sendVoipBroadcast(this.appContext, CallActivity.ACTION_CALL_ACCEPTED);
					break;

				// Call was rejected
				case VoipCallAnswerData.Action.REJECT:
					// TODO: only for tests!
					VoipListenerManager.callEventListener.handle(listener -> {
						listener.onRejected(msg.getFromIdentity(), false, callAnswerData.getRejectReason());
					});
					logger.info("{}: VoipCallAnswerMessage received: Reject (reason code: {})", callId, callAnswerData.getRejectReason());
					break;

				default:
					logger.info("{}: VoipCallAnswer message received: Unknown action: {}", callId, callAnswerData.getAction());
					break;
			}
		}

		// Notify listeners
		VoipListenerManager.messageListener.handle(listener -> {
			final String identity = msg.getFromIdentity();
			if (listener.handle(identity)) {
				listener.onAnswer(identity, callAnswerData);
			}
		});

		return true;
	}

	/**
	 * Handle an incoming VoipICECandidatesMessage.
	 * @return true if messages was successfully processed
	 */
	@WorkerThread
	public synchronized boolean handleICECandidates(final VoipICECandidatesMessage msg) {
		// Unwrap data
		final VoipICECandidatesData candidatesData = msg.getData();
		if (candidatesData == null) {
			logger.warn("VoipICECandidatesMessage received. Data is null, ignoring.");
			return true;
		}
		if (candidatesData.getCandidates() == null) {
			logger.warn("VoipICECandidatesMessage received. Candidates are null, ignoring.");
			return true;
		}

		// Validate Call ID
		final long callId = candidatesData.getCallIdOrDefault(0L);
		if (!this.isCallIdValid(callId)) {
			logger.info(
				"Received candidates for an invalid Call ID ({}, local={}), ignoring",
				callId, this.callState.getCallId()
			);
			return true;
		}

		// The "removed" flag is deprecated, see ANDR-1145 / SE-66
		if (candidatesData.isRemoved()) {
			logger.info("{}: Ignoring VoipICECandidatesMessage with removed=true", callId);
			return true;
		}

		logger.info(
			"{}: VoipICECandidatesMessage with {} candidates received",
			callId, candidatesData.getCandidates().length
		);

		// Handle candidates depending on state
		if (this.callState.isIdle() || this.callState.isRinging()) {
			// If the call hasn't been started yet, cache the candidate(s)
			this.cacheCandidate(msg.getFromIdentity(), candidatesData);
		} else if (this.callState.isInitializing() || this.callState.isCalling()) {
			// Otherwise, send candidate(s) directly to call service via broadcast
			Intent intent = new Intent();
			intent.setAction(ACTION_ICE_CANDIDATES);
			intent.putExtra(EXTRA_CALL_ID, msg.getData().getCallIdOrDefault(0L));
			intent.putExtra(EXTRA_CONTACT_IDENTITY, msg.getFromIdentity());
			intent.putExtra(EXTRA_CANDIDATES, candidatesData);
			LocalBroadcastManager.getInstance(appContext).sendBroadcast(intent);
		} else {
			logger.warn("Received ICE candidates in invalid call state ({})", this.callState);
		}

		// Otherwise, ignore message.

		return true;
	}

	/**
	 * Handle incoming Call Ringing message
	 * @return true if message was successfully processed
	 */
	@WorkerThread
	public synchronized boolean handleCallRinging(final VoipCallRingingMessage msg) {
		final CallStateSnapshot state = this.callState.getStateSnapshot();

		// Validate Call ID
		//
		// NOTE: Ringing messages from older Threema versions may not have any associated data!
		final long callId = msg.getData() == null
			? 0L
			: msg.getData().getCallIdOrDefault(0L);
		if (!this.isCallIdValid(callId)) {
			logger.info(
				"Received ringing for an invalid Call ID ({}, local={}), ignoring",
				callId, state.getCallId()
			);
			return true;
		}

		logger.info("VoipCallRingingMessage received.");

		// Check whether we're in the correct state for a ringing message
		if (!state.isInitializing()) {
			logger.warn("Ignoring VoipCallRingingMessage, call state is {}", state.getName());
			return true;
		}

		// Notify listeners
		VoipListenerManager.messageListener.handle(listener -> {
			final String identity = msg.getFromIdentity();
			if (listener.handle(identity)) {
				listener.onRinging(identity, msg.getData());
			}
		});

		return true;
	}

	/**
	 * Handle remote call hangup messages.
	 * A hangup can happen either before or during a call.
	 * @return true if message was successfully processed
	 */
	@WorkerThread
	public synchronized boolean handleRemoteCallHangup(final VoipCallHangupMessage msg) {
		// Validate Call ID
		//
		// NOTE: Hangup messages from older Threema versions may not have any associated data!
		final long callId = msg.getData() == null
			? 0L
			: msg.getData().getCallIdOrDefault(0L);
		if (!this.isCallIdValid(callId)) {
			logger.info(
				"Received hangup for an invalid Call ID ({}, local={}), ignoring",
				callId, this.callState.getCallId()
			);
			return true;
		}

		logger.info("VoipCallHangupMessage received.");

		final String identity = msg.getFromIdentity();

		final CallStateSnapshot prevState = this.callState.getStateSnapshot();
		final Integer duration = getCallDuration();

		// Detect whether this is an incoming or outgoing call.
		//
		// NOTE: When a call hasn't been accepted yet, the `isInitiator` flag is not yet set.
		//       however, in that case we can be sure that it's an incoming call.
		final boolean incoming = this.isInitiator() != Boolean.TRUE;

		// Reset state
		this.setStateIdle();

		// Cancel call notification for that person
		this.cancelCallNotification(msg.getFromIdentity());

		// Notify listeners
		VoipListenerManager.messageListener.handle(listener -> {
			if (listener.handle(identity)) {
				listener.onHangup(identity, msg.getData());
			}
		});
		if (incoming && (prevState.isIdle() || prevState.isRinging() || prevState.isInitializing())) {
			VoipListenerManager.callEventListener.handle(
				listener -> {
					final boolean accepted = prevState.isInitializing();
					listener.onMissed(identity, accepted);
				}
			);
		} else if (prevState.isCalling() && duration != null) {
			VoipListenerManager.callEventListener.handle(listener -> {
				listener.onFinished(msg.getFromIdentity(), !incoming, duration);
			});
		}

		return true;
	}

	//endregion

	/**
	 * Return whether the specified call ID belongs to the current call.
	 *
	 * NOTE: Do not use this method to validate the call ID in an offer,
	 *       that doesn't make sense :)
	 */
	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	private synchronized boolean isCallIdValid(long callId) {
		// If the passed in Call ID matches the current Call ID, everything is fine
		final long currentCallId = this.callState.getCallId();
		if (callId == currentCallId) {
			return true;
		}

		// ANDR-1140: If we are the initiator, then we will have initialized the call ID to a
		// random value. If however the remote device does not yet support call IDs, then returned
		// messages will not contain a Call ID. Accept the messages anyways.
		final boolean isInitiatior = this.isInitiator() == Boolean.TRUE;
		if (isInitiatior && callId == 0L) {
			return true;
		}

		// Otherwise, there's a call ID mismatch.
		return false;
	}

	/**
	 * Send a call offer to the specified contact.
	 *
	 * @param videoCall Whether to enable video calls in this offer.
	 * @throws ThreemaException if enqueuing the message fails.
	 * @throws IllegalArgumentException if the session description is not valid for an offer message.
	 * @throws IllegalStateException if the call state is not INITIALIZING
	 */
	public synchronized void sendCallOfferMessage(
		@NonNull ContactModel receiver,
		final long callId,
		@NonNull SessionDescription sessionDescription,
		boolean videoCall
	) throws ThreemaException, IllegalArgumentException, IllegalStateException {
		switch (sessionDescription.type) {
			case OFFER:
				// OK
				break;
			case ANSWER:
			case PRANSWER:
				throw new IllegalArgumentException("A " + sessionDescription.type +
						" session description is not valid for an offer message");
		}

		final CallStateSnapshot state = this.callState.getStateSnapshot();
		if (!state.isInitializing()) {
			throw new IllegalStateException("Called sendCallOfferMessage in state " + state.getName());
		}

		final VoipCallOfferData callOfferData = new VoipCallOfferData()
			.setCallId(callId)
			.setOfferData(
				new VoipCallOfferData.OfferData()
					.setSdpType(sessionDescription.type.canonicalForm())
					.setSdp(sessionDescription.description)
			);
		if (videoCall) {
			callOfferData.addFeature(new VideoFeature());
		}

		final VoipCallOfferMessage voipCallOfferMessage = new VoipCallOfferMessage();
		voipCallOfferMessage.setData(callOfferData);
		voipCallOfferMessage.setToIdentity(receiver.getIdentity());

		logger.info("{}: Enqueue VoipCallOfferMessage ID {} to {}: {} (features: {})",
			callId,
			voipCallOfferMessage.getMessageId(),
			voipCallOfferMessage.getToIdentity(),
			callOfferData.getOfferData(),
			callOfferData.getFeatures()
		);
		this.messageQueue.enqueue(voipCallOfferMessage);
		this.messageService.sendProfilePicture(new MessageReceiver[] {contactService.createReceiver(receiver)});
	}

	//region Send call messages

	/**
	 * Accept a call from the specified contact.
	 * @throws ThreemaException if enqueuing the message fails.
	 * @throws IllegalArgumentException if the session description is not valid for an offer message.
	 */
	public void sendAcceptCallAnswerMessage(
		@NonNull ContactModel receiver,
		final long callId,
		@NonNull SessionDescription sessionDescription,
		boolean videoCall
	) throws ThreemaException, IllegalArgumentException {
		this.sendCallAnswerMessage(
			receiver,
			callId,
			sessionDescription,
			VoipCallAnswerData.Action.ACCEPT,
			null,
			videoCall
		);
	}

	/**
	 * Reject a call from the specified contact.
	 * @throws ThreemaException if enqueuing the message fails.
	 */
	public void sendRejectCallAnswerMessage(
		final @NonNull ContactModel receiver,
		final long callId,
		byte reason
	) throws ThreemaException, IllegalArgumentException {
		logger.info("VoipStateService sendRejectCallAnswerMessage");
		this.sendRejectCallAnswerMessage(receiver, callId, reason, true);
	}

	/**
	 * Reject a call from the specified contact.
	 * @throws ThreemaException if enqueuing the message fails.
	 */
	public void sendRejectCallAnswerMessage(
		final @NonNull ContactModel receiver,
		final long callId,
		byte reason,
		boolean notifyListeners
	) throws ThreemaException, IllegalArgumentException {
		logger.info("VoipStateService sendRejectCallAnswerMessage listener true");
		this.sendCallAnswerMessage(receiver, callId, null, VoipCallAnswerData.Action.REJECT, reason, null);

		// Notify listeners
		if (notifyListeners) {
			VoipListenerManager.callEventListener.handle(listener -> {
				switch (reason) {
					case VoipCallAnswerData.RejectReason.BUSY:
					case VoipCallAnswerData.RejectReason.TIMEOUT:
					case VoipCallAnswerData.RejectReason.OFF_HOURS:
						listener.onMissed(receiver.getIdentity(), false);
						break;
					default:
						listener.onRejected(receiver.getIdentity(), true, reason);
						break;
				}
			});
		}
	}

	/**
	 * Send a call answer method.
	 *
	 * @param videoCall If set to TRUE, then the `video` call feature
	 *     will be sent along in the answer.
	 * @throws ThreemaException
	 * @throws IllegalArgumentException
	 * @throws IllegalStateException
	 */
	private void sendCallAnswerMessage(
		@NonNull ContactModel receiver,
		final long callId,
		@Nullable SessionDescription sessionDescription,
	    byte action,
	    @Nullable Byte rejectReason,
		@Nullable Boolean videoCall
	) throws ThreemaException, IllegalArgumentException, IllegalStateException {
		logger.info("VoipStateService sendCallAnswerMessage");
		final VoipCallAnswerData callAnswerData = new VoipCallAnswerData()
			.setCallId(callId)
			.setAction(action);

		if (action == VoipCallAnswerData.Action.ACCEPT && sessionDescription != null) {
			switch (sessionDescription.type) {
				case ANSWER:
				case PRANSWER:
					// OK
					break;
				case OFFER:
					throw new IllegalArgumentException("A " + sessionDescription.type +
							" session description is not valid for an answer message");
			}

			callAnswerData.setAnswerData(
				new VoipCallAnswerData.AnswerData()
					.setSdpType(sessionDescription.type.canonicalForm())
					.setSdp(sessionDescription.description)
			);

			if (Boolean.TRUE.equals(videoCall)) {
				callAnswerData.addFeature(new VideoFeature());
			}
		} else if (action == VoipCallAnswerData.Action.REJECT && rejectReason != null) {
			callAnswerData.setRejectReason(rejectReason);
		} else {
			throw new IllegalArgumentException("Invalid action, missing session description or missing reject reason");
		}

		final VoipCallAnswerMessage voipCallAnswerMessage = new VoipCallAnswerMessage();
		voipCallAnswerMessage.setData(callAnswerData);
		voipCallAnswerMessage.setToIdentity(receiver.getIdentity());

		logger.info("{}: Enqueue VoipCallAnswerMessage ID {} to {}: action={} (features: {})",
			callId,
			voipCallAnswerMessage.getMessageId(),
			voipCallAnswerMessage.getToIdentity(),
			callAnswerData.getAction(),
			callAnswerData.getFeatures()
		);
		messageQueue.enqueue(voipCallAnswerMessage);
		this.messageService.sendProfilePicture(new MessageReceiver[] {contactService.createReceiver(receiver)});

	}

	/**
	 * Send ice candidates to the specified contact.
	 * @throws ThreemaException if enqueuing the message fails.
	 */
	synchronized void sendICECandidatesMessage(
		@NonNull ContactModel receiver,
		final long callId,
		@NonNull IceCandidate[] iceCandidates
	) throws ThreemaException {
		final CallStateSnapshot state = this.callState.getStateSnapshot();
		if (!(state.isRinging() || state.isInitializing() || state.isCalling())) {
			logger.warn("Called sendICECandidatesMessage in state {}, ignoring", state.getName());
			return;
		}

		final List<VoipICECandidatesData.Candidate> candidates = new LinkedList<>();
		for (IceCandidate c : iceCandidates) {
			if (c != null) {
				candidates.add(new VoipICECandidatesData.Candidate(c.sdp, c.sdpMid, c.sdpMLineIndex, null));
			}
		}

		final VoipICECandidatesData voipICECandidatesData = new VoipICECandidatesData()
			.setCallId(callId)
			.setCandidates(candidates.toArray(new VoipICECandidatesData.Candidate[candidates.size()]));

		final VoipICECandidatesMessage voipICECandidatesMessage = new VoipICECandidatesMessage();
		voipICECandidatesMessage.setData(voipICECandidatesData);
		voipICECandidatesMessage.setToIdentity(receiver.getIdentity());

		logger.info(
			"{}: Enqueue VoipICECandidatesMessage ID {} to {}",
			callId, voipICECandidatesMessage.getMessageId(), voipICECandidatesMessage.getToIdentity()
		);
		messageQueue.enqueue(voipICECandidatesMessage);
	}

	/**
	 * Send a ringing message to the specified contact.
	 */
	private synchronized void sendCallRingingMessage(
		@NonNull ContactModel contactModel,
		final long callId
	) throws ThreemaException, IllegalStateException {
		final CallStateSnapshot state = this.callState.getStateSnapshot();
		if (!state.isRinging()) {
			throw new IllegalStateException("Called sendCallRingingMessage in state " + state.getName());
		}

		final VoipCallRingingData callRingingData = new VoipCallRingingData()
			.setCallId(callId);

		final VoipCallRingingMessage msg = new VoipCallRingingMessage();
		msg.setToIdentity(contactModel.getIdentity());
		msg.setData(callRingingData);

		logger.info("{}: Enqueue VoipCallRinging message ID {} to {}", callId, msg.getMessageId(), msg.getToIdentity());
		messageQueue.enqueue(msg);
	}

	/**
	 * Send a hangup message to the specified contact.
	 */
	synchronized void sendCallHangupMessage(
		final @NonNull ContactModel contactModel,
		final long callId
	) throws ThreemaException {
		final CallStateSnapshot state = this.callState.getStateSnapshot();
		final String peerIdentity = contactModel.getIdentity();

		final VoipCallHangupData callHangupData = new VoipCallHangupData()
			.setCallId(callId);

		final VoipCallHangupMessage msg = new VoipCallHangupMessage();
		msg.setData(callHangupData);
		msg.setToIdentity(peerIdentity);

		final Integer duration = getCallDuration();
		final boolean outgoing = this.isInitiator() == Boolean.TRUE;

		logger.info("{}: Enqueue VoipCallHangup message ID {} to {} (prevState={}, duration={})",
			callId, msg.getMessageId(), msg.getToIdentity(), state, duration);
		messageQueue.enqueue(msg);

		// Notify the VoIP call event listener
		if (duration == null && (state.isInitializing() || state.isCalling() || state.isDisconnecting())) {
			// Connection was never established
			VoipListenerManager.callEventListener.handle(
				listener -> {
					if (outgoing) {
						listener.onAborted(peerIdentity);
					} else {
						listener.onMissed(peerIdentity, true);
					}
				}
			);
		}
		// Note: We don't call listener.onFinished here, that's already being done
		// in VoipCallService#disconnect.
	}

	//endregion

	/**
	 * Accept an incoming call.
	 * @return true if call was accepted, false otherwise (e.g. if no incoming call was active)
	 */
	public boolean acceptIncomingCall() {
		if (this.acceptIntent == null) {
			return false;
		}
		try {
			this.acceptIntent.send();
			this.acceptIntent = null;
			return true;
		} catch (PendingIntent.CanceledException e) {
			logger.error("Cannot send pending accept intent: It was cancelled");
			this.acceptIntent = null;
			return false;
		}
	}

	/**
	 * Clear the canddidates cache for the specified identity.
	 */
	void clearCandidatesCache(String identity) {
		logger.debug("Clearing candidates cache for {}", identity);
		synchronized (this.candidatesCache) {
			this.candidatesCache.remove(identity);
		}
	}

	/**
	 * Clear the candidates cache for all identities.
	 */
	private void clearCandidatesCache() {
		logger.debug("Clearing candidates cache for all identities");
		synchronized (this.candidatesCache) {
			this.candidatesCache.clear();
		}
	}

	/**
	 * Cancel a pending call notification for the specified identity.
	 */
	void cancelCallNotification(@NonNull String identity) {
		// Cancel fullscreen activity launched by notification first
		VoipUtil.sendVoipBroadcast(appContext, CallActivity.ACTION_CANCELLED);

		this.stopRingtone();

		synchronized (this.callNotificationTags) {
			if (this.callNotificationTags.contains(identity)) {
				logger.info("Cancelling call notification for {}", identity);
				this.notificationManagerCompat.cancel(identity, INCOMING_CALL_NOTIFICATION_ID);
				this.callNotificationTags.remove(identity);
			} else {
				logger.warn("No call notification found for {}", identity);
			}
		}
		if (ConfigUtils.isPlayServicesInstalled(appContext)){
			cancelOnWearable(TYPE_NOTIFICATION);
			cancelOnWearable(TYPE_ACTIVITY);
		}
	}

	/**
	 * Cancel all pending call notifications.
	 */
	void cancelCallNotificationsForNewCall() {
		synchronized (this.callNotificationTags) {
			logger.info("Cancelling all {} call notifications", this.callNotificationTags.size());
			for (String tag : this.callNotificationTags) {
				this.notificationManagerCompat.cancel(tag, INCOMING_CALL_NOTIFICATION_ID);
			}
			this.callNotificationTags.clear();
		}
		if (ConfigUtils.isPlayServicesInstalled(appContext)){
			cancelOnWearable(TYPE_NOTIFICATION);
		}
	}

	public void cancelOnWearable(@Component int component){
		RuntimeUtil.runInAsyncTask(() -> {
			try {
				final List<Node> nodes = Tasks.await(
					Wearable.getNodeClient(appContext).getConnectedNodes()
				);
				if (nodes != null) {
					for (Node node : nodes) {
						if (node.getId() != null) {
							switch (component) {
								case TYPE_NOTIFICATION:
									Wearable.getMessageClient(appContext)
										.sendMessage(node.getId(), "/cancel-notification", null);
									break;
								case TYPE_ACTIVITY:
									Wearable.getMessageClient(appContext)
										.sendMessage(node.getId(), "/cancel-activity", null);
									break;
								default:
									break;
							}
						}
					}
				}
			} catch (ExecutionException e) {
				logger.info("cancelOnWearable: ExecutionException while trying to connect to wearable: {}", e.getMessage());
			} catch (InterruptedException e) {
				logger.info("cancelOnWearable: Interrupted while waiting for wearable client");
				// Restore interrupted state...
				Thread.currentThread().interrupt();
			}
		});
	}

	/**
	 * Return the current call duration in seconds.
	 *
	 * Return null if the call state is not CALLING.
	 */
	@Nullable Integer getCallDuration() {
		final Long start = this.callStartTimestamp;
		if (start == null) {
			return null;
		} else {
			final long seconds = (SystemClock.elapsedRealtime() - start) / 1000;
			if (seconds > Integer.MAX_VALUE) {
				return Integer.MAX_VALUE;
			}
			return (int) seconds;
		}
	}

	// Private helper methods

	/**
	 * Show a call notification.
	 */
	@WorkerThread
	private void showNotification(
		@NonNull ContactModel contact,
		long callId,
		@Nullable PendingIntent accept,
		@NonNull PendingIntent reject,
		final VoipCallOfferMessage msg,
		final VoipCallOfferData offerData,
		boolean isMuted
	) {
		final long timestamp = System.currentTimeMillis();
		final Bitmap avatar = this.contactService.getAvatar(contact, false);
		final PendingIntent inCallPendingIntent = createLaunchPendingIntent(contact.getIdentity(), msg);

		if (notificationManagerCompat.areNotificationsEnabled()) {
			final NotificationCompat.Builder nbuilder = new NotificationBuilderWrapper(this.appContext, NOTIFICATION_CHANNEL_CALL, isMuted);

			// Content
			nbuilder.setContentTitle(appContext.getString(R.string.voip_notification_title))
					.setContentText(appContext.getString(R.string.voip_notification_text, NameUtil.getDisplayNameOrNickname(contact, true)))
					.setOngoing(true)
					.setWhen(timestamp)
					.setAutoCancel(false)
					.setShowWhen(true);

			// We want a full screen notification
			// Set up the main intent to send the user to the incoming call screen
			nbuilder.setFullScreenIntent(inCallPendingIntent, true);
			nbuilder.setContentIntent(inCallPendingIntent);

			// Icons and colors
			nbuilder.setLargeIcon(avatar)
					.setSmallIcon(R.drawable.ic_phone_locked_white_24dp)
					.setColor(this.appContext.getResources().getColor(R.color.accent_light));

			// Alerting
			nbuilder.setPriority(NotificationCompat.PRIORITY_MAX)
					.setCategory(NotificationCompat.CATEGORY_CALL);

			// Privacy
			nbuilder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
					// TODO
					.setPublicVersion(new NotificationCompat.Builder(appContext, ConfigUtils.supportsNotificationChannels() ? NOTIFICATION_CHANNEL_CALL : null)
							.setContentTitle(appContext.getString(R.string.voip_notification_title))
							.setContentText(appContext.getString(R.string.notification_hidden_text))
							.setSmallIcon(R.drawable.ic_phone_locked_white_24dp)
							.setColor(appContext.getResources().getColor(R.color.accent_light))
							.build());

			// Add identity to notification for DND priority override
			String contactLookupUri = contactService.getAndroidContactLookupUriString(contact);
			if (contactLookupUri != null) {
				nbuilder.addPerson(contactLookupUri);
			}

			if (preferenceService.isVoiceCallVibrate() && !isMuted) {
				nbuilder.setVibrate(VIBRATE_PATTERN_INCOMING_CALL);
			} else if (!ConfigUtils.supportsNotificationChannels()) {
				nbuilder.setVibrate(VIBRATE_PATTERN_SILENT);
			}

			// Actions
			final SpannableString rejectString = new SpannableString(appContext.getString(R.string.voip_reject));
			rejectString.setSpan(new ForegroundColorSpan(Color.RED), 0, rejectString.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

			final SpannableString acceptString = new SpannableString(appContext.getString(R.string.voip_accept));
			acceptString.setSpan(new ForegroundColorSpan(Color.GREEN), 0, acceptString.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

			nbuilder.addAction(R.drawable.ic_call_end_grey600_24dp, rejectString, reject)
					.addAction(R.drawable.ic_call_grey600_24dp, acceptString, accept != null ? accept : inCallPendingIntent);

			// Build notification
			final Notification notification = nbuilder.build();

			// Set flags
			notification.flags |= NotificationCompat.FLAG_INSISTENT | NotificationCompat.FLAG_NO_CLEAR | NotificationCompat.FLAG_ONGOING_EVENT;

			synchronized (this.callNotificationTags) {
				this.notificationManagerCompat.notify(contact.getIdentity(), INCOMING_CALL_NOTIFICATION_ID, notification);
				this.callNotificationTags.add(contact.getIdentity());
			}

		} else {
			// notifications disabled in system settings - fire inCall pending intent to show CallActivity
			try {
				inCallPendingIntent.send();
			} catch (PendingIntent.CanceledException e) {
				logger.error("Could not send inCallPendingIntent", e);
			}
		}

		// WEARABLE
		if (ConfigUtils.isPlayServicesInstalled(appContext)){
			this.showWearableNotification(contact, callId, avatar);
		}
	}

	private void playRingtone(MessageReceiver messageReceiver, boolean isMuted) {
		final Uri ringtoneUri = this.ringtoneService.getVoiceCallRingtone(messageReceiver.getUniqueIdString());

		if (ringtoneUri != null) {
			if (ringtonePlayer != null) {
				stopRingtone();
			}

			boolean isSystemMuted = !notificationManagerCompat.areNotificationsEnabled();

			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				/* we do not play a ringtone sound if system-wide DND is enabled - except for starred contacts */
				switch (notificationManager.getCurrentInterruptionFilter()) {
					case NotificationManager.INTERRUPTION_FILTER_NONE:
						isSystemMuted = true;
						break;
					case NotificationManager.INTERRUPTION_FILTER_PRIORITY:
						isSystemMuted = !DNDUtil.getInstance().isStarredContact(messageReceiver);
						break;
					default:
						break;
				}
			}

			if (!isMuted && !isSystemMuted) {
				audioManager.requestAudioFocus(this, AudioManager.STREAM_RING, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
				ringtonePlayer = new MediaPlayerStateWrapper();
				ringtonePlayer.setStateListener(new MediaPlayerStateWrapper.StateListener() {
					@Override
					public void onCompletion(MediaPlayer mp) {
					}

					@Override
					public void onPrepared(MediaPlayer mp) {
						ringtonePlayer.start();
					}
				});
				ringtonePlayer.setLooping(true);
				ringtonePlayer.setAudioStreamType(AudioManager.STREAM_RING);

				try {
					ringtonePlayer.setDataSource(appContext, ringtoneUri);
					ringtonePlayer.prepareAsync();
				} catch (Exception e) {
					stopRingtone();
				}
			}
		}
	}

	private synchronized void stopRingtone() {
		if (ringtonePlayer != null) {
			ringtonePlayer.stop();
			ringtonePlayer.reset();
			ringtonePlayer.release();
			ringtonePlayer = null;
		}

		try {
			audioManager.abandonAudioFocus(this);
		} catch (Exception e) {
			logger.info("Failed to abandon audio focus");
		} finally {
			this.ringtoneAudioFocusAbandoned.complete(null);
		}
	}

	/*
	 *  Send information to the companion app on the wearable device
	 */
	@WorkerThread
	private void showWearableNotification(
		@NonNull ContactModel contact,
		long callId,
		@Nullable Bitmap avatar
	) {
		final DataClient dataClient = Wearable.getDataClient(appContext);

		// Add data to the request
		final PutDataMapRequest putDataMapRequest = PutDataMapRequest.create("/incoming-call");
		putDataMapRequest.getDataMap().putLong(EXTRA_CALL_ID, callId);
		putDataMapRequest.getDataMap().putString(EXTRA_CONTACT_IDENTITY, contact.getIdentity());
		logger.debug("sending the following contactIdentity from VoipState to wearable " + contact.getIdentity());
		putDataMapRequest.getDataMap().putString("CONTACT_NAME", NameUtil.getDisplayNameOrNickname(contact, true));
		putDataMapRequest.getDataMap().putLong("CALL_TIME", System.currentTimeMillis());
		if (avatar != null) {
			final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			avatar.compress(Bitmap.CompressFormat.PNG, 100, buffer);
			putDataMapRequest.getDataMap().putByteArray("CONTACT_AVATAR", buffer.toByteArray());
		}

		final PutDataRequest request = putDataMapRequest.asPutDataRequest();
		request.setUrgent();

		dataClient.addListener(wearableListener);
		dataClient.putDataItem(request);
	}

	private PendingIntent createLaunchPendingIntent(
		@NonNull String identity,
		@Nullable VoipCallOfferMessage msg
	) {
		final Intent intent = new Intent(Intent.ACTION_MAIN, null);
		intent.setClass(appContext, CallActivity.class);
		intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
		intent.setData((Uri.parse("foobar://"+ SystemClock.elapsedRealtime())));
		intent.putExtra(EXTRA_ACTIVITY_MODE, CallActivity.MODE_INCOMING_CALL);
		intent.putExtra(EXTRA_CONTACT_IDENTITY, identity);
		intent.putExtra(EXTRA_IS_INITIATOR, false);
		if (msg != null) {
			final VoipCallOfferData data = msg.getData();
			intent.putExtra(EXTRA_CALL_ID, data.getCallIdOrDefault(0L));
		}

		// PendingIntent that can be used to launch the InCallActivity.  The
		// system fires off this intent if the user pulls down the windowshade
		// and clicks the notification's expanded view.  It's also used to
		// launch the InCallActivity immediately when when there's an incoming
		// call (see the "fullScreenIntent" field below).
		return PendingIntent.getActivity(appContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
	}

	/**
	 * Add a new ICE candidate to the cache.
	 */
	private void cacheCandidate(String identity, VoipICECandidatesData data) {
		logger.debug("{}: Caching candidate from {}", data.getCallIdOrDefault(0L), identity);
		synchronized (this.candidatesCache) {
			if (this.candidatesCache.containsKey(identity)) {
				List<VoipICECandidatesData> candidates = this.candidatesCache.get(identity);
				candidates.add(data);
			} else {
				List<VoipICECandidatesData> candidates = new LinkedList<>();
				candidates.add(data);
				this.candidatesCache.put(identity, candidates);
			}
		}
	}

	/**
	 * Create a new video context.
	 *
	 * Throws an `IllegalStateException` if a video context already exists.
 	 */
	void createVideoContext() throws IllegalStateException {
		logger.trace("createVideoContext");
		if (this.videoContext != null) {
			throw new IllegalStateException("Video context already exists");
		}
		this.videoContext = new VideoContext();
		this.videoContextFuture.complete(this.videoContext);
	}

	/**
	 * Return a reference to the video context instance.
	 */
	@Nullable
	public VideoContext getVideoContext() {
		return this.videoContext;
	}

	/**
	 * Return a future that resolves with the video context instance.
	 */
	@NonNull
	public CompletableFuture<VideoContext> getVideoContextFuture() {
		return this.videoContextFuture;
	}

	/**
	 * Release resources associated with the video context instance.
	 *
	 * It's safe to call this method multiple times.
	 */
	void releaseVideoContext() {
		if (this.videoContext != null) {
			this.videoContext.release();
			this.videoContext = null;
			this.videoContextFuture = new CompletableFuture<>();
		}
	}

	public int getVideoRenderMode() {
		return videoRenderMode;
	}

	public void setVideoRenderMode(int videoRenderMode) {
		this.videoRenderMode = videoRenderMode;
	}

	@Override
	public void onAudioFocusChange(int focusChange) {
		logger.info("Audio Focus change: " + focusChange);
	}

	public synchronized CompletableFuture<Void> getRingtoneAudioFocusAbandoned() {
		return this.ringtoneAudioFocusAbandoned;
	}
}
