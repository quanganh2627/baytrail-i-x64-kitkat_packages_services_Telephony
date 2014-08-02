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
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.android.internal.telephony.TelephonyConstants;

import static com.android.internal.telephony.TelephonyConstants.SWITCH_FAILED_PHONE_BUSY;
import static com.android.internal.telephony.TelephonyConstants.SWITCH_FAILED_SWITCHING_ON_GOING;
import static com.android.internal.telephony.TelephonyConstants.SWITCH_FAILED_TIMEDOUT;
import static com.android.internal.telephony.TelephonyConstants.EXTRA_SLOT;

public class SimOnOffActivity extends Activity{

    static final String LOG_TAG = "SimOnOffActivity";
    private static final boolean DBG = true;
    private static final boolean VDBG = true;

    public static final String EXTRA_SLOT = TelephonyConstants.EXTRA_SLOT;
    public static final String MESSAGE = "message";

    private static final int EVENT_TASK_END             = 1;
    private static final int POLL_SWITCH_MILLIS =  1000 * 2;
    private static final int SWITCHING_TIMEDOUT_MILLIS =  1000 * 1;

    private int mSlot = -1;
    private boolean mEnabling = true;

    private Dialog mDialog = null;
    private TextView tv;

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

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);

        if (!getDataFromIntent(intent)) {
            finish();
            return;
        }

        eanbleSim();
    }

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_TASK_END:
                    log("EVENT_TASK_END");
                    onSwitchingEnd(msg.arg1);
                    break;
            }
        }
    };

    private boolean getDataFromIntent(Intent intent) {
        int slot = intent.getIntExtra(EXTRA_SLOT, -1);
        mEnabling = intent.getBooleanExtra("onoff",true);
        log("try to enableSim:" + slot + "," + mEnabling);
        mSlot = slot;
        return true;
    }

    private boolean isPhoneInCall(int slot) {
        TelephonyManager tm = TelephonyManager.getTmBySlot(slot);
        if (tm != null && tm.getCallState() != TelephonyManager.CALL_STATE_IDLE) {
            return true;
        }
        return false;
    }

    void eanbleSim() {
       new Thread(new Runnable() {
            public void run() {
                int result = PhoneGlobals.getInstance().getSimSwitchingHandler().enableSim(mSlot, mEnabling);
                mHandler.sendMessage(Message.obtain(mHandler, EVENT_TASK_END, result, 0) );
            }}).start();
       updateUi();
    }

    void updateUi() {
        if (mDialog == null) {
            tv = new TextView(this);
            int titleId = mEnabling ? R.string.sim_switching_on : R.string.sim_switching_off;
            tv.setText(titleId);
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
    protected void onNewIntent(Intent intent) {
        if (intent == null) return;
        log("onNewIntent: intent = " + intent);
        log("inSwitching:" + SimSwitchingHandler.isInSimSwitching());

        int slot = intent.getIntExtra(EXTRA_SLOT, -1);

        if (SimSwitchingHandler.isInSimSwitching()) {
            log("do not reenter");
            SimSwitchingHandler.notifyOnOffResult(slot, SWITCH_FAILED_PHONE_BUSY);
            return;
        }

        if (!getDataFromIntent(intent)) {
            finish();
            return;
        }
        eanbleSim();
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
    }

    private void onSwitchingEnd(int result) {
        log("result of enableSim:" + result);
        int resId = -1;
        switch (result) {
            case SWITCH_FAILED_PHONE_BUSY:
                resId = R.string.sim_switching_failed_phone_busy;
                break;
            case SWITCH_FAILED_TIMEDOUT:
            default:
                break;

        }
        if (resId > 0) {
            Toast.makeText(getApplicationContext(),
                    resId, Toast.LENGTH_LONG).show();
        }
        if (SWITCH_FAILED_SWITCHING_ON_GOING != result) {
            finish();
        }
    }

    private static final void log(String message) {
        Log.d(LOG_TAG, message);
    }
}
