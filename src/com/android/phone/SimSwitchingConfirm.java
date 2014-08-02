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
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnDismissListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncResult;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.provider.Settings.System;
import android.util.Config;
import android.util.Log;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;
import android.content.IntentFilter;
import android.content.Context;
import android.content.BroadcastReceiver;
import com.android.internal.telephony.TelephonyConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyIntents2;

import java.util.ArrayList;
import android.util.Pair;

public class SimSwitchingConfirm extends Activity{

    static final String LOG_TAG = "SimSwitchingConfirm";
    private static final boolean DBG = true;

    public static final String TITLE = "title";
    public static final String MESSAGE = "message";
    private static final int DIALOG_MESSAGE_VIEW = 1;
    private int mTargetSimId = 0;

    private Dialog mDialog = null;

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

        initData();
        IntentFilter intentFilter =
            new IntentFilter(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        intentFilter.addAction(TelephonyIntents2.ACTION_SIM_STATE_CHANGED);
        intentFilter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        registerReceiver(mBroadcastReceiver, intentFilter);

        showDialog(DIALOG_MESSAGE_VIEW);
    }

    void initData() {
        mTargetSimId = 1 - DualPhoneController.getInstance().getPrimarySimId();
    }

    void switchingSim() {
        Intent newIntent = new Intent();
        newIntent.setClassName("com.android.phone", "com.android.phone.SimSwitchingActivity");
        newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        newIntent.putExtra(TelephonyConstants.EXTRA_SLOT, mTargetSimId);
        startActivity(newIntent);
    }

    @Override
    protected Dialog onCreateDialog(int id,Bundle args) {
        Dialog dialog = null;
        switch (id) {
            case DIALOG_MESSAGE_VIEW:
                dialog = new AlertDialog.Builder(this)
                    .setPositiveButton(android.R.string.ok, new AlertDialog.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            switchingSim();
                            dialog.dismiss();
                            finish();
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, new AlertDialog.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                            finish();
                        }
                    })
                    .setTitle(R.string.primary_sim_siwtching_dialog_title)
                    .setMessage(getString(R.string.primary_sim_siwtching_dialog_text, mTargetSimId + 1))
                    .setIcon(android.R.drawable.ic_dialog_info)
                    .create();

                dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                    public void onDismiss(DialogInterface dialog) {
                        finish();
                        mDialog = null;
                    }
                });
                break;
        }
        mDialog = dialog;
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

        initData();
        showDialog(DIALOG_MESSAGE_VIEW);
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
        unregisterReceiver(mBroadcastReceiver);
    }

    final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            log("recv intent:" + intent.getAction());
            if (!PhoneGlobals.getInstance().needTipSimSwitching()) {
                if (DBG) Log.d(LOG_TAG, "OOPS, canot switch to 2nd SIM now!");
                finish();
            }
        }
    };

    private static final void log(String message) {
        Log.d(LOG_TAG, message);
    }
}
