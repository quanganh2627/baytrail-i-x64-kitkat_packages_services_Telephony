/*
 * Copyright (C) 2009 The Android Open Source Project
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
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.preference.CheckBoxPreference;
import android.provider.Settings;
import android.util.AttributeSet;
import android.widget.Toast;
import android.content.res.Resources;
import android.util.Log;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.TelephonyConstants;
import static android.provider.Settings.Global.PREFERRED_NETWORK_MODE;
import static android.provider.Settings.Global.PREFERRED_NETWORK2_MODE;
import static com.android.internal.telephony.RILConstants.SWAP_PS_SWAP_ENABLE;

public class Use2GOnlyCheckBoxPreference extends CheckBoxPreference
        implements DialogInterface.OnClickListener {
    private static final String LOG_TAG = "Use2GOnlyCheckBoxPreference";
    private static final boolean DBG = true;

    private Phone mPhone;
    private MyHandler mHandler;
    private Context mContext;
    private int mSlotId;
    AlertDialog mSwapDialog;

    public Use2GOnlyCheckBoxPreference(Context context) {
        this(context, null);
    }

    public Use2GOnlyCheckBoxPreference(Context context, AttributeSet attrs) {
        this(context, attrs,com.android.internal.R.attr.checkBoxPreferenceStyle);
    }

    public Use2GOnlyCheckBoxPreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mContext = context;
        if (TelephonyConstants.IS_DSDS) {
            mSlotId = NetworkSettingTab.getCurrentSimSlot();
            mPhone = PhoneGlobals.getInstance().getPhoneBySlot(mSlotId);
        } else {
            mPhone = PhoneGlobals.getPhone();
        }
        mHandler = new MyHandler();
        if (TelephonyConstants.IS_DSDS) {
            if (NetworkSettingTab.getRatSwapping() == NetworkSettingTab.RAT_SWAP_NONE
                  && PhoneGlobals.mRatSettingDone == true) {
                mPhone.getPreferredNetworkType(
                    mHandler.obtainMessage(MyHandler.MESSAGE_GET_PREFERRED_NETWORK_TYPE));
            }
        } else {
            mPhone.getPreferredNetworkType(
                mHandler.obtainMessage(MyHandler.MESSAGE_GET_PREFERRED_NETWORK_TYPE));
        }
    }

    //This is a method implemented for DialogInterface.OnClickListener.
    //  Used to dismiss the dialogs when they come up.
    public void onClick(DialogInterface dialog, int which) {
        if (!TelephonyConstants.IS_DSDS) {
            return;
        }
        if (which == DialogInterface.BUTTON_POSITIVE) {
            Log.i(LOG_TAG, "RAT swapping");
            // set the other phone as 2G only first.
            if (mSlotId == 0) {
                NetworkSettingTab.setRatSwapping(NetworkSettingTab.RAT_SWAP_SIM_1_TO_3G);
                Phone phone = PhoneGlobals.getInstance().getPhoneBySlot(1);
               // ((PhoneProxy)phone).requestProtocolStackSwap(
               //            mHandler.obtainMessage(MyHandler.MESSAGE_PS_SWAP_DONE), SWAP_PS_SWAP_ENABLE);
               phone.setPreferredNetworkType(RILConstants.NETWORK_MODE_GSM_ONLY, mHandler.obtainMessage(MyHandler.MESSAGE_ONLY_ONE_3G_SET));
            } else {
                NetworkSettingTab.setRatSwapping(NetworkSettingTab.RAT_SWAP_SIM_2_TO_3G);
                Phone phone = PhoneGlobals.getInstance().getPhoneBySlot(0);
               // ((PhoneProxy)phone).requestProtocolStackSwap(
               //            mHandler.obtainMessage(MyHandler.MESSAGE_PS_SWAP_DONE), SWAP_PS_SWAP_ENABLE);               
               phone.setPreferredNetworkType(RILConstants.NETWORK_MODE_GSM_ONLY, mHandler.obtainMessage(MyHandler.MESSAGE_ONLY_ONE_3G_SET));
            }
            setEnabled(false);
        } else {
            // it means that this rat keeps as GSM only.
            setChecked(true);
        }
    }


    private void handleDualSimRatSelection(int networkType) {
        if (networkType == RILConstants.NETWORK_MODE_GSM_ONLY) {
            android.provider.Settings.Global.putInt(
                        mPhone.getContext().getContentResolver(),
                        mSlotId == 0 ? PREFERRED_NETWORK_MODE : PREFERRED_NETWORK2_MODE,
                        RILConstants.NETWORK_MODE_GSM_ONLY);

            mPhone.setPreferredNetworkType(networkType,
                mHandler.obtainMessage(MyHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
            // Disable the setting till we get a response.
            setEnabled(false);
            return;
        }

        int rat = RILConstants.NETWORK_MODE_WCDMA_PREF;
        if (mSlotId == 0) {
            rat = android.provider.Settings.Global.getInt(mPhone.getContext().getContentResolver(),
                        PREFERRED_NETWORK2_MODE, rat);
        } else {
            rat = android.provider.Settings.Global.getInt(mPhone.getContext().getContentResolver(),
                        PREFERRED_NETWORK_MODE, rat);
        }

        if (DBG) Log.i(LOG_TAG, "check the other sim's rat: " + rat);
        if (rat != RILConstants.NETWORK_MODE_GSM_ONLY) {
            if (DBG) Log.i(LOG_TAG, "show RAT warning");
            mSwapDialog = new AlertDialog.Builder(mContext).setMessage(
                        mContext.getResources().getString(R.string.only_one_3g_warning))
                        .setTitle(android.R.string.dialog_alert_title)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setPositiveButton(android.R.string.yes, this)
                        .setNegativeButton(android.R.string.no, this)
                        .create();
            mSwapDialog.show();
        } else {
            // this network is WCDMA_PREF
            android.provider.Settings.Global.putInt(
                        mPhone.getContext().getContentResolver(),
                        mSlotId == 0 ? PREFERRED_NETWORK_MODE : PREFERRED_NETWORK2_MODE,
                        networkType);

            mPhone.setPreferredNetworkType(networkType,
                mHandler.obtainMessage(MyHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
            // Disable the setting till we get a response.
            setEnabled(false);
        }
    }

    private int getDefaultNetworkMode() {
        int mode = SystemProperties.getInt("ro.telephony.default_network",
                Phone.PREFERRED_NT_MODE);
        Log.i(LOG_TAG, "getDefaultNetworkMode: mode=" + mode);
        return mode;
    }

    @Override
    protected void  onClick() {
        super.onClick();

        int networkType = isChecked() ? Phone.NT_MODE_GSM_ONLY : getDefaultNetworkMode();
        if (PhoneGlobals.getPhoneState() == PhoneConstants.State.IDLE) {
            Log.i(LOG_TAG, "set preferred network type=" + networkType);
            if (TelephonyConstants.IS_DSDS) {
                handleDualSimRatSelection(networkType);
            } else {
                android.provider.Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                    PREFERRED_NETWORK_MODE, networkType);
                mPhone.setPreferredNetworkType(networkType,
                    mHandler.obtainMessage(MyHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
                // Disable the setting till we get a response.
                setEnabled(false);
            }
        } else {
            Toast message = Toast.makeText(mContext,
                    mContext.getResources().getText(R.string.rat_not_allowed), Toast.LENGTH_SHORT);
            message.show();
            setChecked(!(networkType == Phone.NT_MODE_GSM_ONLY));
        }
    }

    private class MyHandler extends Handler {

        static final int MESSAGE_GET_PREFERRED_NETWORK_TYPE = 0;
        static final int MESSAGE_SET_PREFERRED_NETWORK_TYPE = 1;
        static final int MESSAGE_ONLY_ONE_3G_SET            = 2;
        static final int MESSAGE_PS_SWAP_DONE               = 3;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_GET_PREFERRED_NETWORK_TYPE:
                    handleGetPreferredNetworkTypeResponse(msg);
                    break;

                case MESSAGE_SET_PREFERRED_NETWORK_TYPE:
                    handleSetPreferredNetworkTypeResponse(msg);
                    break;

                case MESSAGE_ONLY_ONE_3G_SET:
                    handleOnlyOne3gSetResponse(msg);
                    break;

                case MESSAGE_PS_SWAP_DONE:
                    handlePsSwapDoneResponse(msg);
                    break;
            }
        }

        private void handleGetPreferredNetworkTypeResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;

            if (ar.exception == null) {
                int type = ((int[])ar.result)[0];
                if (type != Phone.NT_MODE_GSM_ONLY) {
                    // Back to default
                    type = getDefaultNetworkMode();
                }
                Log.i(LOG_TAG, "get preferred network type="+type);
                setChecked(type == Phone.NT_MODE_GSM_ONLY);
                int storedNetworkMode = android.provider.Settings.Global.getInt(
                        mPhone.getContext().getContentResolver(),
                        mSlotId == 0 ? PREFERRED_NETWORK_MODE
                                     : PREFERRED_NETWORK2_MODE,
                        Phone.PREFERRED_NT_MODE);

                // check changes in currentNetworkMode and updates storedNetworkMode
                if (type != storedNetworkMode) {
                    storedNetworkMode = type;
                    Log.i(LOG_TAG, "handleGetPreferredNetworkTypeResponse: " +
                                "storedNetworkMode = " + storedNetworkMode);
                    // changes the Settings.System accordingly to currentNetworkMode
                    android.provider.Settings.Global.putInt(
                            mPhone.getContext().getContentResolver(),
                            mSlotId == 0 ? PREFERRED_NETWORK_MODE
                                         : PREFERRED_NETWORK2_MODE,
                            storedNetworkMode);
                }
            } else {
                // Weird state, disable the setting
                Log.i(LOG_TAG, "get preferred network type, exception="+ar.exception);
                setEnabled(false);
            }
        }

        private void handleSetPreferredNetworkTypeResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;
            int type = getDefaultNetworkMode();
            if (ar.exception != null) {
                // Yikes, error, disable the setting
                setEnabled(false);
                // Set UI to current state
                Log.i(LOG_TAG, "set preferred network type, exception=" + ar.exception);
                mPhone.getPreferredNetworkType(obtainMessage(MESSAGE_GET_PREFERRED_NETWORK_TYPE));
            } else {
                Log.i(LOG_TAG, "set preferred network type done");
                if (!TelephonyConstants.IS_DSDS) {
                    setEnabled(true);
                    Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                        PREFERRED_NETWORK_MODE,
                        isChecked() ? Phone.NT_MODE_GSM_ONLY : Phone.NT_MODE_WCDMA_PREF);
                    return;
                }
                switch (NetworkSettingTab.getRatSwapping()) {
                    case NetworkSettingTab.RAT_SWAP_SIM_1_TO_3G:
                        Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                                   PREFERRED_NETWORK_MODE, type);
                        Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                                   PREFERRED_NETWORK2_MODE, RILConstants.NETWORK_MODE_GSM_ONLY);
                        break;
                    case NetworkSettingTab.RAT_SWAP_SIM_2_TO_3G:
                        Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                                   PREFERRED_NETWORK_MODE, RILConstants.NETWORK_MODE_GSM_ONLY);
                        Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                                   PREFERRED_NETWORK2_MODE, type);
                        break;
                    default:
                        // do nothing
                        break;
                }
                DualPhoneController.broadcastSimWidgetUpdateIntent();
                setEnabled(true);
            }
            // set back rat swap to none anyway
            NetworkSettingTab.setRatSwapping(NetworkSettingTab.RAT_SWAP_NONE);
        }

        private void handleOnlyOne3gSetResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;

            if (ar.exception != null) {
                // Yikes, error, disable the setting
                setEnabled(false);
                // Set UI to current state
                Log.i(LOG_TAG, "set preferred network type, exception=" + ar.exception);
                NetworkSettingTab.setRatSwapping(NetworkSettingTab.RAT_SWAP_NONE);
                mPhone.getPreferredNetworkType(obtainMessage(MESSAGE_GET_PREFERRED_NETWORK_TYPE));
            } else {
                Log.i(LOG_TAG, "The other sim's 2g rat is done. Setting current rat as wcdma pref.");
                mPhone.setPreferredNetworkType(RILConstants.NETWORK_MODE_WCDMA_PREF,
                    mHandler.obtainMessage(MyHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
            }
        }

        private void handlePsSwapDoneResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;

            if (ar.exception != null) {
                // Yikes, error, disable the setting
                setEnabled(false);
                // Set UI to current state
                Log.i(LOG_TAG, "set preferred network type, exception=" + ar.exception);
            } else {
                Log.i(LOG_TAG, "PS swap done");
                applyRatSettings();
            }
            NetworkSettingTab.setRatSwapping(NetworkSettingTab.RAT_SWAP_NONE);
            mPhone.getPreferredNetworkType(obtainMessage(MESSAGE_GET_PREFERRED_NETWORK_TYPE));
        }
    }

    private void applyRatSettings() {
        switch (NetworkSettingTab.getRatSwapping()) {
            case NetworkSettingTab.RAT_SWAP_SIM_1_TO_3G:
                Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                           PREFERRED_NETWORK_MODE, RILConstants.NETWORK_MODE_WCDMA_PREF);
                Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                           PREFERRED_NETWORK2_MODE, RILConstants.NETWORK_MODE_GSM_ONLY);
                break;
            case NetworkSettingTab.RAT_SWAP_SIM_2_TO_3G:
                Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                           PREFERRED_NETWORK_MODE, RILConstants.NETWORK_MODE_GSM_ONLY);
                Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                           PREFERRED_NETWORK2_MODE, RILConstants.NETWORK_MODE_WCDMA_PREF);
                break;
            default:
                // do nothing
                break;
        }
        DualPhoneController.broadcastSimWidgetUpdateIntent();
    }
}
