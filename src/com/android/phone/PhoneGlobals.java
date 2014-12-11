/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.phone;

import android.app.AlertDialog;
import android.app.Activity;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.app.TaskStackBuilder;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.IBluetoothHeadsetPhone;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.media.AudioManager;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UpdateLock;
import android.os.UserHandle;
import android.preference.PreferenceManager;
import android.provider.Settings.System;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;
import android.view.KeyEvent;
import android.view.WindowManager;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.MmiCode;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.TelephonyCapabilities;
import com.android.internal.telephony.TelephonyConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyIntents2;
import com.android.internal.telephony.cdma.TtyIntent;
import com.android.phone.common.CallLogAsync;
import com.android.phone.OtaUtils.CdmaOtaScreenState;
import com.android.phone.WiredHeadsetManager.WiredHeadsetListener;
import com.android.server.sip.SipService;
import com.android.services.telephony.common.AudioMode;

import dalvik.system.DexClassLoader;
import java.lang.reflect.Constructor;


/**
 * Global state for the telephony subsystem when running in the primary
 * phone process.
 */
public class PhoneGlobals extends ContextWrapper implements WiredHeadsetListener {
    /* package */ static final String LOG_TAG = "PhoneApp";

    /**
     * Phone app-wide debug level:
     *   0 - no debug logging
     *   1 - normal debug logging if ro.debuggable is set (which is true in
     *       "eng" and "userdebug" builds but not "user" builds)
     *   2 - ultra-verbose debug logging
     *
     * Most individual classes in the phone app have a local DBG constant,
     * typically set to
     *   (PhoneApp.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1)
     * or else
     *   (PhoneApp.DBG_LEVEL >= 2)
     * depending on the desired verbosity.
     *
     * ***** DO NOT SUBMIT WITH DBG_LEVEL > 0 *************
     */
    /* package */ static final int DBG_LEVEL = 1;

    private static final boolean DBG =
            (PhoneGlobals.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);
    private static final boolean VDBG = (PhoneGlobals.DBG_LEVEL >= 2);

    // Message codes; see mHandler below.
    private static final int EVENT_SIM_NETWORK_LOCKED = 3;
    private static final int EVENT_SIM_STATE_CHANGED = 8;
    private static final int EVENT_DATA_ROAMING_DISCONNECTED = 10;
    private static final int EVENT_DATA_ROAMING_OK = 11;
    private static final int EVENT_UNSOL_CDMA_INFO_RECORD = 12;
    private static final int EVENT_DOCK_STATE_CHANGED = 13;
    private static final int EVENT_TTY_PREFERRED_MODE_CHANGED = 14;
    private static final int EVENT_TTY_MODE_GET = 15;
    private static final int EVENT_TTY_MODE_SET = 16;
    private static final int EVENT_START_SIP_SERVICE = 17;

    private static final int EVENT_SIM2_NETWORK_LOCKED = 103;
    private static final int EVENT_SIM2_STATE_CHANGED = 108;
    private static final int EVENT_ICC_HOT_SWAP = 21;
    private static final int EVENT_DELAY_PRIMARY_ABSENT_NOTIFICATION = 22;
    private static final int EVENT_SWITCH_DATA_FOLLOW_SINGLE_SIM = 24;
    private static final int DATA_FOLLOW_SINGLE_SIM_DELAY = 20000;  // msec
    public static final String ACTION_CALL_BACK_FROM_NOTIFICATION_SIM2 =
            "com.android.phone.ACTION_CALL_BACK_FROM_NOTIFICATION_SIM2";
    public static final String ACTION_CALL_BACK_FROM_NOTIFICATION_SIM1 =
            "com.android.phone.ACTION_CALL_BACK_FROM_NOTIFICATION_SIM1";

    // The MMI codes are also used by the InCallScreen.
    public static final int MMI_INITIATE = 51;
    public static final int MMI_COMPLETE = 52;
    public static final int MMI_CANCEL = 53;

    public static final int SUPP_SERVICE_FAILED = 54;

    public static final int MMI2_COMPLETE = 152;
    // Don't use message codes larger than 99 here; those are reserved for
    // the individual Activities of the Phone UI.

    /**
     * Allowable values for the wake lock code.
     *   SLEEP means the device can be put to sleep.
     *   PARTIAL means wake the processor, but we display can be kept off.
     *   FULL means wake both the processor and the display.
     */
    public enum WakeState {
        SLEEP,
        PARTIAL,
        FULL
    }

    /**
     * Intent Action used for hanging up the current call from Notification bar. This will
     * choose first ringing call, first active call, or first background call (typically in
     * HOLDING state).
     */
    public static final String ACTION_HANG_UP_ONGOING_CALL =
            "com.android.phone.ACTION_HANG_UP_ONGOING_CALL";

    /**
     * Intent Action used for making a phone call from Notification bar.
     * This is for missed call notifications.
     */
    public static final String ACTION_CALL_BACK_FROM_NOTIFICATION =
            "com.android.phone.ACTION_CALL_BACK_FROM_NOTIFICATION";

    /**
     * Intent Action used for sending a SMS from notification bar.
     * This is for missed call notifications.
     */
    public static final String ACTION_SEND_SMS_FROM_NOTIFICATION =
            "com.android.phone.ACTION_SEND_SMS_FROM_NOTIFICATION";

    private static PhoneGlobals sMe;

    // A few important fields we expose to the rest of the package
    // directly (rather than thru set/get methods) for efficiency.
    CallController callController;
    CallManager mCM;
	CallManager mCM2;
    CallNotifier notifier;
	CallNotifier notifier2;
    CallerInfoCache callerInfoCache;
    NotificationMgr notificationMgr;
    Phone phone;
    Phone phone2;
    PhoneInterfaceManager phoneMgr;
	PhoneInterfaceManager phoneMgr2;

    private AlertDialog mSuppServiceFailureDialog;

    private AudioRouter audioRouter;
    private BluetoothManager bluetoothManager;
    private CallCommandService callCommandService;
    private CallGatewayManager callGatewayManager;
    private CallHandlerServiceProxy callHandlerServiceProxy;
    private CallModeler callModeler;
    private CallModeler callModeler2;
    private CallStateMonitor callStateMonitor;
    private CallStateMonitor callStateMonitor2;
    private DTMFTonePlayer dtmfTonePlayer;
    private DTMFTonePlayer dtmfTonePlayer2;
    private IBluetoothHeadsetPhone mBluetoothPhone;
    private Ringer ringer;
    private Ringer ringer2;
	private WiredHeadsetManager wiredHeadsetManager;

    static int mDockState = Intent.EXTRA_DOCK_STATE_UNDOCKED;
    static boolean sVoiceCapable = true;
    static boolean mRatSettingDone = false;

    // Internal PhoneApp Call state tracker
    CdmaPhoneCallState cdmaPhoneCallState;

    // The currently-active PUK entry activity and progress dialog.
    // Normally, these are the Emergency Dialer and the subsequent
    // progress dialog.  null if there is are no such objects in
    // the foreground.
    private Activity mPUKEntryActivity;
    private ProgressDialog mPUKEntryProgressDialog;

    private boolean mIsSimPinEnabled;
    private String mCachedSimPin;

    // True if we are beginning a call, but the phone state has not changed yet
    private boolean mBeginningCall;

    // Last phone state seen by updatePhoneState()
    private PhoneConstants.State mLastPhoneState = PhoneConstants.State.IDLE;

    private WakeState mWakeState = WakeState.SLEEP;

    private PowerManager mPowerManager;
    private IPowerManager mPowerManagerService;
    private PowerManager.WakeLock mWakeLock;
    private PowerManager.WakeLock mPartialWakeLock;
    //private PowerManager.WakeLock mProximityWakeLock;
    private KeyguardManager mKeyguardManager;
    //private AccelerometerListener mAccelerometerListener;
    //private int mOrientation = AccelerometerListener.ORIENTATION_UNKNOWN;

    private UpdateLock mUpdateLock;

    // Broadcast receiver for various intent broadcasts (see onCreate())
    private final BroadcastReceiver mReceiver = new PhoneAppBroadcastReceiver();

    // Broadcast receiver purely for ACTION_MEDIA_BUTTON broadcasts
    private final BroadcastReceiver mMediaButtonReceiver = new MediaButtonBroadcastReceiver();

    /** boolean indicating restoring mute state on InCallScreen.onResume() */
    private boolean mShouldRestoreMuteOnInCallResume;

    /**
     * The singleton OtaUtils instance used for OTASP calls.
     *
     * The OtaUtils instance is created lazily the first time we need to
     * make an OTASP call, regardless of whether it's an interactive or
     * non-interactive OTASP call.
     */
    public OtaUtils otaUtils;

    // Following are the CDMA OTA information Objects used during OTA Call.
    // cdmaOtaProvisionData object store static OTA information that needs
    // to be maintained even during Slider open/close scenarios.
    // cdmaOtaConfigData object stores configuration info to control visiblity
    // of each OTA Screens.
    // cdmaOtaScreenState object store OTA Screen State information.
    public OtaUtils.CdmaOtaProvisionData cdmaOtaProvisionData;
    public OtaUtils.CdmaOtaConfigData cdmaOtaConfigData;
    public OtaUtils.CdmaOtaScreenState cdmaOtaScreenState;
    public OtaUtils.CdmaOtaInCallScreenUiState cdmaOtaInCallScreenUiState;

    // TTY feature enabled on this platform
    private boolean mTtyEnabled;
    // Current TTY operating mode selected by user
    private int mPreferredTtyMode = Phone.TTY_MODE_OFF;

    /**
     * Set the restore mute state flag. Used when we are setting the mute state
     * OUTSIDE of user interaction {@link PhoneUtils#startNewCall(Phone)}
     */
    /*package*/void setRestoreMuteOnInCallResume (boolean mode) {
        mShouldRestoreMuteOnInCallResume = mode;
    }

    Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            PhoneConstants.State phoneState;
            switch (msg.what) {
                // Starts the SIP service. It's a no-op if SIP API is not supported
                // on the deivce.
                // TODO: Having the phone process host the SIP service is only
                // temporary. Will move it to a persistent communication process
                // later.
                case EVENT_START_SIP_SERVICE:
                    SipService.start(getApplicationContext());
                    break;

                // TODO: This event should be handled by the lock screen, just
                // like the "SIM missing" and "Sim locked" cases (bug 1804111).
                case EVENT_SIM_NETWORK_LOCKED:
                case EVENT_SIM2_NETWORK_LOCKED:
                    if (getResources().getBoolean(R.bool.ignore_sim_network_locked_events)) {
                        // Some products don't have the concept of a "SIM network lock"
                        Log.i(LOG_TAG, "Ignoring EVENT_SIM_NETWORK_LOCKED event; "
                              + "not showing 'SIM network unlock' PIN entry screen");
                    } else {
                        // Normal case: show the "SIM network unlock" PIN entry screen.
                        // The user won't be able to do anything else until
                        // they enter a valid SIM network PIN.
                        Log.i(LOG_TAG, "show sim depersonal panel");
                        Phone panelPhone =
                                msg.what == EVENT_SIM_NETWORK_LOCKED
                                ? phone : phone2;
                        IccNetworkDepersonalizationPanel ndpPanel =
                                new IccNetworkDepersonalizationPanel(
                                PhoneGlobals.getInstance(), panelPhone);
                        ndpPanel.show();
                    }
                    break;

                case EVENT_DATA_ROAMING_DISCONNECTED:
                    notificationMgr.showDataDisconnectedRoaming();
                    break;

                case EVENT_DATA_ROAMING_OK:
                    notificationMgr.hideDataDisconnectedRoaming();
                    break;

                case MMI_COMPLETE:
                    onMMIComplete((AsyncResult) msg.obj);
                    break;

                case MMI2_COMPLETE:
                    onMMI2Complete((AsyncResult) msg.obj);
                    break;

                case MMI_CANCEL:
                    PhoneUtils.cancelMmiCode(phone);
                    break;

                case SUPP_SERVICE_FAILED:
                    onSuppServiceFailed((AsyncResult) msg.obj);
                    break;

                case EVENT_SIM_STATE_CHANGED:
                    // Marks the event where the SIM goes into ready state.
                    // Right now, this is only used for the PUK-unlocking
                    // process.
                    if (msg.obj.equals(IccCardConstants.INTENT_VALUE_ICC_READY)) {
                        // when the right event is triggered and there
                        // are UI objects in the foreground, we close
                        // them to display the lock panel.
                        if (mPUKEntryActivity != null) {
                            mPUKEntryActivity.finish();
                            mPUKEntryActivity = null;
                        }
                        if (mPUKEntryProgressDialog != null) {
                            mPUKEntryProgressDialog.dismiss();
                            mPUKEntryProgressDialog = null;
                        }
                    }
                    break;

                case EVENT_UNSOL_CDMA_INFO_RECORD:
                    //TODO: handle message here;
                    break;

                case EVENT_DOCK_STATE_CHANGED:
                    // If the phone is docked/undocked during a call, and no wired or BT headset
                    // is connected: turn on/off the speaker accordingly.
                    boolean inDockMode = false;
                    if (mDockState != Intent.EXTRA_DOCK_STATE_UNDOCKED) {
                        inDockMode = true;
                    }
                    if (VDBG) Log.d(LOG_TAG, "received EVENT_DOCK_STATE_CHANGED. Phone inDock = "
                            + inDockMode);

                    phoneState = getPhoneState();
                    if (phoneState == PhoneConstants.State.OFFHOOK &&
                            !wiredHeadsetManager.isHeadsetPlugged() &&
                            !bluetoothManager.isBluetoothHeadsetAudioOn()) {
                        audioRouter.setSpeaker(inDockMode);

                        PhoneUtils.turnOnSpeaker(getApplicationContext(), inDockMode, true);
                    }
                    break;

                case EVENT_TTY_PREFERRED_MODE_CHANGED:
                    // TTY mode is only applied if a headset is connected
                    int ttyMode;
                    if (wiredHeadsetManager.isHeadsetPlugged()) {
                        ttyMode = mPreferredTtyMode;
                    } else {
                        ttyMode = Phone.TTY_MODE_OFF;
                    }

                    if (TelephonyConstants.IS_DSDS) {
                        DualPhoneController.getInstance().getActivePhone().setTTYMode(
                                               ttyMode,
                                               mHandler.obtainMessage(EVENT_TTY_MODE_SET));
                    } else {
                        phone.setTTYMode(ttyMode, mHandler.obtainMessage(EVENT_TTY_MODE_SET));
                    }
                    break;

                case EVENT_TTY_MODE_GET:
                    handleQueryTTYModeResponse(msg);
                    break;

                case EVENT_TTY_MODE_SET:
                    handleSetTTYModeResponse(msg);
                    break;

                case EVENT_ICC_HOT_SWAP:
                    final int slot = msg.arg1;
                    final boolean isSimAdded = msg.arg2 > 0 ? true : false;
                    if (DBG) log("EVENT_ICC_HOT_SWAP,slot:" + slot);
                    onIccHotSwapped(slot, isSimAdded);
                    break;

               case EVENT_DELAY_PRIMARY_ABSENT_NOTIFICATION:
                    if (DBG) log("EVENT_DELAY_PRIMARY_ABSENT_NOTIFICATION");
                    updatePrimarySimAbsentNotify();
                    break;

                case EVENT_SWITCH_DATA_FOLLOW_SINGLE_SIM:
                    if (DBG) log("EVENT_SWITCH_DATA_FOLLOW_SINGLE_SIM");
                    trySwitchingDataSim();
                    break;
            }
        }
    };

    public PhoneGlobals(Context context) {
        super(context);
        sMe = this;
    }

    public void onCreate() {
        if (VDBG) Log.v(LOG_TAG, "onCreate()...");

        ContentResolver resolver = getContentResolver();

        // Cache the "voice capable" flag.
        // This flag currently comes from a resource (which is
        // overrideable on a per-product basis):
        sVoiceCapable =
                getResources().getBoolean(com.android.internal.R.bool.config_voice_capable);
        // ...but this might eventually become a PackageManager "system
        // feature" instead, in which case we'd do something like:
        // sVoiceCapable =
        //   getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY_VOICE_CALLS);

        if (phone == null) {
            if (TelephonyConstants.IS_DSDS)
                createDSDSPhones();
            else
                createStandardPhone();

            // Read platform settings for TTY feature

            // Register for misc other intent broadcasts.
            // intentFilter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED); //not relevant in kk?
            // intentFilter.addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED);      //not relevant in kk?
            // intentFilter.addAction(Intent.ACTION_HEADSET_PLUG);  //not relevant in kk?

            // Use a separate receiver for ACTION_MEDIA_BUTTON broadcasts,
            // since we need to manually adjust its priority (to make sure
            // we get these intents *before* the media player.)
            // TODO verify the independent priority doesn't need to be handled thanks to the
            //  private intent handler registration
            // Make sure we're higher priority than the media player's
            // MediaButtonIntentReceiver (which currently has the default
            // priority of zero; see apps/Music/AndroidManifest.xml.)
            //
            // register the component so it gets priority for calls

            //set the default values for the preferences in the phone.


            // Make sure the audio mode (along with some
            // audio-mode-related state of our own) is initialized
            // correctly, given the current state of the phone.
        }

        if (TelephonyCapabilities.supportsOtasp(phone)) {
            cdmaOtaProvisionData = new OtaUtils.CdmaOtaProvisionData();
            cdmaOtaConfigData = new OtaUtils.CdmaOtaConfigData();
            cdmaOtaScreenState = new OtaUtils.CdmaOtaScreenState();
            cdmaOtaInCallScreenUiState = new OtaUtils.CdmaOtaInCallScreenUiState();
        }

        // XXX pre-load the SimProvider so that it's ready
        resolver.getType(Uri.parse("content://icc/adn"));

        // start with the default value to set the mute state.
        mShouldRestoreMuteOnInCallResume = false;

        // TODO: Register for Cdma Information Records
        // phone.registerCdmaInformationRecord(mHandler, EVENT_UNSOL_CDMA_INFO_RECORD, null);

        // Read TTY settings and store it into BP NV.
        // AP owns (i.e. stores) the TTY setting in AP settings database and pushes the setting
        // to BP at power up (BP does not need to make the TTY setting persistent storage).
        // This way, there is a single owner (i.e AP) for the TTY setting in the phone.
        if (mTtyEnabled) {
            mPreferredTtyMode = android.provider.Settings.Secure.getInt(
                    phone.getContext().getContentResolver(),
                    android.provider.Settings.Secure.PREFERRED_TTY_MODE,
                    Phone.TTY_MODE_OFF);
            mHandler.sendMessage(mHandler.obtainMessage(EVENT_TTY_PREFERRED_MODE_CHANGED, 0));
        }
        // Read HAC settings and configure audio hardware
        if (getResources().getBoolean(R.bool.hac_enabled)) {
            int hac = android.provider.Settings.System.getInt(phone.getContext().getContentResolver(),
                                                              android.provider.Settings.System.HEARING_AID,
                                                              0);
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            audioManager.setParameter(CallFeaturesSetting.HAC_KEY, hac != 0 ?
                                      CallFeaturesSetting.HAC_VAL_ON :
                                      CallFeaturesSetting.HAC_VAL_OFF);
        }

        if (TelephonyConstants.IS_DSDS) {
            Intent intent = new Intent(TelephonyConstants.ACTION_DATA_SIM_SWITCH);
            intent.putExtra(TelephonyConstants.EXTRA_SWITCH_STAGE, TelephonyConstants.PHONE_CREATION_DONE);
            sendBroadcast(intent);
            SimSwitchingHandler.getInstance();
        }
   }

   SimSwitchingHandler getSimSwitchingHandler() {
       return SimSwitchingHandler.getInstance();
   }

    void createStandardPhone() {
            // Initialize the telephony framework
            PhoneFactory.makeDefaultPhones(this);

            // Get the default phone
            phone = PhoneFactory.getDefaultPhone();

            // Start TelephonyDebugService After the default phone is created.
            Intent intent = new Intent(this, TelephonyDebugService.class);
            startService(intent);

            mCM = CallManager.getInstance();
            mCM.registerPhone(phone);

            // Create the NotificationMgr singleton, which is used to display
            // status bar icons and control other status bar behavior.
            notificationMgr = NotificationMgr.init(this);

            mHandler.sendEmptyMessage(EVENT_START_SIP_SERVICE);

            int phoneType = phone.getPhoneType();

            if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                // Create an instance of CdmaPhoneCallState and initialize it to IDLE
                cdmaPhoneCallState = new CdmaPhoneCallState();
                cdmaPhoneCallState.CdmaPhoneCallStateInit();
            }

            if (BluetoothAdapter.getDefaultAdapter() != null) {
                // Start BluetoothPhoneService even if device is not voice capable.
                // The device can still support VOIP.
                startService(new Intent(this, BluetoothPhoneService.class));
                bindService(new Intent(this, BluetoothPhoneService.class),
                            mBluetoothPhoneConnection, 0);
            } else {
                // Device is not bluetooth capable
                mBluetoothPhone = null;
            }

            // before registering for phone state changes
            mPowerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            mWakeLock = mPowerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK, LOG_TAG);
            // lock used to keep the processor awake, when we don't care for the display.
            mPartialWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK
                    | PowerManager.ON_AFTER_RELEASE, LOG_TAG);

            mKeyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);

            // get a handle to the service so that we can use it later when we
            // want to set the poke lock.
            mPowerManagerService = IPowerManager.Stub.asInterface(
                    ServiceManager.getService("power"));

            // Get UpdateLock to suppress system-update related events (e.g. dialog show-up)
            // during phone calls.
            mUpdateLock = new UpdateLock("phone");

            if (DBG) Log.d(LOG_TAG, "onCreate: mUpdateLock: " + mUpdateLock);

            CallLogger callLogger = new CallLogger(this, new CallLogAsync());

            callGatewayManager = CallGatewayManager.getInstance();

            // Create the CallController singleton, which is the interface
            // to the telephony layer for user-initiated telephony functionality
            // (like making outgoing calls.)
            callController = CallController.init(this, callLogger, callGatewayManager);

            // Create the CallerInfoCache singleton, which remembers custom ring tone and
            // send-to-voicemail settings.
            //
            // The asynchronous caching will start just after this call.
            callerInfoCache = CallerInfoCache.init(this);

            // Monitors call activity from the telephony layer
            callStateMonitor = new CallStateMonitor(mCM);

            // Creates call models for use with CallHandlerService.
            callModeler = new CallModeler(callStateMonitor, mCM, callGatewayManager);

            // Plays DTMF Tones
            dtmfTonePlayer = new DTMFTonePlayer(mCM, callModeler);

            // Manages wired headset state
            wiredHeadsetManager = new WiredHeadsetManager(this);
            wiredHeadsetManager.addWiredHeadsetListener(this);

            // Bluetooth manager
            bluetoothManager = new BluetoothManager(this, mCM, callModeler);

            ringer = Ringer.init(this, bluetoothManager);

            // Audio router
            audioRouter = new AudioRouter(this, bluetoothManager, wiredHeadsetManager, mCM);

            // Service used by in-call UI to control calls
            callCommandService = new CallCommandService(this, mCM, callModeler, dtmfTonePlayer,
                    audioRouter);

            // Sends call state to the UI
            callHandlerServiceProxy = new CallHandlerServiceProxy(this, callModeler,
                    callCommandService, audioRouter);

            phoneMgr = PhoneInterfaceManager.init(this, phone, callHandlerServiceProxy, callModeler,
                    dtmfTonePlayer);

            // Create the CallNotifer singleton, which handles
            // asynchronous events from the telephony layer (like
            // launching the incoming-call UI when an incoming call comes
            // in.)
            notifier = CallNotifier.init(this, mCM, ringer, callLogger, callStateMonitor,
                    bluetoothManager, callModeler);

            // register connection tracking to PhoneUtils
            PhoneUtils.initializeConnectionHandler(mCM);
            // Read platform settings for TTY feature
            mTtyEnabled = getResources().getBoolean(R.bool.tty_enabled);
            // Register for misc other intent broadcasts.
            IntentFilter intentFilter =
                    new IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            intentFilter.addAction(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED);
            intentFilter.addAction(Intent.ACTION_DOCK_EVENT);
            intentFilter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
            intentFilter.addAction(TelephonyIntents.ACTION_RADIO_TECHNOLOGY_CHANGED);
            intentFilter.addAction(TelephonyIntents.ACTION_SERVICE_STATE_CHANGED);
            intentFilter.addAction(TelephonyIntents.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED);
            if (mTtyEnabled) {
                intentFilter.addAction(TtyIntent.TTY_PREFERRED_MODE_CHANGE_ACTION);
            }
            intentFilter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
            registerReceiver(mReceiver, intentFilter);
            // Use a separate receiver for ACTION_MEDIA_BUTTON broadcasts,
            // since we need to manually adjust its priority (to make sure
            // we get these intents *before* the media player.)
            IntentFilter mediaButtonIntentFilter =
                    new IntentFilter(Intent.ACTION_MEDIA_BUTTON);
            // TODO verify the independent priority doesn't need to be handled thanks to the
            //  private intent handler registration
            // Make sure we're higher priority than the media player's
            // MediaButtonIntentReceiver (which currently has the default
            // priority of zero; see apps/Music/AndroidManifest.xml.)
            mediaButtonIntentFilter.setPriority(1);
            //
            registerReceiver(mMediaButtonReceiver, mediaButtonIntentFilter);
            // register the component so it gets priority for calls
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            am.registerMediaButtonEventReceiverForCalls(new ComponentName(this.getPackageName(),
                    MediaButtonBroadcastReceiver.class.getName()));
            // register for ICC status
            IccCard sim = phone.getIccCard();
            if (sim != null) {
                if (VDBG) Log.v(LOG_TAG, "register for ICC status");
                sim.registerForNetworkLocked(mHandler, EVENT_SIM_NETWORK_LOCKED, null);
            }

            // register for MMI/USSD
            mCM.registerForMmiComplete(mHandler, MMI_COMPLETE, null);

            // register for SUPP SERVICE FAILURE
            mCM.registerForSuppServiceFailed(mHandler, SUPP_SERVICE_FAILED, null);

        // Make sure the audio mode (along with some
        // audio-mode-related state of our own) is initialized
        // correctly, given the current state of the phone.
        PhoneUtils.setAudioMode(mCM);
            // register connection tracking to PhoneUtils
            PhoneUtils.initializeConnectionHandler(mCM);
   }

    void createDSDSPhones() {
            // Initialize the telephony framework
            PhoneFactory.makeDefaultPhones(this);

            // Get the default phone
            phone = PhoneFactory.getDefaultPhone();
            phone2 = PhoneFactory.get2ndPhone();

            // Start TelephonyDebugService After the default phone is created.
            Intent intent = new Intent(this, TelephonyDebugService.class);
            startService(intent);

            mCM = CallManager.getInstance();
            mCM.registerPhone(phone);

            mCM2 = CallManager.getInstance2();
            mCM2.registerPhone(phone2);

            DualPhoneController.init(this);

            // Create the NotificationMgr singleton, which is used to display
            // status bar icons and control other status bar behavior.
            notificationMgr = NotificationMgr.init(this);

            //phoneMgr = PhoneInterfaceManager.init(this, phone);
            //phoneMgr2 = PhoneInterfaceManager.init2(this, phone2);

            mHandler.sendEmptyMessage(EVENT_START_SIP_SERVICE);

            int phoneType = phone.getPhoneType();

            if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                // Create an instance of CdmaPhoneCallState and initialize it to IDLE
                cdmaPhoneCallState = new CdmaPhoneCallState();
                cdmaPhoneCallState.CdmaPhoneCallStateInit();
            }

            if (BluetoothAdapter.getDefaultAdapter() != null) {
                // Start BluetoothPhoneService even if device is not voice capable.
                // The device can still support VOIP.
                startService(new Intent(this, BluetoothPhoneService.class));
                bindService(new Intent(this, BluetoothPhoneService.class),
                            mBluetoothPhoneConnection, 0);
            } else {
                // Device is not bluetooth capable
                mBluetoothPhone = null;
            }

            //ringer = Ringer.init(this);   //not relevant in kk?
            //ringer2 = Ringer.init2(this); //not relevant in kk?

            // before registering for phone state changes
            mPowerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            mWakeLock = mPowerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK, LOG_TAG);
            // lock used to keep the processor awake, when we don't care for the display.
            mPartialWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK
                    | PowerManager.ON_AFTER_RELEASE, LOG_TAG);
            // Wake lock used to control proximity sensor behavior.
            /* 
            if (mPowerManager.isWakeLockLevelSupported(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                mProximityWakeLock = mPowerManager.newWakeLock(
                        PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, LOG_TAG);
            }
            if (DBG) Log.d(LOG_TAG, "onCreate: mProximityWakeLock: " + mProximityWakeLock);

            // create mAccelerometerListener only if we are using the proximity sensor
            if (proximitySensorModeEnabled()) {
                mAccelerometerListener = new AccelerometerListener(this, this);
            }
            */
            mKeyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);

            // get a handle to the service so that we can use it later when we
            // want to set the poke lock.
            mPowerManagerService = IPowerManager.Stub.asInterface(
                    ServiceManager.getService("power"));

            // Get UpdateLock to suppress system-update related events (e.g. dialog show-up)
            // during phone calls.
            mUpdateLock = new UpdateLock("phone");

            if (DBG) Log.d(LOG_TAG, "onCreate: mUpdateLock: " + mUpdateLock);

            CallLogger callLogger = new CallLogger(this, new CallLogAsync()); // refer to "createStandardPhone" below

            //callGatewayManager = new CallGatewayManager();
			callGatewayManager = CallGatewayManager.getInstance();

            // Create the CallController singleton, which is the interface
            // to the telephony layer for user-initiated telephony functionality
            // (like making outgoing calls.)
            callController = CallController.init(this, callLogger, callGatewayManager);

            // Create the CallerInfoCache singleton, which remembers custom ring tone and
            // send-to-voicemail settings.
            //
            // The asynchronous caching will start just after this call.
            callerInfoCache = CallerInfoCache.init(this);

            // Monitors call activity from the telephony layer
            callStateMonitor = new CallStateMonitor(mCM);   // refer to "createStandardPhone" below
            callStateMonitor2 = new CallStateMonitor(mCM2);

            // Creates call models for use with CallHandlerService.
            callModeler = new CallModeler(callStateMonitor, mCM, callGatewayManager);
            callModeler2 = new CallModeler(callStateMonitor2, mCM2, callGatewayManager);

            // Plays DTMF Tones
            dtmfTonePlayer = new DTMFTonePlayer(mCM, callModeler);
            dtmfTonePlayer2 = new DTMFTonePlayer(mCM2, callModeler2);

            // Manages wired headset state
            wiredHeadsetManager = new WiredHeadsetManager(this);
            wiredHeadsetManager.addWiredHeadsetListener(this);

            // Bluetooth manager
            bluetoothManager = new BluetoothManager(this, mCM, callModeler);
            bluetoothManager.init2(callModeler2);

            ringer = Ringer.init(this, bluetoothManager);
            ringer2 = Ringer.init(this, bluetoothManager);

            // Audio router
            audioRouter = new AudioRouter(this, bluetoothManager, wiredHeadsetManager, mCM);

            // Service used by in-call UI to control calls
            callCommandService = new CallCommandService(this, mCM, callModeler, dtmfTonePlayer,
                    audioRouter);

            // Sends call state to the UI
            callHandlerServiceProxy = new CallHandlerServiceProxy(this, callModeler,
                    callCommandService, audioRouter);

            phoneMgr = PhoneInterfaceManager.init(this, phone, callHandlerServiceProxy, callModeler, dtmfTonePlayer );
   
            phoneMgr2 = PhoneInterfaceManager.init2(this, phone2, callHandlerServiceProxy,callModeler2, dtmfTonePlayer2);

            // Create the CallNotifer singleton, which handles
            // asynchronous events from the telephony layer (like
            // launching the incoming-call UI when an incoming call comes
            // in.)
            notifier = CallNotifier.init(this, mCM, ringer, callLogger, callStateMonitor,
                    bluetoothManager, callModeler);
            notifier2 = CallNotifier.init2(this, mCM2, ringer2, callLogger, callStateMonitor2,
                    bluetoothManager, callModeler2);

            // register for ICC status
            IccCard sim = phone.getIccCard();
            if (sim != null) {
                if (VDBG) Log.v(LOG_TAG, "register for ICC status");
                sim.registerForNetworkLocked(mHandler, EVENT_SIM_NETWORK_LOCKED, null);
            }

            // register for ICC status
            IccCard sim2 = phone2.getIccCard();
            if (sim2 != null) {
                if (VDBG) Log.v(LOG_TAG, "register for ICC2 status");
                sim2.registerForNetworkLocked(mHandler, EVENT_SIM2_NETWORK_LOCKED, null);
            }

            // register for MMI/USSD
            mCM.registerForMmiComplete(mHandler, MMI_COMPLETE, null);
            mCM2.registerForMmiComplete(mHandler, MMI2_COMPLETE, null);

            // register for SUPP SERVICE FAILURE
            mCM.registerForSuppServiceFailed(mHandler, SUPP_SERVICE_FAILED, null);

            // register connection tracking to PhoneUtils
            PhoneUtils.initializeConnectionHandler(mCM);
            PhoneUtils.initializeConnectionHandler(mCM2);
            OemHookInterfaceManager.init();
        // Read platform settings for TTY feature
        mTtyEnabled = getResources().getBoolean(R.bool.tty_enabled);
        IntentFilter intentFilter =
                new IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intentFilter.addAction(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED);
        intentFilter.addAction(Intent.ACTION_DOCK_EVENT);
        intentFilter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        intentFilter.addAction(TelephonyIntents.ACTION_RADIO_TECHNOLOGY_CHANGED);
        intentFilter.addAction(TelephonyIntents.ACTION_SERVICE_STATE_CHANGED);
        intentFilter.addAction(TelephonyIntents.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED);
        intentFilter.addAction(TelephonyIntents2.ACTION_SIM_STATE_CHANGED);
        intentFilter.addAction(TelephonyIntents2.ACTION_SERVICE_STATE_CHANGED);
        intentFilter.addAction(TelephonyConstants.ACTION_ICC_HOT_SWAP);
        if (mTtyEnabled) {
            intentFilter.addAction(TtyIntent.TTY_PREFERRED_MODE_CHANGE_ACTION);
   }
        intentFilter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        registerReceiver(mReceiver, intentFilter);


        // Use a separate receiver for ACTION_MEDIA_BUTTON broadcasts,
        // since we need to manually adjust its priority (to make sure
        // we get these intents *before* the media player.)
        IntentFilter mediaButtonIntentFilter =
                new IntentFilter(Intent.ACTION_MEDIA_BUTTON);
        // TODO verify the independent priority doesn't need to be handled thanks to the
        //  private intent handler registration
        // Make sure we're higher priority than the media player's
        // MediaButtonIntentReceiver (which currently has the default
        // priority of zero; see apps/Music/AndroidManifest.xml.)
        mediaButtonIntentFilter.setPriority(1);
        //
        registerReceiver(mMediaButtonReceiver, mediaButtonIntentFilter);
        // register the component so it gets priority for calls
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        am.registerMediaButtonEventReceiverForCalls(new ComponentName(this.getPackageName(),
                MediaButtonBroadcastReceiver.class.getName()));


        // Make sure the audio mode (along with some
        // audio-mode-related state of our own) is initialized
        // correctly, given the current state of the phone.
        PhoneUtils.setAudioMode(mCM);
    }
    public void onConfigurationChanged(Configuration newConfig) {
        /*
        if (newConfig.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO) {
            mIsHardKeyboardOpen = true;
        } else {
            mIsHardKeyboardOpen = false;
        }

        // Update the Proximity sensor based on keyboard state
        updateProximitySensorMode(getPhoneState());
        */
    }

    /**
     * Returns the singleton instance of the PhoneApp.
     */
    static PhoneGlobals getInstance() {
        if (sMe == null) {
            throw new IllegalStateException("No PhoneGlobals here!");
        }
        return sMe;
    }

    /**
     * Returns the singleton instance of the PhoneApp if running as the
     * primary user, otherwise null.
     */
    static PhoneGlobals getInstanceIfPrimary() {
        return sMe;
    }

    /**
     * Returns the Phone associated with this instance
     */
    static Phone getPhone() {
        return getInstance().phone;
    }

    Ringer getRinger() {
        return ringer;
    }

    IBluetoothHeadsetPhone getBluetoothPhoneService() {
        return mBluetoothPhone;
    }

    /* package */ BluetoothManager getBluetoothManager() {
        return bluetoothManager;
    }

    /* package */ WiredHeadsetManager getWiredHeadsetManager() {
        return wiredHeadsetManager;
    }

    /* package */ AudioRouter getAudioRouter() {
        return audioRouter;
    }

    /* package */ CallModeler getCallModeler() {
        return callModeler;
    }

    /* package */ CallManager getCallManager() {
        return mCM;
    }

    //for dsds
    Ringer get2ndRinger() {
        return ringer2;
    }

    /* package */ CallModeler get2ndCallModeler() {
        return callModeler2;
    }

    /* package */ DTMFTonePlayer getDTMFTonePlayer() {
        return dtmfTonePlayer;
    }

    /* package */ DTMFTonePlayer get2ndDTMFTonePlayer() {
        return dtmfTonePlayer2;
    }

    /**
     * Returns an Intent that can be used to go to the "Call log"
     * UI (aka CallLogActivity) in the Contacts app.
     *
     * Watch out: there's no guarantee that the system has any activity to
     * handle this intent.  (In particular there may be no "Call log" at
     * all on on non-voice-capable devices.)
     */
    /* package */ static Intent createCallLogIntent() {
        Intent intent = new Intent(Intent.ACTION_VIEW, null);
        intent.setType("vnd.android.cursor.dir/calls");
        return intent;
    }

    /* package */static PendingIntent createPendingCallLogIntent(Context context) {
        final Intent callLogIntent = PhoneGlobals.createCallLogIntent();
        final TaskStackBuilder taskStackBuilder = TaskStackBuilder.create(context);
        taskStackBuilder.addNextIntent(callLogIntent);
        return taskStackBuilder.getPendingIntent(0, 0);
    }

    /**
     * Returns PendingIntent for hanging up ongoing phone call. This will typically be used from
     * Notification context.
     */
    /* package */ static PendingIntent createHangUpOngoingCallPendingIntent(Context context) {
        Intent intent = new Intent(PhoneGlobals.ACTION_HANG_UP_ONGOING_CALL, null,
                context, NotificationBroadcastReceiver.class);
        return PendingIntent.getBroadcast(context, 0, intent, 0);
    }

    /* package */ static PendingIntent getCallBackPendingIntent(Context context, String number) {
        Intent intent = new Intent(ACTION_CALL_BACK_FROM_NOTIFICATION,
                Uri.fromParts(Constants.SCHEME_TEL, number, null),
                context, NotificationBroadcastReceiver.class);
        return PendingIntent.getBroadcast(context, 0, intent, 0);
    }

    /* package */ static PendingIntent getCallBackPendingIntent(Context context, String number,
            boolean fromSlot2) {
        Intent intent = null;
        if (fromSlot2) {
            intent = new Intent(ACTION_CALL_BACK_FROM_NOTIFICATION_SIM2,
                    Uri.fromParts(Constants.SCHEME_TEL, number, null),
                    context, NotificationBroadcastReceiver.class);
        } else {
            intent = new Intent(ACTION_CALL_BACK_FROM_NOTIFICATION_SIM1,
                    Uri.fromParts(Constants.SCHEME_TEL, number, null),
                    context, NotificationBroadcastReceiver.class);
        }
        return PendingIntent.getBroadcast(context, 0, intent, 0);
    }

    /* package */ static PendingIntent getSendSmsFromNotificationPendingIntent(
            Context context, String number) {
        Intent intent = new Intent(ACTION_SEND_SMS_FROM_NOTIFICATION,
                Uri.fromParts(Constants.SCHEME_SMSTO, number, null),
                context, NotificationBroadcastReceiver.class);
        return PendingIntent.getBroadcast(context, 0, intent, 0);
    }

    boolean isSimPinEnabled() {
        return mIsSimPinEnabled;
    }

    boolean authenticateAgainstCachedSimPin(String pin) {
        return (mCachedSimPin != null && mCachedSimPin.equals(pin));
    }

    void setCachedSimPin(String pin) {
        mCachedSimPin = pin;
    }

    /**
     * Handles OTASP-related events from the telephony layer.
     *
     * While an OTASP call is active, the CallNotifier forwards
     * OTASP-related telephony events to this method.
     */
    void handleOtaspEvent(Message msg) {
        if (DBG) Log.d(LOG_TAG, "handleOtaspEvent(message " + msg + ")...");

        if (otaUtils == null) {
            // We shouldn't be getting OTASP events without ever
            // having started the OTASP call in the first place!
            Log.w(LOG_TAG, "handleOtaEvents: got an event but otaUtils is null! "
                  + "message = " + msg);
            return;
        }

        otaUtils.onOtaProvisionStatusChanged((AsyncResult) msg.obj);
    }

    /**
     * Similarly, handle the disconnect event of an OTASP call
     * by forwarding it to the OtaUtils instance.
     */
    /* package */ void handleOtaspDisconnect() {
        if (DBG) Log.d(LOG_TAG, "handleOtaspDisconnect()...");

        if (otaUtils == null) {
            // We shouldn't be getting OTASP events without ever
            // having started the OTASP call in the first place!
            Log.w(LOG_TAG, "handleOtaspDisconnect: otaUtils is null!");
            return;
        }

        otaUtils.onOtaspDisconnect();
    }

    /**
     * Sets the activity responsible for un-PUK-blocking the device
     * so that we may close it when we receive a positive result.
     * mPUKEntryActivity is also used to indicate to the device that
     * we are trying to un-PUK-lock the phone. In other words, iff
     * it is NOT null, then we are trying to unlock and waiting for
     * the SIM to move to READY state.
     *
     * @param activity is the activity to close when PUK has
     * finished unlocking. Can be set to null to indicate the unlock
     * or SIM READYing process is over.
     */
    void setPukEntryActivity(Activity activity) {
        mPUKEntryActivity = activity;
    }

    Activity getPUKEntryActivity() {
        return mPUKEntryActivity;
    }

    /**
     * Sets the dialog responsible for notifying the user of un-PUK-
     * blocking - SIM READYing progress, so that we may dismiss it
     * when we receive a positive result.
     *
     * @param dialog indicates the progress dialog informing the user
     * of the state of the device.  Dismissed upon completion of
     * READYing process
     */
    void setPukEntryProgressDialog(ProgressDialog dialog) {
        mPUKEntryProgressDialog = dialog;
    }

    ProgressDialog getPUKEntryProgressDialog() {
        return mPUKEntryProgressDialog;
    }

    /**
     * Controls whether or not the screen is allowed to sleep.
     *
     * Once sleep is allowed (WakeState is SLEEP), it will rely on the
     * settings for the poke lock to determine when to timeout and let
     * the device sleep {@link PhoneGlobals#setScreenTimeout}.
     *
     * @param ws tells the device to how to wake.
     */
    /* package */ void requestWakeState(WakeState ws) {
        if (VDBG) Log.d(LOG_TAG, "requestWakeState(" + ws + ")...");
        synchronized (this) {
            if (mWakeState != ws) {
                switch (ws) {
                    case PARTIAL:
                        // acquire the processor wake lock, and release the FULL
                        // lock if it is being held.
                        mPartialWakeLock.acquire();
                        if (mWakeLock.isHeld()) {
                            mWakeLock.release();
                        }
                        break;
                    case FULL:
                        // acquire the full wake lock, and release the PARTIAL
                        // lock if it is being held.
                        mWakeLock.acquire();
                        if (mPartialWakeLock.isHeld()) {
                            mPartialWakeLock.release();
                        }
                        break;
                    case SLEEP:
                    default:
                        // release both the PARTIAL and FULL locks.
                        if (mWakeLock.isHeld()) {
                            mWakeLock.release();
                        }
                        if (mPartialWakeLock.isHeld()) {
                            mPartialWakeLock.release();
                        }
                        break;
                }
                mWakeState = ws;
            }
        }
    }

    /**
     * If we are not currently keeping the screen on, then poke the power
     * manager to wake up the screen for the user activity timeout duration.
     */
    /* package */ void wakeUpScreen() {
        synchronized (this) {
            if (mWakeState == WakeState.SLEEP) {
                if (DBG) Log.d(LOG_TAG, "pulse screen lock");
                mPowerManager.wakeUp(SystemClock.uptimeMillis());
            }
        }
    }

    /**
     * Sets the wake state and screen timeout based on the current state
     * of the phone, and the current state of the in-call UI.
     *
     * This method is a "UI Policy" wrapper around
     * {@link PhoneGlobals#requestWakeState} and {@link PhoneGlobals#setScreenTimeout}.
     *
     * It's safe to call this method regardless of the state of the Phone
     * (e.g. whether or not it's idle), and regardless of the state of the
     * Phone UI (e.g. whether or not the InCallScreen is active.)
     */
    /* package */ void updateWakeState() {
        PhoneConstants.State state = getPhoneState();

        // True if the speakerphone is in use.  (If so, we *always* use
        // the default timeout.  Since the user is obviously not holding
        // the phone up to his/her face, we don't need to worry about
        // false touches, and thus don't need to turn the screen off so
        // aggressively.)
        // Note that we need to make a fresh call to this method any
        // time the speaker state changes.  (That happens in
        // PhoneUtils.turnOnSpeaker().)
        boolean isSpeakerInUse = (state == PhoneConstants.State.OFFHOOK) && PhoneUtils.isSpeakerOn(this);

        // TODO (bug 1440854): The screen timeout *might* also need to
        // depend on the bluetooth state, but this isn't as clear-cut as
        // the speaker state (since while using BT it's common for the
        // user to put the phone straight into a pocket, in which case the
        // timeout should probably still be short.)

        // Decide whether to force the screen on or not.
        //
        // Force the screen to be on if the phone is ringing or dialing,
        // or if we're displaying the "Call ended" UI for a connection in
        // the "disconnected" state.
        // However, if the phone is disconnected while the user is in the
        // middle of selecting a quick response message, we should not force
        // the screen to be on.
        //
        boolean isRinging = (state == PhoneConstants.State.RINGING);
        boolean isDialing = (phone.getForegroundCall().getState() == Call.State.DIALING
                || ((TelephonyConstants.IS_DSDS) && phone2.getForegroundCall().getState() == Call.State.DIALING));
        boolean keepScreenOn = isRinging || isDialing;
        // keepScreenOn == true means we'll hold a full wake lock:
        requestWakeState(keepScreenOn ? WakeState.FULL : WakeState.SLEEP);
    }

    /**
     * Manually pokes the PowerManager's userActivity method.  Since we
     * set the {@link WindowManager.LayoutParams#INPUT_FEATURE_DISABLE_USER_ACTIVITY}
     * flag while the InCallScreen is active when there is no proximity sensor,
     * we need to do this for touch events that really do count as user activity
     * (like pressing any onscreen UI elements.)
     */
    /* package */ void pokeUserActivity() {
        if (VDBG) Log.d(LOG_TAG, "pokeUserActivity()...");
        mPowerManager.userActivity(SystemClock.uptimeMillis(), false);
    }

    /**
     * Notifies the phone app when the phone state changes.
     *
     * This method will updates various states inside Phone app (e.g. update-lock state, etc.)
     */
    /* package */ void updatePhoneState() {
        PhoneConstants.State state = getPhoneState();
        if (VDBG) log("updatePhoneState: " + state);

        if (state != mLastPhoneState) {
            mLastPhoneState = state;

            // Try to acquire or release UpdateLock.
            //
            // Watch out: we don't release the lock here when the screen is still in foreground.
            // At that time InCallScreen will release it on onPause().
            if (state != PhoneConstants.State.IDLE) {
                // UpdateLock is a recursive lock, while we may get "acquire" request twice and
                // "release" request once for a single call (RINGING + OFFHOOK and IDLE).
                // We need to manually ensure the lock is just acquired once for each (and this
                // will prevent other possible buggy situations too).
                if (!mUpdateLock.isHeld()) {
                    mUpdateLock.acquire();
                }
            } else {
                if (mUpdateLock.isHeld()) {
                    mUpdateLock.release();
                }
            }
        }
    }

    /* package */ static PhoneConstants.State getPhoneState() {
        if (TelephonyConstants.IS_DSDS) {
            PhoneConstants.State state = sMe.mCM.getState();
            PhoneConstants.State state2 = sMe.mCM2.getState();
            if (state == PhoneConstants.State.OFFHOOK || state2 == PhoneConstants.State.OFFHOOK) {
                return PhoneConstants.State.OFFHOOK;
            }
            if (state == PhoneConstants.State.RINGING || state2 == PhoneConstants.State.RINGING) {
                return PhoneConstants.State.RINGING;
            }
            return PhoneConstants.State.IDLE;
        } else {
            return sMe.mCM.getState();
        }
    /**
     * @return true if this device supports the "proximity sensor
     * auto-lock" feature while in-call (see updateProximitySensorMode()).
     */
    /* package */ //boolean proximitySensorModeEnabled() {
        //return (mProximityWakeLock != null);
    }

    KeyguardManager getKeyguardManager() {
        return mKeyguardManager;
    }

    private void onMMIComplete(AsyncResult r) {
        if (VDBG) Log.d(LOG_TAG, "onMMIComplete()...");
        MmiCode mmiCode = (MmiCode) r.result;
        PhoneUtils.displayMMIComplete(phone, getInstance(), mmiCode, null, null);
    }

    private void onMMI2Complete(AsyncResult r) {
        if (VDBG) Log.d(LOG_TAG, "onMMIComplete()...");
        MmiCode mmiCode = (MmiCode) r.result;
        PhoneUtils.displayMMIComplete(phone2, getInstance(), mmiCode, null, null);
    }

    private void initForNewRadioTechnology() {
        if (DBG) Log.d(LOG_TAG, "initForNewRadioTechnology...");

         if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA) {
            // Create an instance of CdmaPhoneCallState and initialize it to IDLE
            cdmaPhoneCallState = new CdmaPhoneCallState();
            cdmaPhoneCallState.CdmaPhoneCallStateInit();
        }
        if (TelephonyCapabilities.supportsOtasp(phone)) {
            //create instances of CDMA OTA data classes
            if (cdmaOtaProvisionData == null) {
                cdmaOtaProvisionData = new OtaUtils.CdmaOtaProvisionData();
            }
            if (cdmaOtaConfigData == null) {
                cdmaOtaConfigData = new OtaUtils.CdmaOtaConfigData();
            }
            if (cdmaOtaScreenState == null) {
                cdmaOtaScreenState = new OtaUtils.CdmaOtaScreenState();
            }
            if (cdmaOtaInCallScreenUiState == null) {
                cdmaOtaInCallScreenUiState = new OtaUtils.CdmaOtaInCallScreenUiState();
            }
        } else {
            //Clean up OTA data in GSM/UMTS. It is valid only for CDMA
            clearOtaState();
        }

        ringer.updateRingerContextAfterRadioTechnologyChange(this.phone);
        notifier.updateCallNotifierRegistrationsAfterRadioTechnologyChange();
        callStateMonitor.updateAfterRadioTechnologyChange();

        if (mBluetoothPhone != null) {
            try {
                mBluetoothPhone.updateBtHandsfreeAfterRadioTechnologyChange();
            } catch (RemoteException e) {
                Log.e(LOG_TAG, Log.getStackTraceString(new Throwable()));
            }
        }

        // Update registration for ICC status after radio technology change
        IccCard sim = phone.getIccCard();
        if (sim != null) {
            if (DBG) Log.d(LOG_TAG, "Update registration for ICC status...");

            //Register all events new to the new active phone
            sim.registerForNetworkLocked(mHandler, EVENT_SIM_NETWORK_LOCKED, null);
        }
    }


    /**
     * This is called when the wired headset state changes.
     */
    @Override
    public void onWiredHeadsetConnection(boolean pluggedIn) {
        PhoneConstants.State phoneState = mCM.getState();

        // Force TTY state update according to new headset state
        if (mTtyEnabled) {
            mHandler.sendMessage(mHandler.obtainMessage(EVENT_TTY_PREFERRED_MODE_CHANGED, 0));
        }
    }
	
    private void handleAirplaneModeforDualSim(boolean enabled) {
        ((PhoneProxy)phone).setRadioForFlightMode(enabled);
        ((PhoneProxy)phone2).setRadioForFlightMode(enabled);
        trySimSwitchingNotify("AirplanemodeChange");
    }

    /**
     * Receiver for misc intent broadcasts the Phone app cares about.
     */
    private class PhoneAppBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
                boolean enabled = System.getInt(getContentResolver(),
                        System.AIRPLANE_MODE_ON, 0) == 0;
                if (TelephonyConstants.IS_DSDS) {
                    handleAirplaneModeforDualSim(enabled);
                } else {
                    phone.setRadioPower(enabled);
                }
            } else if (action.equals(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED)) {
                if (VDBG) Log.d(LOG_TAG, "mReceiver: ACTION_ANY_DATA_CONNECTION_STATE_CHANGED");
                if (VDBG) Log.d(LOG_TAG, "- state: " + intent.getStringExtra(PhoneConstants.STATE_KEY));
                if (VDBG) Log.d(LOG_TAG, "- reason: "
                                + intent.getStringExtra(PhoneConstants.STATE_CHANGE_REASON_KEY));

                // The "data disconnected due to roaming" notification is shown
                // if (a) you have the "data roaming" feature turned off, and
                // (b) you just lost data connectivity because you're roaming.
                boolean disconnectedDueToRoaming =
                        !phone.getDataRoamingEnabled()
                        && "DISCONNECTED".equals(intent.getStringExtra(PhoneConstants.STATE_KEY))
                        && Phone.REASON_ROAMING_ON.equals(
                            intent.getStringExtra(PhoneConstants.STATE_CHANGE_REASON_KEY));
                mHandler.sendEmptyMessage(disconnectedDueToRoaming
                                          ? EVENT_DATA_ROAMING_DISCONNECTED
                                          : EVENT_DATA_ROAMING_OK);
            } else if ((action.equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED)) ||
                    action.equals(TelephonyIntents2.ACTION_SIM_STATE_CHANGED)) {
                // if an attempt to un-PUK-lock the device was made, while we're
                // receiving this state change notification, notify the handler.
                // NOTE: This is ONLY triggered if an attempt to un-PUK-lock has
                // been attempted.
                // mPUKEntryActicity is obselete code. It's always NULL in JB4.2.
                if (mPUKEntryActivity != null) {
                    mHandler.sendMessage(mHandler.obtainMessage(EVENT_SIM_STATE_CHANGED,
                                intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE)));
                }

                final String stateExtra = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
                final int slotId = intent.getIntExtra(TelephonyConstants.EXTRA_SLOT, 0);
                onSimStateChanged(slotId, stateExtra);
            } else if (action.equals(TelephonyIntents.ACTION_RADIO_TECHNOLOGY_CHANGED)) {
                String newPhone = intent.getStringExtra(PhoneConstants.PHONE_NAME_KEY);
                Log.d(LOG_TAG, "Radio technology switched. Now " + newPhone + " is active.");
                initForNewRadioTechnology();
            } else if (action.equals(TelephonyIntents.ACTION_SERVICE_STATE_CHANGED)) {
                handleServiceStateChanged(intent, true);
            } else if (action.equals(TelephonyIntents2.ACTION_SERVICE_STATE_CHANGED)) {
                handleServiceStateChanged(intent, false);
            } else if (action.equals(TelephonyIntents.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED)) {
                if (TelephonyCapabilities.supportsEcm(phone)) {
                    Log.d(LOG_TAG, "Emergency Callback Mode arrived in PhoneApp.");
                    // Start Emergency Callback Mode service
                    if (intent.getBooleanExtra("phoneinECMState", false)) {
                        context.startService(new Intent(context,
                                EmergencyCallbackModeService.class));
                    }
                } else {
                    // It doesn't make sense to get ACTION_EMERGENCY_CALLBACK_MODE_CHANGED
                    // on a device that doesn't support ECM in the first place.
                    Log.e(LOG_TAG, "Got ACTION_EMERGENCY_CALLBACK_MODE_CHANGED, "
                          + "but ECM isn't supported for phone: " + phone.getPhoneName());
                }
            } else if (action.equals(Intent.ACTION_DOCK_EVENT)) {
                mDockState = intent.getIntExtra(Intent.EXTRA_DOCK_STATE,
                        Intent.EXTRA_DOCK_STATE_UNDOCKED);
                if (VDBG) Log.d(LOG_TAG, "ACTION_DOCK_EVENT -> mDockState = " + mDockState);
                mHandler.sendMessage(mHandler.obtainMessage(EVENT_DOCK_STATE_CHANGED, 0));
            } else if (action.equals(TtyIntent.TTY_PREFERRED_MODE_CHANGE_ACTION)) {
                mPreferredTtyMode = intent.getIntExtra(TtyIntent.TTY_PREFFERED_MODE,
                                                       Phone.TTY_MODE_OFF);
                if (VDBG) Log.d(LOG_TAG, "mReceiver: TTY_PREFERRED_MODE_CHANGE_ACTION");
                if (VDBG) Log.d(LOG_TAG, "    mode: " + mPreferredTtyMode);
                mHandler.sendMessage(mHandler.obtainMessage(EVENT_TTY_PREFERRED_MODE_CHANGED, 0));
            } else if (action.equals(AudioManager.RINGER_MODE_CHANGED_ACTION)) {
                int ringerMode = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE,
                        AudioManager.RINGER_MODE_NORMAL);
                if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
                    notifier.silenceRinger();
                    if (TelephonyConstants.IS_DSDS) {
                        notifier2.silenceRinger();
                    }
                }
            } else if (TelephonyConstants.ACTION_ICC_HOT_SWAP.equals(action)) {
                boolean isSimAdded = intent.getBooleanExtra(TelephonyConstants.EXTRA_SWAP_IN, false);
                int slot = intent.getIntExtra(TelephonyConstants.EXTRA_SLOT, 0);
                if (DBG) Log.d(LOG_TAG,"recv ACTION_ICC_HOT_SWAP, slot:" + slot + ",isSimAdded:" + isSimAdded);
                mHandler.sendMessageDelayed(mHandler.obtainMessage(
                        EVENT_ICC_HOT_SWAP, slot, isSimAdded ? 1 : 0), 800);
            }
        }
    }

    private int mSimState[] = {-1, -1};
    private boolean mBothSimInited = false;

    void resetDualSimState() {
        mSimState[0] = mSimState[1]  = -1;
        mBothSimInited = false;
    }

    private void onSimStateChanged(int slot, String state) {
        if (VDBG) Log.d(LOG_TAG, "onSimStateChanged, slot: " + slot + ",state:" + state);

        if (!TelephonyConstants.IS_DSDS) {
            return;
        }

        final DualPhoneController duaPhoneController = DualPhoneController.getInstance();
        if (mSimSwitchingNotifyEnabled) {
            final boolean cancel = !duaPhoneController.isSecondarySimOnly();
            if (cancel) {
                if (DBG) Log.d(LOG_TAG, "to cancel Data SIM switching request");
                mHandler.removeMessages(EVENT_SWITCH_DATA_FOLLOW_SINGLE_SIM);
                updateSimSwitchingNotify(-1);
                mSimSwitchingNotifyEnabled = false;
            }
        }

        final int simState = TelephonyManager.getTmBySlot(slot).getSimState();
        mSimState[slot] = TelephonyManager.getTmBySlot(slot).getSimState();
        if (DBG) Log.d(LOG_TAG, "onSimStateChanged, sim state: " + mSimState[0] + "," + mSimState[1]);

        if (simState > 0 && mSimState[1 - slot] > 0 && !mBothSimInited) {
            mBothSimInited = true;
            trySimSwitchingNotify("SIM_INIT_COMPLETED");
        }
        mHandler.removeMessages(EVENT_DELAY_PRIMARY_ABSENT_NOTIFICATION);
        mHandler.sendMessageDelayed(mHandler.obtainMessage(EVENT_DELAY_PRIMARY_ABSENT_NOTIFICATION), 800);
    }

    private void onIccHotSwapped(int slot, boolean isSimAdded) {
        Log.d(LOG_TAG, "onIccHotSwapped, slot:" + slot + ",isSimAdded:" + isSimAdded);

        if (!TelephonyConstants.IS_DSDS) {
            return;
        }

        trySimSwitchingNotify("onIccHotSwapped");
    }

    public boolean isAirplaneModeOn() {
        return (System.getInt(getContentResolver(), System.AIRPLANE_MODE_ON, 0) > 0);
    }

    void trySwitchingDataSim() {
        if (needTipSimSwitching()) {
            int targetSlot = DualPhoneController.getInstance().getSecondarySimId();
            switchingDataSim(targetSlot);
        }
    }

    void switchingDataSim(int slot) {
        log("switchingDataSim NOW!");
        Intent newIntent = new Intent();
        newIntent.setClassName("com.android.phone", "com.android.phone.SimSwitchingActivity");
        newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        newIntent.putExtra(TelephonyConstants.EXTRA_SLOT, slot);
        startActivity(newIntent);
    }

    private void trySimSwitchingNotify(String reason) {
        int targetSlot = -1;
        boolean isAirplaneMode = isAirplaneModeOn();

        log("trySimSwitchingNotify, reason:" + reason);
        if (needTipSimSwitching()) {
            targetSlot = DualPhoneController.getInstance().getSecondarySimId();
        } else {
            log("No SimSwitchingNotify, isAirplaneMode:" + isAirplaneMode);
        }
        if (targetSlot >= 0 && DualPhoneController.isDataFollowSingleSim()) {
            mHandler.removeMessages(EVENT_SWITCH_DATA_FOLLOW_SINGLE_SIM);
            mHandler.sendMessageDelayed(mHandler.obtainMessage(
                        EVENT_SWITCH_DATA_FOLLOW_SINGLE_SIM),
                        DATA_FOLLOW_SINGLE_SIM_DELAY);
        }
        updateSimSwitchingNotify(targetSlot);
    }

    boolean needTipSimSwitching() {
        boolean isAirplaneMode = isAirplaneModeOn();
        boolean is2ndSimOnly = DualPhoneController.getInstance().isSecondarySimOnly();
        return (is2ndSimOnly && !isAirplaneMode);
    }

    private boolean mSimSwitchingNotifyEnabled = false;
    private void updateSimSwitchingNotify(int slot) {
        notificationMgr.updatePrimarySimSwitching(slot);
        mSimSwitchingNotifyEnabled = (slot >= 0);
        updatePrimarySimAbsentNotify();
    }

    private void updatePrimarySimAbsentNotify() {
        final DualPhoneController duaPhoneController = DualPhoneController.getInstance();
        final int simId = duaPhoneController.getPrimarySimId();
        boolean primarySimAbsent = duaPhoneController.isSimReallyAbsent(simId);
        boolean visible = primarySimAbsent && !mSimSwitchingNotifyEnabled;
        Log.d(LOG_TAG, "primarySimAbsent:" + primarySimAbsent
                + ",switchingNotify:" + mSimSwitchingNotifyEnabled);
        notificationMgr.updatePrimarySimAbsent(visible);
    }

    /**
     * Broadcast receiver for the ACTION_MEDIA_BUTTON broadcast intent.
     *
     * This functionality isn't lumped in with the other intents in
     * PhoneAppBroadcastReceiver because we instantiate this as a totally
     * separate BroadcastReceiver instance, since we need to manually
     * adjust its IntentFilter's priority (to make sure we get these
     * intents *before* the media player.)
     */
    private class MediaButtonBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            KeyEvent event = (KeyEvent) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            if (VDBG) Log.d(LOG_TAG,
                           "MediaButtonBroadcastReceiver.onReceive()...  event = " + event);
            if ((event != null)
                && (event.getKeyCode() == KeyEvent.KEYCODE_HEADSETHOOK)) {
                if (VDBG) Log.d(LOG_TAG, "MediaButtonBroadcastReceiver: HEADSETHOOK");
                boolean consumed = TelephonyConstants.IS_DSDS ?
                        PhoneUtils.handleHeadsetHook(DualPhoneController.getInstance().getActivePhone(), event) :
                        PhoneUtils.handleHeadsetHook(phone, event);
                if (VDBG) Log.d(LOG_TAG, "==> handleHeadsetHook(): consumed = " + consumed);
                if (consumed) {
                    abortBroadcast();
                }
            } else {
                if (getPhoneState() != PhoneConstants.State.IDLE) {
                    // If the phone is anything other than completely idle,
                    // then we consume and ignore any media key events,
                    // Otherwise it is too easy to accidentally start
                    // playing music while a phone call is in progress.
                    if (VDBG) Log.d(LOG_TAG, "MediaButtonBroadcastReceiver: consumed");
                    abortBroadcast();
                }
            }
        }
    }

    /**
     * Accepts broadcast Intents which will be prepared by {@link NotificationMgr} and thus
     * sent from framework's notification mechanism (which is outside Phone context).
     * This should be visible from outside, but shouldn't be in "exported" state.
     *
     * TODO: If possible merge this into PhoneAppBroadcastReceiver.
     */
    public static class NotificationBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            // TODO: use "if (VDBG)" here.
            Log.d(LOG_TAG, "Broadcast from Notification: " + action);

            if (action.equals(ACTION_HANG_UP_ONGOING_CALL)) {
                if (TelephonyConstants.IS_DSDS) {
                    PhoneUtils.hangup(DualPhoneController.getInstance().getActiveCM());
                } else {
                    PhoneUtils.hangup(PhoneGlobals.getInstance().mCM);
                }
            } else if (action.equals(ACTION_CALL_BACK_FROM_NOTIFICATION)) {
                // Collapse the expanded notification and the notification item itself.
                closeSystemDialogs(context);
                clearMissedCallNotification(context);

                Intent callIntent = new Intent(Intent.ACTION_CALL_PRIVILEGED, intent.getData());

                callIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                context.startActivity(callIntent);
            } else if (action.equals(ACTION_CALL_BACK_FROM_NOTIFICATION_SIM1) ||
                    action.equals(ACTION_CALL_BACK_FROM_NOTIFICATION_SIM2)) {
                // Collapse the expanded notification and the notification item itself.
                closeSystemDialogs(context);
                clearMissedCallNotification(context);

                Intent callIntent = new Intent(TelephonyConstants.ACTION_DUAL_SIM_CALL, intent.getData());
                callIntent.setClassName(context, "com.android.phone.OutgoingCallBroadcaster");

                int slot = TelephonyConstants.EXTRA_DCALL_SLOT_1;
                if (action.equals(ACTION_CALL_BACK_FROM_NOTIFICATION_SIM2)) {
                    slot = TelephonyConstants.EXTRA_DCALL_SLOT_2;
                }
                Log.d(LOG_TAG, "slot: "  + slot);
                callIntent.putExtra(TelephonyConstants.EXTRA_DSDS_CALL_POLICY, slot);
                callIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                context.startActivity(callIntent);
            } else if (action.equals(ACTION_SEND_SMS_FROM_NOTIFICATION)) {
                // Collapse the expanded notification and the notification item itself.
                closeSystemDialogs(context);
                clearMissedCallNotification(context);

                Intent smsIntent = new Intent(Intent.ACTION_SENDTO, intent.getData());
                smsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(smsIntent);
            } else if (TelephonyConstants.ACTION_RAT_SETTING.equals(intent.getAction())) {
                mRatSettingDone = true;
            } else {
                Log.w(LOG_TAG, "Received hang-up request from notification,"
                        + " but there's no call the system can hang up.");
            }
        }

        private void closeSystemDialogs(Context context) {
            Intent intent = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            context.sendBroadcastAsUser(intent, UserHandle.ALL);
        }

        private void clearMissedCallNotification(Context context) {
            Intent clearIntent = new Intent(context, ClearMissedCallsService.class);
            clearIntent.setAction(ClearMissedCallsService.ACTION_CLEAR_MISSED_CALLS);
            context.startService(clearIntent);
        }
    }

    private void handleServiceStateChanged(Intent intent, boolean primaryPhone) {
        /**
         * This used to handle updating EriTextWidgetProvider this routine
         * and and listening for ACTION_SERVICE_STATE_CHANGED intents could
         * be removed. But leaving just in case it might be needed in the near
         * future.
         */

        // If service just returned, start sending out the queued messages
        ServiceState ss = ServiceState.newFromBundle(intent.getExtras());

        if (ss != null) {
            int state = ss.getState();
            notificationMgr.updateNetworkSelection(state, primaryPhone);
        }
    }

    public boolean isOtaCallInActiveState() {
        boolean otaCallActive = false;
        if (VDBG) Log.d(LOG_TAG, "- isOtaCallInActiveState " + otaCallActive);
        return otaCallActive;
    }

    public boolean isOtaCallInEndState() {
        boolean otaCallEnded = false;
        if (VDBG) Log.d(LOG_TAG, "- isOtaCallInEndState " + otaCallEnded);
        return otaCallEnded;
    }

    // it is safe to call clearOtaState() even if the InCallScreen isn't active
    public void clearOtaState() {
        if (DBG) Log.d(LOG_TAG, "- clearOtaState ...");
        if (otaUtils != null) {
            otaUtils.cleanOtaScreen(true);
            if (DBG) Log.d(LOG_TAG, "  - clearOtaState clears OTA screen");
        }
    }

    // it is safe to call dismissOtaDialogs() even if the InCallScreen isn't active
    public void dismissOtaDialogs() {
        if (DBG) Log.d(LOG_TAG, "- dismissOtaDialogs ...");
        if (otaUtils != null) {
            otaUtils.dismissAllOtaDialogs();
            if (DBG) Log.d(LOG_TAG, "  - dismissOtaDialogs clears OTA dialogs");
        }
    }

    private void handleQueryTTYModeResponse(Message msg) {
        AsyncResult ar = (AsyncResult) msg.obj;
        if (ar.exception != null) {
            if (DBG) Log.d(LOG_TAG, "handleQueryTTYModeResponse: Error getting TTY state.");
        } else {
            if (DBG) Log.d(LOG_TAG,
                           "handleQueryTTYModeResponse: TTY enable state successfully queried.");

            int ttymode = ((int[]) ar.result)[0];
            if (DBG) Log.d(LOG_TAG, "handleQueryTTYModeResponse:ttymode=" + ttymode);

            Intent ttyModeChanged = new Intent(TtyIntent.TTY_ENABLED_CHANGE_ACTION);
            ttyModeChanged.putExtra("ttyEnabled", ttymode != Phone.TTY_MODE_OFF);
            sendBroadcastAsUser(ttyModeChanged, UserHandle.ALL);

            String audioTtyMode;
            switch (ttymode) {
            case Phone.TTY_MODE_FULL:
                audioTtyMode = "tty_full";
                break;
            case Phone.TTY_MODE_VCO:
                audioTtyMode = "tty_vco";
                break;
            case Phone.TTY_MODE_HCO:
                audioTtyMode = "tty_hco";
                break;
            case Phone.TTY_MODE_OFF:
            default:
                audioTtyMode = "tty_off";
                break;
            }
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            audioManager.setParameters("tty_mode="+audioTtyMode);
        }
    }

    private void handleSetTTYModeResponse(Message msg) {
        AsyncResult ar = (AsyncResult) msg.obj;

        if (ar.exception != null) {
            if (DBG) Log.d (LOG_TAG,
                    "handleSetTTYModeResponse: Error setting TTY mode, ar.exception"
                    + ar.exception);
        }
        phone.queryTTYMode(mHandler.obtainMessage(EVENT_TTY_MODE_GET));
    }

    /**
     * Handle a failure notification for a supplementary service
     * (i.e. conference, switch, separate, transfer, etc.).
     */
    private void onSuppServiceFailed(AsyncResult r) {
        Phone.SuppService service = (Phone.SuppService) r.result;
        Log.d(LOG_TAG, "onSuppServiceFailed: " + service);

        int errorMessageResId;
        switch (service) {
            case SWITCH:
                // Attempt to switch foreground and background/incoming calls failed
                // ("Failed to switch calls")
                errorMessageResId = R.string.incall_error_supp_service_switch;
                break;

            case SEPARATE:
                // Attempt to separate a call from a conference call
                // failed ("Failed to separate out call")
                errorMessageResId = R.string.incall_error_supp_service_separate;
                break;

            case TRANSFER:
                // Attempt to connect foreground and background calls to
                // each other (and hanging up user's line) failed ("Call
                // transfer failed")
                errorMessageResId = R.string.incall_error_supp_service_transfer;
                break;

            case CONFERENCE:
                // Attempt to add a call to conference call failed
                // ("Conference call failed")
                errorMessageResId = R.string.incall_error_supp_service_conference;
                break;

            case REJECT:
                // Attempt to reject an incoming call failed
                // ("Call rejection failed")
                errorMessageResId = R.string.incall_error_supp_service_reject;
                break;

            case HANGUP:
                // Attempt to release a call failed ("Failed to release call(s)")
                errorMessageResId = R.string.incall_error_supp_service_hangup;
                break;

            case UNKNOWN:
            default:
                // Attempt to use a service we don't recognize or support
                // ("Unsupported service" or "Selected service failed")
                errorMessageResId = R.string.incall_error_supp_service_unknown;
                break;
        }

        // mSuppServiceFailureDialog is a generic dialog used for any
        // supp service failure, and there's only ever have one
        // instance at a time.  So just in case a previous dialog is
        // still around, dismiss it.
        if (mSuppServiceFailureDialog != null) {
            Log.v(LOG_TAG, "- DISMISSING mSuppServiceFailureDialog.");
            mSuppServiceFailureDialog.dismiss();  // It's safe to dismiss() a dialog
                                                  // that's already dismissed.
            mSuppServiceFailureDialog = null;
        }

        mSuppServiceFailureDialog = new AlertDialog.Builder(this)
                .setMessage(errorMessageResId)
                .setPositiveButton(R.string.ok, null)
                .create();
        mSuppServiceFailureDialog.getWindow().setType(
                WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);
        mSuppServiceFailureDialog.getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        mSuppServiceFailureDialog.show();
    }


    /**
     * "Call origin" may be used by Contacts app to specify where the phone call comes from.
     * Currently, the only permitted value for this extra is {@link #ALLOWED_EXTRA_CALL_ORIGIN}.
     * Any other value will be ignored, to make sure that malicious apps can't trick the in-call
     * UI into launching some random other app after a call ends.
     *
     * TODO: make this more generic. Note that we should let the "origin" specify its package
     * while we are now assuming it is "com.android.contacts"
     */
    public static final String EXTRA_CALL_ORIGIN = "com.android.phone.CALL_ORIGIN";
    private static final String DEFAULT_CALL_ORIGIN_PACKAGE = "com.android.dialer";
    private static final String ALLOWED_EXTRA_CALL_ORIGIN =
            "com.android.dialer.DialtactsActivity";
    /**
     * Used to determine if the preserved call origin is fresh enough.
     */
    private static final long CALL_ORIGIN_EXPIRATION_MILLIS = 30 * 1000;
    Phone getPhoneBySlot(int slot) {
        if (TelephonyConstants.IS_DSDS) {
            if (DualPhoneController.isPrimaryOnSim1()) {
                return slot == 0 ? phone : phone2;
            }
            return slot == 0 ? phone2 : phone;
        }
        return phone;
    }

    /** Service connection */
    private final ServiceConnection mBluetoothPhoneConnection = new ServiceConnection() {

        /** Handle the task of binding the local object to the service */
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.i(LOG_TAG, "Headset phone created, binding local service.");
            mBluetoothPhone = IBluetoothHeadsetPhone.Stub.asInterface(service);
        }

        /** Handle the task of cleaning up the local binding */
        public void onServiceDisconnected(ComponentName className) {
            Log.i(LOG_TAG, "Headset phone disconnected, cleaning local binding.");
            mBluetoothPhone = null;
        }
    };

    static private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }

    void setActiveSimId(int simId) {
        if (callHandlerServiceProxy != null)
            callHandlerServiceProxy.setActiveSimId(simId);
    }
}
