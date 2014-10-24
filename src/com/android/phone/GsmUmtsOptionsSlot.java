/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.SystemProperties;

import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.provider.Settings;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.TelephonyConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyIntents2;
import com.android.internal.telephony.PhoneProxy;
import android.telephony.TelephonyManager;
import android.content.Context;

/**
 * List of Network-specific settings screens.
 */
public class GsmUmtsOptionsSlot extends PreferenceActivity {
    private static final String LOG_TAG = "GsmUmtsOptionsSlot";
    private static final boolean DBG = false;

    private PreferenceScreen mButtonAPNExpand;
    private PreferenceScreen mButtonOperatorSelectionExpand;
    private CheckBoxPreference mButtonPrefer2g;
    private CheckBoxPreference mButtonSimMode;

    private static final String BUTTON_APN_EXPAND_KEY = "button_apn_key";
    private static final String BUTTON_OPERATOR_SELECTION_EXPAND_KEY = "button_carrier_sel_key";
    private static final String BUTTON_PREFER_2G_KEY = "button_prefer_2g_key";
    private static final String BUTTON_SIM_MODE_KEY = "button_sim_mode_key";
    private PreferenceScreen mPrefScreen;

    private Phone mPhone;
    private int   mSlotId;
    private static final int DISABLED = 0;
    private static final int ENABLED = 1;
    private TelephonyManager mTelM;
    private TelephonyManager mTelM2;



    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        final Intent intent = getIntent();
        mSlotId = intent.getIntExtra(TelephonyConstants.EXTRA_SLOT, 0);
        if (DBG) log("slot from intent:" + mSlotId);

        mPhone = PhoneGlobals.getInstance().getPhoneBySlot(mSlotId);

        addPreferencesFromResource(R.xml.gsm_umts_options_slot);

        mPrefScreen = getPreferenceScreen();

        mButtonAPNExpand = (PreferenceScreen) mPrefScreen.findPreference(BUTTON_APN_EXPAND_KEY);
        mButtonOperatorSelectionExpand =
                (PreferenceScreen) mPrefScreen.findPreference(BUTTON_OPERATOR_SELECTION_EXPAND_KEY);
        mButtonPrefer2g = (CheckBoxPreference) mPrefScreen.findPreference(BUTTON_PREFER_2G_KEY);
        if (PhoneFactory.getDefaultPhone().getPhoneType() != PhoneConstants.PHONE_TYPE_GSM) {
            if (DBG) log("Not a GSM phone");
            mButtonAPNExpand.setEnabled(false);
            mButtonOperatorSelectionExpand.setEnabled(false);
            mButtonPrefer2g.setEnabled(false);
            mButtonAPNExpand.setSelectable(false);
            mButtonSimMode.setSelectable(false);
            mButtonPrefer2g.setSelectable(false);

        } else if (getResources().getBoolean(R.bool.csp_enabled)) {
            if (PhoneFactory.getDefaultPhone().isCspPlmnEnabled()) {
                if (DBG) log("[CSP] Enabling Operator Selection menu.");
                mButtonOperatorSelectionExpand.setEnabled(true);
            } else {
                if (DBG) log("[CSP] Disabling Operator Selection menu.");
                mPrefScreen.removePreference(mPrefScreen
                      .findPreference(BUTTON_OPERATOR_SELECTION_EXPAND_KEY));
            }
        }

        mButtonSimMode = (CheckBoxPreference) mPrefScreen.findPreference(BUTTON_SIM_MODE_KEY);

        mTelM = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        mTelM2 = TelephonyManager.get2ndTm();

        final IntentFilter filter = new IntentFilter();
            filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
            filter.addAction(TelephonyIntents2.ACTION_SIM_STATE_CHANGED);
        registerReceiver(mSimStateListener, filter);
    }

    private final BroadcastReceiver mSimStateListener = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (TelephonyIntents.ACTION_SIM_STATE_CHANGED.equals(action)) {
                if (mSlotId == DualPhoneController.getPrimarySimId()) {
                    updatePreferences(true);
                }
            } else if (TelephonyIntents2.ACTION_SIM_STATE_CHANGED.equals(action)) {
                if (mSlotId != DualPhoneController.getPrimarySimId()) {
                    updatePreferences(true);
                }
            }
        }
    };
    protected void onDestroy() {
            unregisterReceiver(mSimStateListener);
        super.onDestroy();
    }

    private void updatePrefer2gUI() {
        boolean enabled = true;
        int simState = getSimState();
        if (simState != TelephonyManager.SIM_STATE_READY) {
            enabled = false;
        } else if (auto3GSelection() || only3GSelection() ) {
            // auto 3g selection mode
            enabled = false;
        } else if (NetworkSettingTab.getRatSwapping() != NetworkSettingTab.RAT_SWAP_NONE) {
            enabled = false;
        }

        mButtonPrefer2g.setEnabled(enabled);
        mButtonPrefer2g.setSelectable(enabled);
    }

    private void updateSimModeUI() {
        boolean enabled = true;
        int simState = getSimState();
        if (simState == TelephonyManager.SIM_STATE_ABSENT &&
                               DualPhoneController.simModeEnabled(mSlotId)) {
            enabled = false;
        } else if (NetworkSettingTab.getRatSwapping() != NetworkSettingTab.RAT_SWAP_NONE) {
            enabled = false;
        } else if (getCallState() != TelephonyManager.CALL_STATE_IDLE) {
            enabled = false;
        }
        enabled = enabled && (!TelephonyManager.getTmBySlot(mSlotId).isSimBusy());

        mButtonSimMode.setEnabled(enabled);
        mButtonSimMode.setSelectable(enabled);
    }

    private void updateOthersUI(boolean enabled) {
        int simState = getSimState();
        if (simState != TelephonyManager.SIM_STATE_READY) {
            enabled = false;
        } else if (NetworkSettingTab.getRatSwapping() != NetworkSettingTab.RAT_SWAP_NONE) {
            // don't need disable
            enabled = true;
        }

        mButtonAPNExpand.setEnabled(enabled);
        mButtonAPNExpand.setSelectable(enabled);
        mButtonOperatorSelectionExpand.setEnabled(enabled);
        mButtonOperatorSelectionExpand.setSelectable(enabled);
    }

    public void updatePreferences(boolean enabled) {
        updatePrefer2gUI();
        updateSimModeUI();
        updateOthersUI(enabled);
    }

    public int getPrimaryDataSim() {
        enforceAccessPermission();
        int retVal = Settings.Global.getInt(getContentResolver(),
                Settings.Global.MOBILE_DATA_SIM, TelephonyConstants.DSDS_SLOT_1_ID);
        return retVal;
    }
    private void enforceAccessPermission() {
        enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE,
                "ConnectivityService");
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (DBG) log("onResume");

        updatePreferences(true);

        switch (NetworkSettingTab.getRatSwapping()) {
            case NetworkSettingTab.RAT_SWAP_SIM_1_TO_3G:
                mButtonPrefer2g.setChecked(mSlotId == 0 ? false : true);
                break;
            case NetworkSettingTab.RAT_SWAP_SIM_2_TO_3G:
                mButtonPrefer2g.setChecked(mSlotId == 0 ? true : false);
                break;
            default:
                // not in rat swapping
                int rat = Settings.Global.getInt(mPhone.getContext().getContentResolver(),
                                mSlotId == 0 ? Settings.Global.PREFERRED_NETWORK_MODE
                                             : Settings.Global.PREFERRED_NETWORK2_MODE,
                                RILConstants.NETWORK_MODE_WCDMA_PREF);

                mButtonPrefer2g.setChecked(rat == RILConstants.NETWORK_MODE_GSM_ONLY);
                break;
        }

        boolean simMode = DualPhoneController.simModeEnabled(mSlotId);
        mButtonSimMode.setChecked(simMode);
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        if (preference == mButtonAPNExpand) {
            Intent intent = new Intent("android.intent.action.MAIN");
            intent.putExtra(TelephonyConstants.EXTRA_SLOT, mSlotId);
            intent.setClassName("com.android.settings", "com.android.settings.ApnSettings");
            startActivity(intent);
            if (DBG) log("preferenceTreeClick: APN return true");
            return true;
        } else if (preference == mButtonOperatorSelectionExpand) {
            Intent intent = new Intent("android.intent.action.MAIN");
            intent.putExtra(TelephonyConstants.EXTRA_SLOT, mSlotId);
            intent.setClassName("com.android.phone", "com.android.phone.NetworkSetting");
            startActivity(intent);
            if (DBG) log("preferenceTreeClick: operator return true");
            return true;
         } else if (preference == mButtonSimMode) {
            boolean enabled = mButtonSimMode.isChecked();
            if (DBG) log("SIM " + mSlotId + " mode: " + enabled);

            if (mSlotId == TelephonyConstants.DSDS_SLOT_1_ID) {
                Settings.Global.putInt(getContentResolver(),
                                       Settings.Global.DUAL_SLOT_1_ENABLED,
                                       enabled ? ENABLED : DISABLED);
            } else if (mSlotId == TelephonyConstants.DSDS_SLOT_2_ID) {
                Settings.Global.putInt(getContentResolver(),
                                       Settings.Global.DUAL_SLOT_2_ENABLED,
                                       enabled ? ENABLED : DISABLED);
            }
            //((PhoneProxy)mPhone).enableSim(enabled);
            enableSim(mSlotId, enabled);
            updatePreferences(enabled);
            DualPhoneController.broadcastSimWidgetUpdateIntent();

            return true;
        }

        if (DBG) log("preferenceTreeClick: return false");
        return false;
    }
    void enableSim(int simId, boolean state) {
        Intent intent = new Intent(TelephonyConstants.INTENT_SWITCHING_SIM_ONOFF);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(TelephonyConstants.EXTRA_SLOT, simId);
        intent.putExtra("onoff", state);
        startActivity(intent);
    }

    private boolean auto3GSelection() {
        int gsm3GSelection = android.provider.Settings.Global.getInt(mPhone.getContext().getContentResolver(),
                        android.provider.Settings.Global.GSM_3G_SELECTION_MODE, 1);
        return gsm3GSelection == 1;
    }

    private boolean only3GSelection() {
        int only3GSelectionsel = android.provider.Settings.Global.getInt(mPhone.getContext().getContentResolver(),
                        android.provider.Settings.Global.ONLY_3G_SELECTION_MODE, 1);
        return only3GSelectionsel == 1;
    }

    private int getCallState() {
        return mTelM.getCallState() != TelephonyManager.CALL_STATE_IDLE
            ? mTelM.getCallState() : mTelM2.getCallState();
    }

    private int getSimState() {
        return DualPhoneController.isPrimarySimId(mSlotId) ?
                       mTelM.getSimState() : mTelM2.getSimState();
    }

    protected void log(String s) {
        android.util.Log.d(LOG_TAG, s);
    }
}
