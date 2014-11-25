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
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.DsdsDataSimManager;
import com.android.phone.PhoneApp;

import static com.android.internal.telephony.RILConstants.NETWORK_MODE_GSM_ONLY;
import static com.android.internal.telephony.RILConstants.NETWORK_MODE_WCDMA_PREF;
import static com.android.internal.telephony.RILConstants.NETWORK_MODE_WCDMA_ONLY;
import static com.android.internal.telephony.RILConstants.SWAP_PS_RESET_RADIO_STATE;
import static com.android.internal.telephony.TelephonyConstants.ACTION_DATA_SIM_SWITCH;
import static com.android.internal.telephony.TelephonyConstants.DSDS_SLOT_1_ID;
import static com.android.internal.telephony.TelephonyConstants.DSDS_SLOT_2_ID;
import static com.android.internal.telephony.TelephonyConstants.ENABLED;
import static com.android.internal.telephony.TelephonyConstants.EXTRA_SWITCH_STAGE;
import static com.android.internal.telephony.TelephonyConstants.EXTRA_RESULT_CODE;
import static com.android.internal.telephony.TelephonyConstants.SIM_SWITCH_BEGIN;
import static com.android.internal.telephony.TelephonyConstants.SIM_SWITCH_END;
import static com.android.internal.telephony.TelephonyConstants.SWITCH_FAILED_TIMEDOUT;

public class SimWidgetService extends Service implements Runnable{

    private static final String TAG = "SimWidgetService";
    private static final boolean DBG = true;

    private static final int EVENT_RESTART_PHONE_APP         = 1;
    private static final int EVENT_POLL_RADIO_STATES         = 2;
    private static final int EVENT_RAT_SWAP_DONE             = 3;
    private static final int EVENT_PS_SWAP_1_DONE            = 4;
    private static final int EVENT_PS_SWAP_2_DONE            = 5;

    private static final int EVENT_REBOOT_FORCE_PRIMARY_SIM_2G_DONE = 6;
    private static final int EVENT_REBOOT_DEVICE                    = 7;
    private static final int EVENT_CHANGE_PRIMARY_SIM               = 8;

    private static final int EVENT_POLL_SWITCH_DONE      = 11;
    private static final int POLL_SWITCH_MILLIS =  1000;
    private static final int POLL_SWITCH_RETRY_MAX = 10;


    // return code for sim switch
    private static final int SWITCH_SUCCESS           = 1;
    private static final int SWITCH_NO_CHANGE         = 2;
    private static final int SWITCH_ERROR_RAT_CHANGE  = 3;
    private static final int SWITCH_ERROR_NOT_ALLOWED = 4;
    private static final int SWITCH_ERROR_GENERIC     = 5;

    // the to-be primary sim id
    private int mNewSimId = -1;
    private int mRadioOffs = 0;
    private int mPollRetries = 0;
    private int mSwapState = 0;
    private boolean mDisconnected = false;
    private int mDynamicDataSimPolicy;
    private volatile Looper mServiceLooper;
    private volatile ServiceHandler mServiceHandler;

    public void onCreate() {
        super.onCreate();
        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        mDynamicDataSimPolicy = tm.getDynamicDataSimPolicy();
        if (DBG) Log.d(TAG, "mDynamicDataSimPolicy: " + mDynamicDataSimPolicy);
        Thread serviceThread = new Thread(null, this, "SimWidget Service");
        serviceThread.start();
    }

    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void run() {
        Looper.prepare();

        mServiceLooper = Looper.myLooper();
        mServiceHandler = new ServiceHandler();

        Looper.loop();
    }
    @Override
    public void onDestroy() {
        waitForLooper();
        mServiceLooper.quit();
    }

    private void waitForLooper() {
        while (mServiceHandler == null) {
            synchronized (this) {
                try {
                    wait(100);
                } catch (InterruptedException e) {
                }
            }
        }
    }



    //Handler mHandler = new Handler() {
    private final class ServiceHandler extends Handler {
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
                        pm.reboot(false, null, false);
                    } catch (RemoteException e) {
                        if (DBG) log("pm.reboot() failed: " + e);
                    }
                    break;

                case EVENT_CHANGE_PRIMARY_SIM:
                    changePrimarySim();
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
        //if (checkRadiosOff()) {
        if (isDataDeactivated()) {
            mPollRetries = 0;
            onRadiosOffSafely(SWITCH_SUCCESS, (Message)msg.obj);
        } else if (mPollRetries++ < POLL_SWITCH_RETRY_MAX) {
            log("EVENT_POLL_RADIO_STATES:" + mPollRetries);
            mServiceHandler.sendMessageDelayed(mServiceHandler.obtainMessage(EVENT_POLL_RADIO_STATES, msg.obj), POLL_SWITCH_MILLIS);
            //mServiceHandler.sendMessageDelayed(mServiceHandler.obtainMessage(EVENT_POLL_RADIO_STATES), POLL_SWITCH_MILLIS);
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
        try {
            Thread.sleep(1000*20);
        }catch(Exception e) {
        }
        mRadioOffs = 0;
        if (SWITCH_SUCCESS == result) {
            applySettings();
        }
        int[] results = new int[1];
        results[0] = result;
        AsyncResult.forMessage(msg, results, null);
        msg.sendToTarget();
        //mServiceHandler.sendMessage(mServiceHandler.obtainMessage(EVENT_CHANGE_PRIMARY_SIM));
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

        int settingsNetworkMode = Settings.Global.getInt(
                getContentResolver(),
                Settings.Global.PREFERRED_NETWORK_MODE, NETWORK_MODE_WCDMA_PREF);

         int settingsNetwork2Mode = Settings.Global.getInt(
                 getContentResolver(),
                 Settings.Global.PREFERRED_NETWORK2_MODE, NETWORK_MODE_WCDMA_PREF);
		if ( settingsNetworkMode != 1 && settingsNetwork2Mode != 1){
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
            broadcastSwitchStage(SIM_SWITCH_END, SWITCH_ERROR_RAT_CHANGE);
            if (DBG) log("Error: rat swap exception: " + ar.exception);
        } else {
            swapProtocolStack();
        }
    }

    private void swapProtocolStack() {

        broadcastSwitchStage("swapping protocol stack", 0);

        mSwapState = 0;

        ((PhoneProxy)PhoneGlobals.getInstance().phone)
                .requestProtocolStackSwap(mServiceHandler.obtainMessage(EVENT_PS_SWAP_1_DONE), SWAP_PS_RESET_RADIO_STATE);
        ((PhoneProxy)PhoneGlobals.getInstance().phone2)
                .requestProtocolStackSwap(mServiceHandler.obtainMessage(EVENT_PS_SWAP_2_DONE), SWAP_PS_RESET_RADIO_STATE);
    }

    private void handleAllPSswapDone(Message msg) {
        AsyncResult ar = (AsyncResult) msg.obj;

        if (ar.exception != null) {
            broadcastSwitchStage(SIM_SWITCH_END, SWITCH_ERROR_GENERIC);
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
            broadcastSwitchStage(SIM_SWITCH_END, SWITCH_ERROR_RAT_CHANGE);
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
                getContentResolver(),
                Settings.Global.MOBILE_DATA_SIM,
                ConnectivityManager.MOBILE_DATA_NETWORK_SLOT_A);
        return curId;
    }

    private final ISimWidgetService.Stub mBinder = new ISimWidgetService.Stub() {
        public boolean setPrimarySim(int simId) {
            if (DBG) log("setPrimarySim: " + simId);
            if ((simId == DSDS_SLOT_1_ID || simId == DSDS_SLOT_2_ID) &&
                     mDynamicDataSimPolicy != TelephonyManager.DYNAMIC_DATA_SIM_DISABLED) {
                if (simId == getPrimaryId()) {
                    if (DBG) Log.w(TAG, "Widget has logic problem. No change for same sim id.");
                    broadcastSwitchStage(SIM_SWITCH_END, SWITCH_NO_CHANGE);
                    return false;
                }

                if (mNewSimId != -1) {
                    if (DBG) Log.w(TAG, "Switching is in progress. This request is aborted.");
                    return false;
                }

                mNewSimId = simId;
                boolean ret = false;

                if (mDynamicDataSimPolicy == TelephonyManager.DYNAMIC_DATA_SIM_ENABLED_REBOOT) {
                    //it takes too long to setRadio Off on SIMs, just wait for modem/ril fix
                    //setRadiosOff();
                    ret = setPrimarySimDynamically(mNewSimId);
                    //applySettings();
                    //mServiceHandler.sendMessage(mServiceHandler.obtainMessage(EVENT_CHANGE_PRIMARY_SIM));
                    //PhoneFactory.setPrimarySim(mNewSimId);
                    //broadcastSwitchStage(SIM_SWITCH_END, SWITCH_SUCCESS);

                    // give time to setting
                    //mServiceHandler.sendMessageDelayed(mServiceHandler.obtainMessage(EVENT_REBOOT_DEVICE), 1000);
                } else {
                    handleRatSettings();
                }

                return ret;
            } else {
                if (DBG) Log.w(TAG, "Wrong simId to set primary sim: " + simId +
                                           " Dynamic DataSIM: " + mDynamicDataSimPolicy);
                broadcastSwitchStage(SIM_SWITCH_END, SWITCH_ERROR_GENERIC);
                return false;
            }
        }

        public int getPrimarySim() {
            return getPrimaryId();
        }

        public void enableSim(int simId, boolean enabled) {
            if (DBG) log("enable sim: " + simId + " to: " + enabled);
            if (simId == DSDS_SLOT_1_ID || simId == DSDS_SLOT_2_ID) {
                Settings.Global.putInt(
                        getContentResolver(),
                        simId == DSDS_SLOT_1_ID ? Settings.Global.DUAL_SLOT_1_ENABLED
                        : Settings.Global.DUAL_SLOT_2_ENABLED,
                        enabled ? 1 : 0);
                ((PhoneProxy)PhoneGlobals.getInstance().getPhoneBySlot(simId)).enableSim(enabled);
            } else {
                if (DBG) Log.w(TAG, "Wrong simId");
            }
        }

        public boolean isSimOn(int simId) {
            if (simId == DSDS_SLOT_1_ID || simId == DSDS_SLOT_2_ID) {
                return DualPhoneController.simModeEnabled(simId);
            } else {
                if (DBG) Log.w(TAG, "Wrong simId");
                return false;
            }
        }
    };

    private void applySettings() {
        Settings.Global.putInt(
                getContentResolver(),
                Settings.Global.MOBILE_DATA_SIM,
                mNewSimId);
        DualPhoneController.getInstance().updatePrimarySim();

        int settingsNetworkMode = Settings.Global.getInt(
                        getContentResolver(),
                        Settings.Global.PREFERRED_NETWORK_MODE, NETWORK_MODE_WCDMA_PREF);

         int settingsNetwork2Mode = Settings.Global.getInt(
                            getContentResolver(),
                            Settings.Global.PREFERRED_NETWORK2_MODE, NETWORK_MODE_WCDMA_PREF);
		if ( settingsNetworkMode ==1 && settingsNetwork2Mode == 1){
            if (DBG) log("No need to update rat setting for manual mode");
            return;
        }

        if (mNewSimId == DSDS_SLOT_1_ID) {
                    Settings.Global.putInt(getContentResolver(),
                            Settings.Global.PREFERRED_NETWORK_MODE, settingsNetwork2Mode);
            Settings.Global.putInt(getContentResolver(),
                             Settings.Global.PREFERRED_NETWORK2_MODE, NETWORK_MODE_GSM_ONLY);
        } else {
            Settings.Global.putInt(getContentResolver(),
                             Settings.Global.PREFERRED_NETWORK_MODE, NETWORK_MODE_GSM_ONLY);

                Settings.Global.putInt(getContentResolver(),
                     Settings.Global.PREFERRED_NETWORK2_MODE, settingsNetworkMode);
        }
    }

    private void broadcastSwitchStage(String stage, int result) {

        if (DBG) log("broadcast : " + stage + " result : " + result);

        if (SIM_SWITCH_END.equals(stage)) {
            mNewSimId = -1;
        }

        Intent intent = new Intent(ACTION_DATA_SIM_SWITCH);
        intent.putExtra(EXTRA_SWITCH_STAGE, stage);
        intent.putExtra(EXTRA_RESULT_CODE, result);
        getApplicationContext().sendBroadcast(intent);
    }

    boolean setPrimarySimDynamically(int simId) {
        int result = -1;
        final DataSimSwitcher switcher = new DataSimSwitcher();
        switcher.start();
        result = switcher.setPrimarySim(mNewSimId);
        log("return from setPrimarySim:" + result);
        broadcastSwitchStage(SIM_SWITCH_END, result);
        return (result == SWITCH_SUCCESS);
    }

    private void changePrimarySim() {

        //PhoneFactory.setPrimarySim(mNewSimId);
        int result = -1;
        final DataSimSwitcher switcher = new DataSimSwitcher();
        switcher.start();
        result = switcher.setPrimarySim(mNewSimId);
        log("return from setPrimarySim:" + result);
        broadcastSwitchStage(SIM_SWITCH_END, result);
        //SWITCH result shall be based on RIL reconnection
        //broadcastSwitchStage(SIM_SWITCH_END, SWITCH_SUCCESS);
    }

    private class DataSimSwitcher extends Thread {

        //private final IDsdsDataSim mDataSimService;
        private final DsdsDataSimManager mDataSimManager;

        private boolean mDone = false;
        private int mResult = -1;

        // For replies from SimCard interface
        private Handler mHandler;

        // For async handler to identify request type
        private static final int STAGE_SWITCH_START = 1;
        private static final int STAGE_SWITCH_RADIO_OFF = 2;
        private static final int STAGE_SWITCH_DONE = 3;
        private static final int EVENT_DATA_SIM_SWITCH_DONE = 100;
        private static final int EVENT_TURN_OFF_RADIO_DONE = 101;
        private int mSwitchStage;

        public DataSimSwitcher() {
            //mDataSimService = IDsdsDataSim.Stub.asInterface(
            //        ServiceManager.getService(DATA_SIM_SERVICE));
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
                                    mResult = ((int[])(ar.result))[0];
                                    Log.d(TAG, "stage:" + mSwitchStage +",ret :" + mResult);
                                    mDone = true;
                                    DataSimSwitcher.this.notifyAll();
                                }
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

       synchronized int setPrimarySim(int simId ) {
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
                    doStageRadiosOff(Message.obtain(mHandler, EVENT_DATA_SIM_SWITCH_DONE));
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
/*
    boolean isTethered() {
         ConnectivityManager cm =
             (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);

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
         boolean mToDoTether = isTethered();
         if (DBG) Log.d(TAG, "untether: " + mToDoTether);
         if (mToDoTether) {
             ConnectivityManager cm =
                 (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
             cm.setUsbTethering(false);
         }
     }

     void handleResumeUsbTether() {

         if (DBG) Log.d(TAG, "resume usb tether: " + mToDoTether);
         if (mToDoTether == false) {
             return;
         }

         mToDoTether = false;

         ConnectivityManager cm =
             (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);

         cm.setUsbTethering(true);
     }

*/

    private void log(String msg) {
        Log.d(TAG, msg);
    }
}
