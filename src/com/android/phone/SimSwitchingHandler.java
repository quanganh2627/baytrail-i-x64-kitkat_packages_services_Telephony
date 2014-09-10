/*
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

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.IPowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.Looper;
import android.provider.Settings;
import android.provider.Settings.System;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyIntents2;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.DsdsDataSimManager;
import com.android.phone.PhoneApp;

import static com.android.internal.telephony.RILConstants.NETWORK_MODE_GSM_ONLY;
import static com.android.internal.telephony.RILConstants.NETWORK_MODE_WCDMA_PREF;
import static com.android.internal.telephony.OemHookConstants.SWAP_PS_FLAG_RESET_RADIO_STATE;
import static com.android.internal.telephony.RILConstants.SWAP_PS_SWAP_ENABLE;
import static com.android.internal.telephony.TelephonyConstants.ACTION_DATA_SIM_SWITCH;
import static com.android.internal.telephony.TelephonyConstants.DSDS_SLOT_1_ID;
import static com.android.internal.telephony.TelephonyConstants.DSDS_SLOT_2_ID;
import static com.android.internal.telephony.TelephonyConstants.ENABLED;
import static com.android.internal.telephony.TelephonyConstants.EXTRA_RESULT_CODE;
import static com.android.internal.telephony.TelephonyConstants.EXTRA_SWITCH_STAGE;
import static com.android.internal.telephony.TelephonyConstants.INTENT_SIM_ONOFF_RESULT;
import static com.android.internal.telephony.TelephonyConstants.PROP_SIM_BUSY;
import static com.android.internal.telephony.TelephonyConstants.SIM_ACTIVITY_IDLE;
import static com.android.internal.telephony.TelephonyConstants.SIM_ACTIVITY_ONOFF;
import static com.android.internal.telephony.TelephonyConstants.SIM_ACTIVITY_PRIMARY;
import static com.android.internal.telephony.TelephonyConstants.SIM_SWITCH_BEGIN;
import static com.android.internal.telephony.TelephonyConstants.SIM_SWITCH_END;
import static com.android.internal.telephony.TelephonyConstants.SIM_SWITCH_DONE;
import static com.android.internal.telephony.TelephonyConstants.EXTRA_SLOT;
import static com.android.internal.telephony.TelephonyConstants.SWITCH_FAILED_GENERIC;
import static com.android.internal.telephony.TelephonyConstants.SWITCH_FAILED_PHONE_BUSY;
import static com.android.internal.telephony.TelephonyConstants.SWITCH_FAILED_RADIO_OFF;
import static com.android.internal.telephony.TelephonyConstants.SWITCH_FAILED_SWITCHING_ON_GOING;
import static com.android.internal.telephony.TelephonyConstants.SWITCH_FAILED_TIMEDOUT;
import static com.android.internal.telephony.TelephonyConstants.SWITCH_SUCCESS;


public class SimSwitchingHandler{

    private static final String TAG = "SimSwitchingHandler";
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    private static final int EVENT_RESTART_PHONE_APP         = 1;
    private static final int EVENT_POLL_RADIO_STATES         = 2;
    private static final int EVENT_RAT_SWAP_DONE             = 3;
    private static final int EVENT_PS_SWAP_1_DONE            = 4;
    private static final int EVENT_PS_SWAP_2_DONE            = 5;

    private static final int EVENT_REBOOT_FORCE_PRIMARY_SIM_2G_DONE = 6;
    private static final int EVENT_REBOOT_DEVICE                    = 7;
    private static final int EVENT_CHANGE_PRIMARY_SIM               = 8;
    private static final int EVENT_RESUME_USB_TETHER                = 9;

    private static final int EVENT_POLL_SWITCH_DONE      = 11;
    private static final int POLL_SWITCH_MILLIS =  1000;
    private static final int POLL_SWITCH_RETRY_MAX = 60*5;

    private static final int EVENT_SIM_ENABLE_TIMEDOUT  = 21;
    private static final int SWITCHING_TIMEDOUT_MILLIS =  1000 * 1;

    // the to-be primary sim id
    private int mNewSimId = -1;
    private int mRadioOffs = 0;
    private int mPollRetries = 0;
    private int mSwapState = 0;
    private int mDynamicDataSimPolicy;
    private volatile Looper mServiceLooper;
    static private SimSwitchingHandler sMe;
    private Context mContext;
    private boolean mToDoTether = false;
    private static SimEnabler sSimEnabler = null;

    private SimSwitchingHandler() {
        TelephonyManager tm = (TelephonyManager)PhoneGlobals.getInstance()
                .getSystemService(Context.TELEPHONY_SERVICE);
        mDynamicDataSimPolicy = tm.getDynamicDataSimPolicy();
        if (DBG) Log.d(TAG, "mDynamicDataSimPolicy: " + mDynamicDataSimPolicy);
        mContext = PhoneGlobals.getInstance().getApplicationContext();
        IntentFilter intentFilter =
                new IntentFilter(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED);
        mContext.registerReceiver(mBroadcastReceiver, intentFilter);
        intentFilter =
            new IntentFilter(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        intentFilter.addAction(TelephonyIntents2.ACTION_SIM_STATE_CHANGED);
    }

    public void dispose() {
        mContext.unregisterReceiver(mBroadcastReceiver);
        if (sSimEnabler != null) {
            sSimEnabler.dispose();
        }
    }

    public static final SimSwitchingHandler getInstance() {
        if (sMe == null) {
            sMe = new SimSwitchingHandler();
        }
        return sMe;
    }

    private Handler mServiceHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_RESTART_PHONE_APP:
                    if (DBG) Log.w(TAG, "To restart phone");
                    Process.killProcess(Process.myPid());
                    break;

                case EVENT_POLL_RADIO_STATES:
                    handlePollRadioStates(msg);
                    break;

                case EVENT_RAT_SWAP_DONE:
                    handleRatSwapDone(msg);
                    break;

                case EVENT_PS_SWAP_1_DONE:
                    if (DBG) Log.w(TAG, "swap sim 1 done");
                    mSwapState |= 1;
                    if (mSwapState == 3)
                        handleAllPSswapDone(msg);
                    break;

                case EVENT_PS_SWAP_2_DONE:
                    if (DBG) Log.w(TAG, "swap sim 2 done");
                    mSwapState |= 2;
                    if (mSwapState == 3)
                        handleAllPSswapDone(msg);
                    break;

                case EVENT_REBOOT_FORCE_PRIMARY_SIM_2G_DONE:
                    handleRebootForcePrimarySim2gDone(msg);
                    break;

                case EVENT_REBOOT_DEVICE:
                    IPowerManager pm = IPowerManager.Stub.asInterface(
                            ServiceManager.getService("power"));
                    try {
                        pm.reboot(false, null, true);
                    } catch (RemoteException e) {
                        if (DBG) log("pm.reboot() failed: " + e);
                    }
                    break;

                case EVENT_CHANGE_PRIMARY_SIM:
                    changePrimarySim();
                    break;

                case EVENT_RESUME_USB_TETHER:
                    handleResumeUsbTether();
                    break;

                case EVENT_SIM_ENABLE_TIMEDOUT:
                    log("EVENT_SIM_ENABLE_TIMEDOUT, forceFinish");
                    if (sSimEnabler != null) {
                        sSimEnabler.notifyTaskEnd(SWITCH_FAILED_TIMEDOUT);
                    }
                    break;
            }
        }
    };
    private static final String  DATA_SIM_SERVICE = "dsdsdatasim";

    private void setRadiosOff() {
       broadcastSwitchStage("setting radios off ", 0);
       mPollRetries = 0;
        // optimize to see if power is already off
        ((PhoneProxy)PhoneGlobals.getInstance().phone).setRadioPower(false);
        ((PhoneProxy)PhoneGlobals.getInstance().phone2).setRadioPower(false);
        mServiceHandler.sendMessageDelayed(mServiceHandler.obtainMessage(EVENT_POLL_RADIO_STATES), POLL_SWITCH_MILLIS);
    }

    private void handlePollRadioStates(Message msg) {
        if (checkRadiosOff()) {
        //if (isDataDeactivated()) {
            log("RADIO_OFF_TIMER:" + mPollRetries);
            mPollRetries = 0;
            onRadiosOffSafely(SWITCH_SUCCESS, (Message)msg.obj);
        } else if (mPollRetries++ < POLL_SWITCH_RETRY_MAX) {
            log("EVENT_POLL_RADIO_STATES:" + mPollRetries);
            mServiceHandler.sendMessageDelayed(mServiceHandler.obtainMessage(EVENT_POLL_RADIO_STATES, msg.obj), POLL_SWITCH_MILLIS);
        } else {
            //broadcastSwitchStage(SIM_SWITCH_END, SWITCH_FAILED_TIMEDOUT);
            onRadiosOffSafely(SWITCH_FAILED_TIMEDOUT, (Message)msg.obj);
            //to force switch RIL after timeout
            //onRadiosOffSafely(SWITCH_SUCCESS, (Message)msg.obj);
            mPollRetries = 0;
            rollbackSwitch();
        }
    }

    void rollbackSwitch() {
        //Firstly, make sure the Desired Power state is restored
        ((PhoneProxy)PhoneGlobals.getInstance().phone).restoreRadioPower();
        ((PhoneProxy)PhoneGlobals.getInstance().phone2).restoreRadioPower();
    }

    private void onRadiosOffSafely(int result, Message msg) {
        log("onRadiosOffSafely,result:" + result);
        mRadioOffs = 0;
        if (SWITCH_SUCCESS == result) {
            applySettings();
        }
        int[] results = new int[1];
        results[0] = result;
        AsyncResult.forMessage(msg, results, null);
        msg.sendToTarget();
    }

    private boolean isDataDeactivated() {
        return PhoneFactory.getDsdsDataSimManager().isDataDisconnected();
    }

    private boolean checkRadiosOff() {
        int state;
        if ((state = PhoneGlobals.getInstance().phone.getServiceState().getState())
                == ServiceState.STATE_POWER_OFF)
            mRadioOffs |= 1;
        if (DBG) Log.w(TAG, "Sim 1 state: " + state);

        if ((state = PhoneGlobals.getInstance().phone2.getServiceState().getState())
                == ServiceState.STATE_POWER_OFF)
            mRadioOffs |= 2;
        if (DBG) Log.w(TAG, "Sim 2 state: " + state);
        return mRadioOffs == 3;
   }

    private void handleRatSettings() {
        int gsm3GSelection = Settings.Global.getInt(
                PhoneGlobals.getInstance().getContentResolver(),
                Settings.Global.GSM_3G_SELECTION_MODE, ENABLED);
        if (DBG) Log.w(TAG, "rat swapping: " + gsm3GSelection);
        if (gsm3GSelection == ENABLED) {
            broadcastSwitchStage("handling rat swap", 0);
            Message msg = mServiceHandler.obtainMessage(EVENT_RAT_SWAP_DONE);
            OnlyOne3gRatSwitcher switcher = new OnlyOne3gRatSwitcher(getPrimaryId() == 0 ? 1 : 0, msg);
            switcher.startSwitch(false);
        } else {
            // static mode doesn't need rat change
            swapProtocolStack();
        }
    }

    private void handleRatSwapDone(Message msg) {
        AsyncResult ar = (AsyncResult) msg.obj;
        if (ar.exception != null) {
            broadcastSwitchStage(SIM_SWITCH_END, SWITCH_FAILED_GENERIC);
            if (DBG) log("Error: rat swap exception: " + ar.exception);
        } else {
            swapProtocolStack();
        }
    }

    private void swapProtocolStack() {
        broadcastSwitchStage("swapping protocol stack", 0);
        mSwapState = 0;
        ((PhoneProxy)PhoneGlobals.getInstance().phone)
                .requestProtocolStackSwap(mServiceHandler.obtainMessage(EVENT_PS_SWAP_1_DONE), SWAP_PS_FLAG_RESET_RADIO_STATE);
        ((PhoneProxy)PhoneGlobals.getInstance().phone2)
                .requestProtocolStackSwap(mServiceHandler.obtainMessage(EVENT_PS_SWAP_2_DONE), SWAP_PS_FLAG_RESET_RADIO_STATE);
    }

    private void handleAllPSswapDone(Message msg) {
        AsyncResult ar = (AsyncResult) msg.obj;
        if (ar.exception != null) {
            broadcastSwitchStage(SIM_SWITCH_END, SWITCH_FAILED_GENERIC);
            if (DBG) log("Error: ps swap with exception: " + ar.exception);
        } else {
            if (DBG) log("new sim id: " + mNewSimId);
            mRadioOffs = 0;
            setRadiosOff();
        }
    }

    private void handleRebootForcePrimarySim2gDone(Message msg) {
        AsyncResult ar = (AsyncResult) msg.obj;
        if (ar.exception != null) {
            broadcastSwitchStage(SIM_SWITCH_END, SWITCH_FAILED_GENERIC);
            if (DBG) log("Error: force 2g failed with exception: " + ar.exception);
        } else {
            applySettings();
            broadcastSwitchStage(SIM_SWITCH_END, SWITCH_SUCCESS);
            // give time to setting
            mServiceHandler.sendMessageDelayed(mServiceHandler.obtainMessage(EVENT_REBOOT_DEVICE), 1000);
        }
    }

    private int getPrimaryId() {
        int curId = Settings.Global.getInt(
                PhoneGlobals.getInstance().getContentResolver(),
                Settings.Global.MOBILE_DATA_SIM,
                ConnectivityManager.MOBILE_DATA_NETWORK_SLOT_A);
        return curId;
    }

    private boolean isSimIdSwitchable(int simId) {
        return simId == (1 - getPrimaryId());
    }

    public int setPrimarySim(int simId) {
        if (isInSwitchingPrimary()) {
            return SWITCH_FAILED_SWITCHING_ON_GOING;
        }
        int ret = 0;
        if (!isSimIdSwitchable(simId)) {
            ret = SWITCH_FAILED_GENERIC;
            onSwitchingFailed(ret);
            log("invalidSimId:" + simId);
            return ret;
        }
        int result = trySwitchingPrimary(simId);
        log("result from trySwitchingPrimary:" + result);
        return result;
    }

    public int enableSim(int slot, boolean enabling) {
       int ret = SWITCH_SUCCESS;
        if (isInSimSwitching() || isPhoneBusy())  {
            log("cannot switch,isInSimSwitching:" + isInSimSwitching() + ",isPhoneBusy:" + isPhoneBusy());
            //onEnabSimFailed(R.string.sim_switching_failed_phone_busy);
            ret = SWITCH_FAILED_PHONE_BUSY;
            notifyOnOffResult(slot, ret);
        } else {
            ret = getSimEnabler().doSimSwitching(slot, enabling);
        }
        return ret;
    }

    static boolean isInSimSwitching() {
        boolean ret = TelephonyManager.getDefault().isSimBusy();
        log("isInSimSwitching:" + ret);
        return ret;
    }

    static boolean isInSwitchingPrimary() {
        boolean ret = (TelephonyManager.getDefault().getSimActivity() == SIM_ACTIVITY_PRIMARY);
        log("isInSwitchingPrimary:" + ret);
        return ret;
    }

    private boolean isPhoneInCall(int slot) {
        TelephonyManager tm = TelephonyManager.getTmBySlot(slot);
        if (tm != null && tm.getCallState() != TelephonyManager.CALL_STATE_IDLE) {
            return true;
        }
        return false;
    }

    SimEnabler getSimEnabler() {
        if (sSimEnabler == null) {
            sSimEnabler = new SimEnabler();
        }
        return sSimEnabler;
    }

    final class SimEnabler {
        private int mSlot = -1;
        private boolean mEnabling = true;
        private boolean mTaskPending = false;
        private final Object mLock = new Object();
        private int mSimEnableResult = 0;

        SimEnabler() {
            mSlot = -1;
            mTaskPending = false;
            IntentFilter intentFilter =
                    new IntentFilter(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
            intentFilter.addAction(TelephonyIntents2.ACTION_SIM_STATE_CHANGED);

            mContext.registerReceiver(mSimStateReceiver, intentFilter);
        }

        public void dispose() {
            mContext.unregisterReceiver(mSimStateReceiver);
        }

        void doEnableSim(int slot, boolean enabling) {
            mSlot = slot;
            mEnabling = enabling;
            Settings.Global.putInt(
                    PhoneGlobals.getInstance().getContentResolver(),
                    slot == DSDS_SLOT_1_ID ? Settings.Global.DUAL_SLOT_1_ENABLED
                    : Settings.Global.DUAL_SLOT_2_ENABLED,
                    enabling ? 1 : 0);
            mServiceHandler.sendMessageDelayed(Message.obtain(mServiceHandler, EVENT_SIM_ENABLE_TIMEDOUT), SWITCHING_TIMEDOUT_MILLIS);
            log("doEnableSim");
            ((PhoneProxy)PhoneGlobals.getInstance().getPhoneBySlot(slot)).enableSim(enabling);
            mTaskPending = true;
            setInSwitchingOnOff(true);
        }

        void notifyTaskEnd(int result) {
            log("notifyTaskEnd");
            mSimEnableResult = result;
            synchronized(mLock) {
                mLock.notifyAll();
            }
        }

        private boolean isSimStateAsExpected(int state) {
            return mEnabling == (state != TelephonyManager.SIM_STATE_ABSENT);
        }

        final BroadcastReceiver mSimStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (VDBG) log("intent: " + intent);
                if (TelephonyIntents.ACTION_SIM_STATE_CHANGED.equals(intent.getAction()) ||
                        TelephonyIntents2.ACTION_SIM_STATE_CHANGED.equals(intent.getAction())) {
                    final int slot = intent.getIntExtra(EXTRA_SLOT, 0);
                    if (mSlot != slot) {
                        log("not my state,slot," + slot);
                        return;
                    }
                    final int simState = TelephonyManager.getTmBySlot(slot).getSimState();
                    if (isSimStateAsExpected(simState) && mTaskPending) {
                        log("Great, state as expected:" + simState);
                        mServiceHandler.removeMessages(EVENT_SIM_ENABLE_TIMEDOUT);
                        notifyTaskEnd(SWITCH_SUCCESS);
                    } else {
                        log("OOO, SIM state not as expected:" + simState);
                    }
                }
            }
        };

        private int doSimSwitching(int simId, boolean enabling) {
            if (DBG) log("doSimSwitching:" + simId + ",on:" + enabling);
            mSimEnableResult = SWITCH_SUCCESS;
            doEnableSim(simId, enabling);
            int ret = waitingForSwitchingEnd();
            log("return from enableSim:" + ret);
            onTaskEnd();
            return ret;
        }

        private int waitingForSwitchingEnd() {
            log("waitingForSwitchingEnd");
            synchronized(mLock) {
                try {
                    mLock.wait();
                } catch (InterruptedException e) {
                    log("interrupted while trying to enableSim");
                }
            }
            return mSimEnableResult;
        }

        private void onTaskEnd() {
            if (mTaskPending) {
                log("onEnableSimEnd:" + mSimEnableResult);
                mTaskPending = false;
                setInSwitchingOnOff(false);
                notifyOnOffResult(mSlot, mSimEnableResult);
                mSlot = -1;
                DualPhoneController.broadcastSimWidgetUpdateIntent();
            }
        }

        private void setInSwitchingOnOff(boolean switching) {
            TelephonyManager tm = TelephonyManager.getTmBySlot(mSlot);
            if (switching) {
                tm.requestSimActivity(SIM_ACTIVITY_ONOFF);
            } else {
                tm.stopSimActivity(SIM_ACTIVITY_ONOFF);
            }
        }
    };

    private int trySwitchingPrimary(int simId) {
       int ret = 0;
        if (isPhoneBusy()) {
            log("cannot switch due to phone busy");
            ret = SWITCH_FAILED_PHONE_BUSY;
        }
        if (isAirplaneModeOn()) {
            log("cannot switch due to airplanemode");
            ret = SWITCH_FAILED_RADIO_OFF;
        }
        if (isInSimSwitching()) {
            if (DBG) Log.w(TAG, "Switching is in progress. This request is aborted.");
            ret = SWITCH_FAILED_GENERIC;
        }
        if (ret != 0) {
            onSwitchingFailed(ret);
            return ret;
        }
        mNewSimId = simId;
        setInSwitchingPrimary(true);
        return setPrimarySimDynamically(mNewSimId);
    }

    void onSwitchingFailed(int result) {
        broadcastSwitchStage(SIM_SWITCH_END, result);
    }

    private boolean isPhoneBusy() {
        return (isPhoneInCall(0) || isPhoneInCall(1));
    }

    private boolean isAirplaneModeOn() {
        return (System.getInt(mContext.getContentResolver(),
                    System.AIRPLANE_MODE_ON, 0) > 0);
    }

    private void applySettings() {
        PhoneGlobals app = PhoneGlobals.getInstance();
        Settings.Global.putInt(
                app.getContentResolver(),
                Settings.Global.MOBILE_DATA_SIM,
                mNewSimId);
        DualPhoneController.getInstance().updatePrimarySim();
        int gsm3GSelection = Settings.Global.getInt(PhoneGlobals.getInstance().getContentResolver(),
                Settings.Global.GSM_3G_SELECTION_MODE, ENABLED);
        if (gsm3GSelection != ENABLED) {
            if (DBG) log("No need to update rat setting for manual mode");
            return;






        // Use setDataSim on ConnectivityManager to change data sim id


        // Update Preferred Network Mode
            }

            if (mNewSimId == DSDS_SLOT_1_ID) {
                Settings.Global.putInt(app.getContentResolver(),
                        Settings.Global.PREFERRED_NETWORK_MODE, NETWORK_MODE_WCDMA_PREF);
                Settings.Global.putInt(app.getContentResolver(),
                        Settings.Global.PREFERRED_NETWORK2_MODE, NETWORK_MODE_GSM_ONLY);
            } else {
                Settings.Global.putInt(app.getContentResolver(),
                        Settings.Global.PREFERRED_NETWORK_MODE, NETWORK_MODE_GSM_ONLY);
                Settings.Global.putInt(app.getContentResolver(),
                        Settings.Global.PREFERRED_NETWORK2_MODE, NETWORK_MODE_WCDMA_PREF);
            }
        }

    private void broadcastSwitchStage(String stage, int result) {
        if (DBG) log("broadcast : " + stage + " result : " + result);
        if (SIM_SWITCH_END.equals(stage)) {
            mNewSimId = -1;
            setInSwitchingPrimary(false);
        }
        Intent intent = new Intent(ACTION_DATA_SIM_SWITCH);
        intent.putExtra(EXTRA_SWITCH_STAGE, stage);
        intent.putExtra(EXTRA_RESULT_CODE, result);
        PhoneGlobals.getInstance().getApplicationContext().sendBroadcast(intent);
    }

    static void notifyOnOffResult(int slot, int result) {
        if (DBG) log("notifyOnOffResult, slot" + slot + ",result : " + result);
        Intent intent = new Intent(INTENT_SIM_ONOFF_RESULT);
        intent.putExtra(EXTRA_SLOT, slot);
        intent.putExtra(EXTRA_RESULT_CODE, result);
        PhoneGlobals.getInstance().getApplicationContext().sendBroadcast(intent);
    }
    int setPrimarySimDynamically(int simId) {
        unTetherUsb();
        int result = -1;
        final DataSimSwitcher switcher = new DataSimSwitcher();
        switcher.start();
        result = switcher.setPrimarySimStagely(mNewSimId);
        log("return from setPrimarySimStagely:" + result);
        broadcastSwitchStage(SIM_SWITCH_END, result);
        return result;
    }

    private void changePrimarySim() {
        int result = -1;
        final DataSimSwitcher switcher = new DataSimSwitcher();
        switcher.start();
        result = switcher.setPrimarySimStagely(mNewSimId);
        log("return from setPrimarySimStagely:" + result);
        broadcastSwitchStage(SIM_SWITCH_END, result);
    }

    private class DataSimSwitcher extends Thread {

        private final DsdsDataSimManager mDataSimManager;

        private boolean mDone = false;
        private int mResult = -1;

        // For replies from SimCard interface
        private Handler mHandler;

        // For async handler to identify request type
        private static final int STAGE_SWITCH_START = 1;
        private static final int STAGE_SWITCH_RADIO_OFF = 2;
        private static final int STAGE_SWITCH_DONE = 3;
        private static final int EVENT_TURN_OFF_RADIO_DONE  = 100;
        private static final int EVENT_DATA_SIM_SWITCH_DONE = 101;
        private int mSwitchStage;

        public DataSimSwitcher() {
            mDataSimManager = PhoneFactory.getDsdsDataSimManager();
        }

        @Override
        public void run() {
            Looper.prepare();
            synchronized (DataSimSwitcher.this) {
                mHandler = new Handler() {
                    @Override
                    public void handleMessage(Message msg) {
                        AsyncResult ar = (AsyncResult) msg.obj;
                        switch (msg.what) {
                            case EVENT_DATA_SIM_SWITCH_DONE:
                                Log.d(TAG, "EVENT_DATA_SIM_SWITCH_DONE");
                                synchronized (DataSimSwitcher.this) {
                                    //Do no present the failed result to the end user, becase we cannot revert
                                    mResult = SWITCH_SUCCESS;
                                    Log.d(TAG, "stage:" + mSwitchStage +",ret :" + mResult);
                                    mDone = true;
                                    DataSimSwitcher.this.notifyAll();
                                }
                                PhoneFactory.updateDataSimProperty(mNewSimId);
                                break;
                            case EVENT_TURN_OFF_RADIO_DONE:
                                Log.d(TAG, "EVENT_TURN_OFF_RADIO_DONE");
                                synchronized (DataSimSwitcher.this) {
                                    mResult = ((int[])(ar.result))[0];
                                    Log.d(TAG, "ret:" + mResult);
                                    mDone = true;
                                    DataSimSwitcher.this.notifyAll();
                                }
                                break;
                        }
                    }
                };
                DataSimSwitcher.this.notifyAll();
            }
            Looper.loop();
        }

        synchronized int setPrimarySimStagely(int simId ) {
            mSwitchStage = STAGE_SWITCH_START;
            while (mSwitchStage < STAGE_SWITCH_DONE) {
                if (doSwitchSync() != SWITCH_SUCCESS) {
                    break;
                }
                nextStage();
            }
            return mResult;
        }

        void nextStage( ) {
            if (mResult != SWITCH_SUCCESS) {
                mSwitchStage = STAGE_SWITCH_DONE;
                return;
            }
            mSwitchStage++;
        }

        private void doStageRadiosOff(Message callback) {
            setRadiosOff(callback);
        }

        private void doStageSwitchRil(Message callback) {
            broadcastSwitchStage("switching RIL", 0);
            mDataSimManager.setPrimarySim(mNewSimId, callback);
            PhoneGlobals.getInstance().resetDualSimState();
        }

        synchronized int doSwitchSync() {
            while (mHandler == null) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            mResult = -1;
            switch (mSwitchStage) {
                case STAGE_SWITCH_START:
                    doStageRadiosOff(Message.obtain(mHandler, EVENT_TURN_OFF_RADIO_DONE));
                    break;

                case STAGE_SWITCH_RADIO_OFF:
                    doStageSwitchRil(Message.obtain(mHandler, EVENT_DATA_SIM_SWITCH_DONE));
                    break;
            }
            while (!mDone) {
                try {
                    Log.d(TAG, "wait for done");
                    wait();
                } catch (InterruptedException e) {
                    // Restore the interrupted status
                    Thread.currentThread().interrupt();
                }
            }
            Log.d(TAG, "done, result:" + mResult);
            mDone = false;
            return mResult;
        }
    }

    private void setRadiosOff(Message msg) {
        broadcastSwitchStage("setting radios off ", 0);
        mPollRetries = 0;
        // optimize to see if power is already off
        ((PhoneProxy)PhoneGlobals.getInstance().phone).setRadioPower(false);
        ((PhoneProxy)PhoneGlobals.getInstance().phone2).setRadioPower(false);
        mServiceHandler.sendMessageDelayed(mServiceHandler.obtainMessage(EVENT_POLL_RADIO_STATES, msg), POLL_SWITCH_MILLIS);
    }

    void setInSwitchingPrimary(boolean switching) {
        TelephonyManager tm = TelephonyManager.getDefault();
        int ret = -1;
        if (switching) {
            ret = tm.requestSimActivity(SIM_ACTIVITY_PRIMARY);
        } else {
            ret = tm.stopSimActivity(SIM_ACTIVITY_PRIMARY);
        }
    }

    boolean isTethered() {
         ConnectivityManager cm =
                 (ConnectivityManager)mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
         String[] usbRegexs = cm.getTetherableUsbRegexs();
         String[] tethered = cm.getTetheredIfaces();
         boolean usbTethered = false;
         for (String s : tethered) {
             for (String regex : usbRegexs) {
                 if (s.matches(regex)) usbTethered = true;
             }
         }
         if (DBG) Log.d(TAG, "usb tethered: " + usbTethered);
         return usbTethered;
     }

     private void unTetherUsb() {
         mToDoTether = isTethered();
         if (DBG) Log.d(TAG, "untether: " + mToDoTether);
         if (mToDoTether) {
             ConnectivityManager cm =
                     (ConnectivityManager)mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
             cm.setUsbTethering(false);
         }
     }

     void handleResumeUsbTether() {
         if (!mToDoTether) {
             return;
         }
         if (DBG) Log.d(TAG, "resume usb tether: " + mToDoTether);
         mToDoTether = false;
         ConnectivityManager cm =
                 (ConnectivityManager)mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
         cm.setUsbTethering(true);
     }

     final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (VDBG) log("intent: " + intent);
            if (TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED.equals(intent.getAction())) {
                if ("CONNECTED".equals(intent.getStringExtra(PhoneConstants.STATE_KEY)) ) {
                    mServiceHandler.sendMessage(mServiceHandler.obtainMessage(EVENT_RESUME_USB_TETHER));
                }
            }
        }
    };


    private static void log(String msg) {
        Log.d(TAG, msg);
    }
}
