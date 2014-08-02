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

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnDismissListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.os.AsyncResult;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.provider.Settings.System;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Config;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.widget.TextView;
import android.util.Pair;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.TelephonyConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyIntents2;

import java.util.ArrayList;

import static com.android.internal.telephony.TelephonyConstants.ACTION_DATA_SIM_SWITCH;
import static com.android.internal.telephony.TelephonyConstants.EXTRA_RESULT_CODE;
import static com.android.internal.telephony.TelephonyConstants.PROP_SIM_BUSY;
import static com.android.internal.telephony.TelephonyConstants.SWITCH_FAILED_GENERIC;
import static com.android.internal.telephony.TelephonyConstants.SWITCH_FAILED_PHONE_BUSY;
import static com.android.internal.telephony.TelephonyConstants.SWITCH_FAILED_RADIO_OFF;
import static com.android.internal.telephony.TelephonyConstants.SWITCH_FAILED_SWITCHING_ON_GOING;
import static com.android.internal.telephony.TelephonyConstants.SWITCH_FAILED_TIMEDOUT;
import static com.android.internal.telephony.TelephonyConstants.SWITCH_SUCCESS;
import static com.android.internal.telephony.TelephonyConstants.EXTRA_SLOT;

public class SimSwitchingActivity extends Activity{

    static final String LOG_TAG = "SimSwitchingActivity";
    private static final boolean DBG = true;

    public static final String MESSAGE = "message";
    private static final int DIALOG_MESSAGE_VIEW = 1;

    private static final int EVENT_RESUME_USB_TETHER = 2;
    private static final int EVENT_REBIND_SERVICE = 1;
    private static final int EVENT_SIM_STATE_CHANGE = 11;
    private static final int EVENT_SIM_SETTINGS_CHANGED = 12;
    private static final int EVENT_DSDS_SWITCH_COMPLETE = 13;

    private int mTargetSim = -1;

    private Dialog mDialog = null;
    private TextView tv;
    private boolean  hasRegistered = false;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        requestWindowFeature(Window.FEATURE_NO_TITLE);

        Intent intent = getIntent();
        if (intent == null) {
            log("xxxx null intent ");
            finish();
            return;
        }
        log("onCreate");

        IntentFilter intentFilter =
                new IntentFilter(TelephonyConstants.ACTION_DATA_SIM_SWITCH);
        registerReceiver(mBroadcastReceiver, intentFilter);
        hasRegistered = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);

        if (!getDataFromIntent(intent)) {
            finish();
            return;
        }

        switchPrimarySim();
    }

    private int getCurrentPrimarySimId() {
        int simId = Settings.Global.getInt(
                getContentResolver(),
                Settings.Global.MOBILE_DATA_SIM,
                ConnectivityManager.MOBILE_DATA_NETWORK_SLOT_A);
        return simId;
    }

    private boolean isSimIdSwitchable(int simId) {
        return simId == (1 - getCurrentPrimarySimId());
    }

    private boolean getDataFromIntent(Intent intent) {
        int simId = intent.getIntExtra(EXTRA_SLOT, -1);
        log("try to setPrimarySim:" + simId);
        if (!isSimIdSwitchable(simId)) {
            log("invalidSimId:" + simId);
            return false;
        }
        mTargetSim = simId;
        return true;
    }

    private boolean isMobileDataOn() {
        ConnectivityManager cm = (ConnectivityManager)getSystemService(
                Context.CONNECTIVITY_SERVICE);
        final boolean ret = cm.getMobileDataEnabled();
        return ret;
    }

    private void toggleMobileDataEnabled() {
        ConnectivityManager cm = (ConnectivityManager)getSystemService(
                Context.CONNECTIVITY_SERVICE);
        final boolean enabled = cm.getMobileDataEnabled();
        cm.setMobileDataEnabled(!enabled);
    }

    void setDataSimSync(int simId) {
        final int dataSimId = simId;
        log("setDataSimSync, enter");
        new Thread(new Runnable() {
                public void run() {
                    log("setDataSimSync,begin");
                    int ret = setDataSimInternally(dataSimId);
                    if (DBG) log("ret from setPrimarySim:" + ret);
                    if (ret == 0) {
                        log("to restorte Data after switchingSim");
                        if (!isMobileDataOn()) {
                            toggleMobileDataEnabled();
                        }
                    }
                    log("setDataSimSync,success:" + ret);
                }
        },"DataSimSwitch").start();
        log("setDataSimSync, exit");
    }

    int setDataSimInternally(int simId) {
        int ret = 0;
        ret = PhoneGlobals.getInstance().getSimSwitchingHandler().setPrimarySim(simId);
        if (DBG) log("ret from setPrimarySim:" + ret);
        return ret;
    }

    void doSimSwitching(int simId) {
        if (DBG) log("doSimSwitching:" + simId);
        setDataSimSync(simId);
    }

    void switchPrimarySim() {
        doSimSwitching(mTargetSim);
        updateUi();
    }

    void updateUi() {
        if (mDialog == null) {
            tv = new TextView(this);
            tv.setText(R.string.sim_switching);
            tv.setGravity(Gravity.CENTER);
            tv.setHeight(getResources().getDimensionPixelSize(R.dimen.switching_sim_waiting_dialog_height));
            tv.setWidth(getResources().getDimensionPixelSize(R.dimen.switching_sim_waiting_dialog_width));
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(getResources().getDimensionPixelSize(R.dimen.switching_sim_waiting_dialog_text_size));
            Dialog progressBar = new AlertDialog.Builder(this).setView(tv).setCancelable(false).create();
            if (mDialog != null) mDialog.dismiss();
            mDialog = progressBar;
        }
        mDialog.show();
    }

    @Override
    protected Dialog onCreateDialog(int id,Bundle args) {
        Dialog dialog = null;
        switch (id) {
            case DIALOG_MESSAGE_VIEW:
               break;
        }
        return dialog;
    }

    @Override
    protected void onPrepareDialog(int id, Dialog dialog, Bundle args) {
        switch (id) {
            case DIALOG_MESSAGE_VIEW:
                //updateMessage(dialog);
                break;
        }
    }


    @Override
    protected void onNewIntent(Intent intent) {
        if (intent == null) return;
        switchPrimarySim();
    }

    @Override
    protected void onStop() {
        Log.d(LOG_TAG,"onStop()...");
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        Log.d(LOG_TAG,"onDestroy()...");
        super.onDestroy();
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }
        if (hasRegistered) {
            unregisterReceiver(mBroadcastReceiver);
        }
    }

    final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DBG) Log.e(LOG_TAG, "intent: " + intent);
            if (TelephonyConstants.ACTION_DATA_SIM_SWITCH.equals(intent.getAction())) {
                String stage = intent.getStringExtra(TelephonyConstants.EXTRA_SWITCH_STAGE);
                if (DBG) Log.e(LOG_TAG, "new stage: " + stage);
                if (TextUtils.isEmpty(stage)) return;
                if (stage.equals(TelephonyConstants.SIM_SWITCH_END)) {
                    int result = intent.getIntExtra(EXTRA_RESULT_CODE, -1);
                    onSwitchingEnd(result);
                    return;
                }
                onProgress(stage);
            }
        }
    };

    void onSwitchingEnd(int result) {
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }
        int resId = 0;
        log("onSwitchingEnd,result:" + result);
        switch (result) {
            case SWITCH_SUCCESS:
                break;
            case SWITCH_FAILED_PHONE_BUSY:
                resId = R.string.sim_switching_failed_phone_busy;
                break;
            case SWITCH_FAILED_RADIO_OFF:
                resId = R.string.sim_switching_failed_radio_off;
                break;
            case SWITCH_FAILED_GENERIC:
            default:
                resId = R.string.sim_switching_failed_generic;
                break;
        }
        if (resId != 0) {
            onSwitchingFailed(resId);
        }
        if (SWITCH_FAILED_SWITCHING_ON_GOING != result) {
            finish();
        }
    }

    private void onSwitchingFailed(int resId) {
        Toast.makeText(getApplicationContext(),
                resId, Toast.LENGTH_LONG).show();
    }

    void onProgress(String stage) {
        if (tv != null) {
            tv.setText(getString(R.string.sim_switching) + "\n" + stage);
        }

    }

   private static final void log(String message) {
        Log.d(LOG_TAG, message);
    }
}
