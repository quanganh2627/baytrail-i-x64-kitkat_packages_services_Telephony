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

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.TelephonyConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.IOemHook;
import com.android.internal.telephony.OemHookConstants;

import android.app.ActionBar;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.provider.Settings.System;
import android.provider.Settings.Secure;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Toast;

/**
 * "Mobile network settings" screen.  This preference screen lets you
 * enable/disable mobile data, and control data roaming and other
 * network-specific mobile data features.  It's used on non-voice-capable
 * tablets as well as regular phone devices.
 *
 * Note that this PreferenceActivity is part of the phone app, even though
 * you reach it from the "Wireless & Networks" section of the main
 * Settings app.  It's not part of the "Call settings" hierarchy that's
 * available from the Phone app (see CallFeaturesSetting for that.)
 */
public class MobileNetworkSettings extends PreferenceActivity
        implements DialogInterface.OnClickListener,
        DialogInterface.OnDismissListener, Preference.OnPreferenceChangeListener{

    // debug data
    private static final String LOG_TAG = "NetworkSettings";
    private static final boolean DBG = true;
    public static final int REQUEST_CODE_EXIT_ECM = 17;

    //String keys for preference lookup
    private static final String BUTTON_DATA_ENABLED_KEY = "button_data_enabled_key";
    private static final String BUTTON_3G_SELECTION_KEY = "button_3g_selection_key";
    private static final String BUTTON_3G_ONLY_KEY = "button_3g_only_key";
    private static final String BUTTON_PREFERED_NETWORK_MODE = "preferred_network_mode_key";
    private static final String BUTTON_ROAMING_KEY = "button_roaming_key";
    private static final String BUTTON_DVP_KEY = "button_dvp_key";
    private static final String BUTTON_CDMA_LTE_DATA_SERVICE_KEY = "cdma_lte_data_service_key";
    private static final String BUTTON_ENABLED_NETWORKS_KEY = "enabled_networks_key";
    private static final String BUTTON_CARRIER_SETTINGS_KEY = "carrier_settings_key";
    private static final String BUTTON_DATA_FOLLOW_SINGLE_SIM = "button_data_follow_single_sim";

    static final int preferredNetworkMode = Phone.PREFERRED_NT_MODE;

    //Information about logical "up" Activity
    private static final String UP_ACTIVITY_PACKAGE = "com.android.settings";
    private static final String UP_ACTIVITY_CLASS =
            "com.android.settings.Settings$WirelessSettingsActivity";

    //UI objects
    private ListPreference mButtonPreferredNetworkMode;
    private ListPreference mButtonEnabledNetworks;
    private CheckBoxPreference mButtonDataRoam;
    private CheckBoxPreference mButtonDataEnabled;
    private CheckBoxPreference mButton3GSelection;
    private CheckBoxPreference mButton3Gonly;
    private CheckBoxPreference mButtonDvPEnabled;
    private CheckBoxPreference mButtonDataFollowSingleSim;
    private Preference mLteDataServicePref;

    private static final String iface = "rmnet0"; //TODO: this will go away

    private Phone mPhone;
    private MyHandler mHandler;
    private My3gHandler m3gHandler;
    private boolean mOkClicked;
    private TelephonyManager mTelephony;
    private IOemHook mOemTelephony;
    private IntentFilter mIntentFilter;

    //GsmUmts options and Cdma options
    GsmUmtsOptions mGsmUmtsOptions;
    CdmaOptions mCdmaOptions;

    private Preference mClickedPreference;
    private boolean mShow4GForLTE;
    private boolean mIsGlobalCdma;

    private AlertDialog mRoamingDialog;

    private WorkerHandler mThreadHandler;
    private HandlerThread mWorkerThread = null;

    private final int CMD_GET_DVP          = 10;
    private final int MESSAGE_GET_DVP_DONE = 11;
    private final int CMD_SET_DVP          = 12;
    private final int MESSAGE_SET_DVP_DONE = 13;

    //This is a method implemented for DialogInterface.OnClickListener.
    //  Used to dismiss the dialogs when they come up.
    public void onClick(DialogInterface dialog, int which) {
        if (which == DialogInterface.BUTTON_POSITIVE) {
            mPhone.setDataRoamingEnabled(true);
            mOkClicked = true;
        } else {
            // Reset the toggle
            mButtonDataRoam.setChecked(false);
        }
    }

    public void onDismiss(DialogInterface dialog) {
        // Assuming that onClick gets called first
        if (!mOkClicked) {
            mButtonDataRoam.setChecked(false);
        }
    }
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            boolean showPreferenceScreen = true;
            if (TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(action)) {
                if (mPhone.getState() == PhoneConstants.State.IDLE) {
                    showPreferenceScreen = true;
                } else {
                    showPreferenceScreen = false;
                }
            } else if (action.equals(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
                if (intent.getBooleanExtra("state", false)) {
                    showPreferenceScreen = false;
                } else {
                    showPreferenceScreen = true;
                }
            }
            PreferenceScreen screen = getPreferenceScreen();
            if (screen != null) {
                screen.setEnabled(showPreferenceScreen);
            }
        }
    };

    /**
     * Invoked on each preference click in this hierarchy, overrides
     * PreferenceActivity's implementation.  Used to make sure we track the
     * preference click events.
     */
    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        /** TODO: Refactor and get rid of the if's using subclasses */
        if (mGsmUmtsOptions != null &&
                mGsmUmtsOptions.preferenceTreeClick(preference) == true) {
            return true;
        } else if (mCdmaOptions != null &&
                   mCdmaOptions.preferenceTreeClick(preference) == true) {
            if (Boolean.parseBoolean(
                    SystemProperties.get(TelephonyProperties.PROPERTY_INECM_MODE))) {

                mClickedPreference = preference;

                // In ECM mode launch ECM app dialog
                startActivityForResult(
                    new Intent(TelephonyIntents.ACTION_SHOW_NOTICE_ECM_BLOCK_OTHERS, null),
                    REQUEST_CODE_EXIT_ECM);
            }
            return true;
        } else if (preference == mButtonPreferredNetworkMode) {
            //displays the value taken from the Settings.System
            int settingsNetworkMode = android.provider.Settings.Global.getInt(mPhone.getContext().
                    getContentResolver(), android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                    preferredNetworkMode);
            mButtonPreferredNetworkMode.setValue(Integer.toString(settingsNetworkMode));
            return true;
        } else if (preference == mButtonDataRoam) {
            if (DBG) log("onPreferenceTreeClick: preference == mButtonDataRoam.");

            //normally called on the toggle click
            if (mButtonDataRoam.isChecked()) {
                // First confirm with a warning dialog about charges
                mOkClicked = false;
                mRoamingDialog = new AlertDialog.Builder(this).setMessage(
                        getResources().getString(R.string.roaming_warning))
                        .setTitle(android.R.string.dialog_alert_title)
                        .setIconAttribute(android.R.attr.alertDialogIcon)
                        .setPositiveButton(android.R.string.yes, this)
                        .setNegativeButton(android.R.string.no, this)
                        .create();
                mRoamingDialog.setOnDismissListener(this);
                mRoamingDialog.show();
            } else {
                mPhone.setDataRoamingEnabled(false);
            }
            return true;
        } else if (preference == mButtonDvPEnabled) {
            if (DBG) log("onPreferenceTreeClick: preference == mButtonDvPEnabled.");
            Log.i(LOG_TAG, "About to set DvP.");
            mButtonDvPEnabled.setEnabled(false);
            Message msg = mThreadHandler.obtainMessage(CMD_SET_DVP);
            msg.arg1 = mButtonDvPEnabled.isChecked() ? 1 : 0;
            mThreadHandler.sendMessage(msg);
            return true;
        } else if (preference == mButtonDataEnabled) {
            if (DBG) log("onPreferenceTreeClick: preference == mButtonDataEnabled.");
            ConnectivityManager cm =
                    (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);

            cm.setMobileDataEnabled(mButtonDataEnabled.isChecked());
            return true;
        } else if (preference == mButton3GSelection) {
            if (DBG) log("onPreferenceTreeClick: preference == mButton3GSelection.");

            android.provider.Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                        android.provider.Settings.Global.GSM_3G_SELECTION_MODE,
                        mButton3GSelection.isChecked() ? 1 : 0);

            if (mButton3GSelection.isChecked()) {

                android.provider.Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                        android.provider.Settings.Global.ONLY_3G_SELECTION_MODE, 0);
				mButton3Gonly.setEnabled(false);
                // Only the case of manual to auto mode needs to handle.
                handleManualToAutoMode();
            }
            else{
                mButton3Gonly.setEnabled(true);
            }
            return true;
        } else if (preference == mButton3Gonly) {
            if (DBG) log("onPreferenceTreeClick: preference == mButton3Gonly.");

            android.provider.Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                        android.provider.Settings.Global.ONLY_3G_SELECTION_MODE,
                        mButton3Gonly.isChecked() ? 1 : 0);

            if (mButton3Gonly.isChecked()) {
                android.provider.Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                        android.provider.Settings.Global.GSM_3G_SELECTION_MODE, 0);
				mButton3GSelection.setEnabled(false);
                // Only the case of manual to 3g mode needs to handle.
                handleManualTo3gMode();
            }
            else{
                mButton3GSelection.setEnabled(true);
            }
            return true;
        } else if (preference == mButtonDataFollowSingleSim) {
            if (DBG) log("onPreferenceTreeClick: preference == mButtonDataFollowSingleSim.");
            setDataFollowSingleSim(mButtonDataFollowSingleSim.isChecked());
            return true;
        } else if (preference == mLteDataServicePref) {
            String tmpl = android.provider.Settings.Global.getString(getContentResolver(),
                        android.provider.Settings.Global.SETUP_PREPAID_DATA_SERVICE_URL);
            if (!TextUtils.isEmpty(tmpl)) {
                TelephonyManager tm = (TelephonyManager) getSystemService(
                        Context.TELEPHONY_SERVICE);
                String imsi = tm.getSubscriberId();
                if (imsi == null) {
                    imsi = "";
                }
                final String url = TextUtils.isEmpty(tmpl) ? null
                        : TextUtils.expandTemplate(tmpl, imsi).toString();
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
            } else {
                android.util.Log.e(LOG_TAG, "Missing SETUP_PREPAID_DATA_SERVICE_URL");
            }
            return true;
        }  else if (preference == mButtonEnabledNetworks) {
            int settingsNetworkMode = android.provider.Settings.Global.getInt(mPhone.getContext().
                    getContentResolver(), android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                    preferredNetworkMode);
            mButtonEnabledNetworks.setValue(Integer.toString(settingsNetworkMode));
            return true;
        } else {
            // if the button is anything but the simple toggle preference,
            // we'll need to disable all preferences to reject all click
            // events until the sub-activity's UI comes up.
            preferenceScreen.setEnabled(false);
            // Let the intents be launched by the Preference manager
            return false;
        }
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        if (TelephonyConstants.IS_DSDS) {
            addPreferencesFromResource(R.xml.network_setting_dual_sim);
        } else {
            addPreferencesFromResource(R.xml.network_setting);
        }

        mPhone = PhoneGlobals.getPhone();
        mTelephony = (TelephonyManager)mPhone.getContext().getSystemService(Context.TELEPHONY_SERVICE);
        mOemTelephony = IOemHook.Stub.asInterface(ServiceManager.getService("oemtelephony"));
        mHandler = new MyHandler();
        m3gHandler = new My3gHandler();
        try {
            Context con = createPackageContext("com.android.systemui", 0);
            int id = con.getResources().getIdentifier("config_show4GForLTE",
                    "bool", "com.android.systemui");
            mShow4GForLTE = con.getResources().getBoolean(id);
        } catch (NameNotFoundException e) {
            loge("NameNotFoundException for show4GFotLTE");
            mShow4GForLTE = false;
        }

        //get UI object references
        PreferenceScreen prefSet = getPreferenceScreen();

        mButtonDataEnabled = (CheckBoxPreference) prefSet.findPreference(BUTTON_DATA_ENABLED_KEY);
        if (TelephonyConstants.IS_DSDS) {
            mButton3GSelection = (CheckBoxPreference) prefSet.findPreference(BUTTON_3G_SELECTION_KEY);
            mButton3Gonly = (CheckBoxPreference) prefSet.findPreference(BUTTON_3G_ONLY_KEY);
            mButtonDataFollowSingleSim = (CheckBoxPreference) prefSet.findPreference(BUTTON_DATA_FOLLOW_SINGLE_SIM);
        }
        mButtonDataRoam = (CheckBoxPreference) prefSet.findPreference(BUTTON_ROAMING_KEY);
        if (TelephonyConstants.IS_DSDS) {    
            mButtonDvPEnabled = (CheckBoxPreference) prefSet.findPreference(BUTTON_DVP_KEY);
            boolean dvpSupported = getResources().getBoolean(R.bool.config_dvp_feature_supported);
            if (!dvpSupported) {
                 prefSet.removePreference(mButtonDvPEnabled);
                 mButtonDvPEnabled = null;
            } else {
                 mWorkerThread = new HandlerThread("DvPAsyncWorker");
                 mWorkerThread.start();
                 mThreadHandler = new WorkerHandler(mWorkerThread.getLooper());

                 //Enable it after reading the real settings
                 mButtonDvPEnabled.setEnabled(false);
            }
        }
        mButtonPreferredNetworkMode = (ListPreference) prefSet.findPreference(
                BUTTON_PREFERED_NETWORK_MODE);
        mButtonEnabledNetworks = (ListPreference) prefSet.findPreference(
                BUTTON_ENABLED_NETWORKS_KEY);

        mLteDataServicePref = prefSet.findPreference(BUTTON_CDMA_LTE_DATA_SERVICE_KEY);

        boolean isLteOnCdma = mPhone.getLteOnCdmaMode() == PhoneConstants.LTE_ON_CDMA_TRUE;
        mIsGlobalCdma = isLteOnCdma && getResources().getBoolean(R.bool.config_show_cdma);
        if (getResources().getBoolean(R.bool.world_phone) == true) {
            prefSet.removePreference(mButtonEnabledNetworks);
            // set the listener for the mButtonPreferredNetworkMode list preference so we can issue
            // change Preferred Network Mode.
            mButtonPreferredNetworkMode.setOnPreferenceChangeListener(this);

            //Get the networkMode from Settings.System and displays it
            int settingsNetworkMode = android.provider.Settings.Global.getInt(mPhone.getContext().
                    getContentResolver(),android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                    preferredNetworkMode);
            mButtonPreferredNetworkMode.setValue(Integer.toString(settingsNetworkMode));
            mCdmaOptions = new CdmaOptions(this, prefSet, mPhone);
            if (TelephonyConstants.IS_DSDS) {
            } else {
                mGsmUmtsOptions = new GsmUmtsOptions(this, prefSet);
            }
        } else {
            prefSet.removePreference(mButtonPreferredNetworkMode);
            int phoneType = mPhone.getPhoneType();
            if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                if (isLteOnCdma) {
                    mButtonEnabledNetworks.setEntries(
                            R.array.enabled_networks_cdma_choices);
                    mButtonEnabledNetworks.setEntryValues(
                            R.array.enabled_networks_cdma_values);
                }
                mCdmaOptions = new CdmaOptions(this, prefSet, mPhone);
            } else if (phoneType == PhoneConstants.PHONE_TYPE_GSM) {

                if (!getResources().getBoolean(R.bool.config_prefer_2g)
                        && !getResources().getBoolean(R.bool.config_enabled_lte)) {
                    mButtonEnabledNetworks.setEntries(
                            R.array.enabled_networks_except_gsm_lte_choices);
                    mButtonEnabledNetworks.setEntryValues(
                            R.array.enabled_networks_except_gsm_lte_values);
                } else if (!getResources().getBoolean(R.bool.config_prefer_2g)) {
                    int select = (mShow4GForLTE == true) ?
                        R.array.enabled_networks_except_gsm_4g_choices
                        : R.array.enabled_networks_except_gsm_choices;
                    mButtonEnabledNetworks.setEntries(select);
                    mButtonEnabledNetworks.setEntryValues(
                            R.array.enabled_networks_except_gsm_values);
                } else if (!getResources().getBoolean(R.bool.config_enabled_lte)) {
                    mButtonEnabledNetworks.setEntries(
                            R.array.enabled_networks_except_lte_choices);
                    mButtonEnabledNetworks.setEntryValues(
                            R.array.enabled_networks_except_lte_values);
                } else if (mIsGlobalCdma) {
                    mButtonEnabledNetworks.setEntries(
                            R.array.enabled_networks_cdma_choices);
                    mButtonEnabledNetworks.setEntryValues(
                            R.array.enabled_networks_cdma_values);
                } else {
                    int select = (mShow4GForLTE == true) ? R.array.enabled_networks_4g_choices
                        : R.array.enabled_networks_choices;
                    mButtonEnabledNetworks.setEntries(select);
                    mButtonEnabledNetworks.setEntryValues(
                            R.array.enabled_networks_values);
                }
                if (!TelephonyConstants.IS_DSDS) {
                    mGsmUmtsOptions = new GsmUmtsOptions(this, prefSet);
                } else {
                    prefSet.removePreference(mButtonEnabledNetworks);
                }
            } else {
                throw new IllegalStateException("Unexpected phone type: " + phoneType);
            }
            mButtonEnabledNetworks.setOnPreferenceChangeListener(this);
            int settingsNetworkMode = android.provider.Settings.Global.getInt(
                    mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                    preferredNetworkMode);
            if (DBG) log("settingsNetworkMode: " + settingsNetworkMode);
            mButtonEnabledNetworks.setValue(Integer.toString(settingsNetworkMode));
        }

        final boolean missingDataServiceUrl = TextUtils.isEmpty(
                android.provider.Settings.Global.getString(getContentResolver(),
                        android.provider.Settings.Global.SETUP_PREPAID_DATA_SERVICE_URL));
        if (!isLteOnCdma || missingDataServiceUrl) {
            prefSet.removePreference(mLteDataServicePref);
        } else {
            android.util.Log.d(LOG_TAG, "keep ltePref");
        }

        // Read platform settings for carrier settings
        final boolean isCarrierSettingsEnabled = getResources().getBoolean(
                R.bool.config_carrier_settings_enable);
        if (!isCarrierSettingsEnabled) {
            Preference pref = prefSet.findPreference(BUTTON_CARRIER_SETTINGS_KEY);
            if (pref != null) {
                prefSet.removePreference(pref);
            }
        }

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            // android.R.id.home will be triggered in onOptionsItemSelected()
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
        mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        mIntentFilter.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
    }

    boolean enable3GSelection() {
        if (mTelephony.isSimOff(TelephonyConstants.DSDS_SLOT_1_ID) &&
                mTelephony.isSimOff(TelephonyConstants.DSDS_SLOT_2_ID)) {
            return false;
        }

        if (isCallIdle() == false) {
            return false;
        }

        return NetworkSettingTab.getRatSwapping() == NetworkSettingTab.RAT_SWAP_NONE;
    }

    @Override
    protected void onResume() {
        super.onResume();

        registerReceiver(mReceiver, mIntentFilter);
        // upon resumption from the sub-activity, make sure we re-enable the
        // preferences.
        getPreferenceScreen().setEnabled(
                Settings.Global.getInt(mPhone.getContext().getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) == 0);

        ConnectivityManager cm =
                (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        mButtonDataEnabled.setChecked(cm.getMobileDataEnabled());

        if (TelephonyConstants.IS_DSDS) {
            int settingsNetworkMode = android.provider.Settings.Global.getInt(
                    mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                    preferredNetworkMode);
            boolean auto3G = android.provider.Settings.Global.getInt(mPhone.getContext().getContentResolver(),
                        android.provider.Settings.Global.GSM_3G_SELECTION_MODE, 1) == 1;
            if ( settingsNetworkMode == RILConstants.NETWORK_MODE_WCDMA_PREF && auto3G ){
                 mButton3GSelection.setChecked(true);
                 // when rat swapping, disable this option.
                 mButton3GSelection.setEnabled(enable3GSelection());
                 mButton3Gonly.setEnabled(false);
            }
            boolean only3G = android.provider.Settings.Global.getInt(mPhone.getContext().getContentResolver(),
                        android.provider.Settings.Global.ONLY_3G_SELECTION_MODE, 1) == 1;
            if ( settingsNetworkMode == RILConstants.NETWORK_MODE_WCDMA_ONLY && only3G ){
                 mButton3Gonly.setChecked(true);
                 // when rat swapping, disable this option.
                 mButton3Gonly.setEnabled(enable3GSelection());
                 mButton3GSelection.setEnabled(false);
            }
            mButtonDataFollowSingleSim.setChecked(isDataFollowSingleSim());
        }

        // Set UI state in onResume because a user could go home, launch some
        // app to change this setting's backend, and re-launch this settings app
        // and the UI state would be inconsistent with actual state
        mButtonDataRoam.setChecked(mPhone.getDataRoamingEnabled());

        if (mButtonDvPEnabled != null) {
            mThreadHandler.sendEmptyMessage(CMD_GET_DVP);
        }
        if (getPreferenceScreen().findPreference(BUTTON_PREFERED_NETWORK_MODE) != null)  {
            mPhone.getPreferredNetworkType(mHandler.obtainMessage(
                    MyHandler.MESSAGE_GET_PREFERRED_NETWORK_TYPE));
        }

        if (getPreferenceScreen().findPreference(BUTTON_ENABLED_NETWORKS_KEY) != null)  {
            mPhone.getPreferredNetworkType(mHandler.obtainMessage(
                    MyHandler.MESSAGE_GET_PREFERRED_NETWORK_TYPE));
        }
        mTelephony.listen(mPhoneStateListener, PhoneStateListener.LISTEN_SERVICE_STATE);
    }
	
    private boolean isDataFollowSingleSim() {
        return DualPhoneController.isDataFollowSingleSim();
    }

    private void setDataFollowSingleSim(boolean enabling) {
        if (DBG) log("setDataFollowSingleSim:" + enabling);
        android.provider.Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                android.provider.Settings.Global.DATA_FOLLOW_SINGLE_SIM,
                enabling ? 1 : 0);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mReceiver);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mTelephony.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if ((mRoamingDialog != null) && mRoamingDialog.isShowing()) {
            mRoamingDialog.dismiss();
        }

        if (mWorkerThread != null) {
            mWorkerThread.quit();
        }
    }

    /**
     * Implemented to support onPreferenceChangeListener to look for preference
     * changes specifically on CLIR.
     *
     * @param preference is the preference to be changed, should be mButtonCLIR.
     * @param objValue should be the value of the selection, NOT its localized
     * display value.
     */
    public boolean onPreferenceChange(Preference preference, Object objValue) {
        if (preference == mButtonPreferredNetworkMode) {
            //NOTE onPreferenceChange seems to be called even if there is no change
            //Check if the button value is changed from the System.Setting
            mButtonPreferredNetworkMode.setValue((String) objValue);
            int buttonNetworkMode;
            buttonNetworkMode = Integer.valueOf((String) objValue).intValue();
            int settingsNetworkMode = android.provider.Settings.Global.getInt(
                    mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Global.PREFERRED_NETWORK_MODE, preferredNetworkMode);
            if (buttonNetworkMode != settingsNetworkMode) {
                int modemNetworkMode;
                // if new mode is invalid ignore it
                switch (buttonNetworkMode) {
                    case Phone.NT_MODE_WCDMA_PREF:
                    case Phone.NT_MODE_GSM_ONLY:
                    case Phone.NT_MODE_WCDMA_ONLY:
                    case Phone.NT_MODE_GSM_UMTS:
                    case Phone.NT_MODE_CDMA:
                    case Phone.NT_MODE_CDMA_NO_EVDO:
                    case Phone.NT_MODE_EVDO_NO_CDMA:
                    case Phone.NT_MODE_GLOBAL:
                    case Phone.NT_MODE_LTE_CDMA_AND_EVDO:
                    case Phone.NT_MODE_LTE_GSM_WCDMA:
                    case Phone.NT_MODE_LTE_CMDA_EVDO_GSM_WCDMA:
                    case Phone.NT_MODE_LTE_ONLY:
                    case Phone.NT_MODE_LTE_WCDMA:
                        // This is one of the modes we recognize
                        modemNetworkMode = buttonNetworkMode;
                        break;
                    default:
                        loge("Invalid Network Mode (" + buttonNetworkMode + ") chosen. Ignore.");
                        return true;
                }

                UpdatePreferredNetworkModeSummary(buttonNetworkMode);

                android.provider.Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                        android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                        buttonNetworkMode );
                mButtonPreferredNetworkMode.setEnabled(false);
                //Set the modem network mode
                mPhone.setPreferredNetworkType(modemNetworkMode, mHandler
                        .obtainMessage(MyHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
            }
        } else if (preference == mButtonEnabledNetworks) {
            mButtonEnabledNetworks.setValue((String) objValue);
            int buttonNetworkMode;
            buttonNetworkMode = Integer.valueOf((String) objValue).intValue();
            if (DBG) log("buttonNetworkMode: " + buttonNetworkMode);
            int settingsNetworkMode = android.provider.Settings.Global.getInt(
                    mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Global.PREFERRED_NETWORK_MODE, preferredNetworkMode);
            if (buttonNetworkMode != settingsNetworkMode) {
                int modemNetworkMode;
                // if new mode is invalid ignore it
                switch (buttonNetworkMode) {
                    case Phone.NT_MODE_WCDMA_PREF:
                    case Phone.NT_MODE_GSM_ONLY:
                    case Phone.NT_MODE_WCDMA_ONLY:
                    case Phone.NT_MODE_LTE_GSM_WCDMA:
                    case Phone.NT_MODE_LTE_CMDA_EVDO_GSM_WCDMA:
                    case Phone.NT_MODE_CDMA:
                    case Phone.NT_MODE_CDMA_NO_EVDO:
                    case Phone.NT_MODE_LTE_CDMA_AND_EVDO:
                        // This is one of the modes we recognize
                        modemNetworkMode = buttonNetworkMode;
                        break;
                    default:
                        loge("Invalid Network Mode (" + buttonNetworkMode + ") chosen. Ignore.");
                        return true;
                }

                UpdateEnabledNetworksValueAndSummary(buttonNetworkMode);

                android.provider.Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                        android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                        buttonNetworkMode );
                mButtonEnabledNetworks.setEnabled(false);
                //Set the modem network mode
                mPhone.setPreferredNetworkType(modemNetworkMode, mHandler
                        .obtainMessage(MyHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
            }
        }

        // always let the preference setting proceed.
        return true;
    }

    private class MyHandler extends Handler {

        static final int MESSAGE_GET_PREFERRED_NETWORK_TYPE = 0;
        static final int MESSAGE_SET_PREFERRED_NETWORK_TYPE = 1;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_GET_PREFERRED_NETWORK_TYPE:
                    handleGetPreferredNetworkTypeResponse(msg);
                    break;

                case MESSAGE_SET_PREFERRED_NETWORK_TYPE:
                    handleSetPreferredNetworkTypeResponse(msg);
                    break;

                case MESSAGE_GET_DVP_DONE:
                    handleGetDvPStateDone(msg.arg1);
                    break;

                case MESSAGE_SET_DVP_DONE:
                    handleSetDvPStateDone(msg.arg1 == 1, msg.arg2 == 1);
                    break;
            }
        }

        private void handleGetPreferredNetworkTypeResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;

            if (ar.exception == null) {
                int modemNetworkMode = ((int[])ar.result)[0];

                if (DBG) {
                    log ("handleGetPreferredNetworkTypeResponse: modemNetworkMode = " +
                            modemNetworkMode);
                }

                int settingsNetworkMode = android.provider.Settings.Global.getInt(
                        mPhone.getContext().getContentResolver(),
                        android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                        preferredNetworkMode);

                if (DBG) {
                    log("handleGetPreferredNetworkTypeReponse: settingsNetworkMode = " +
                            settingsNetworkMode);
                }

                //check that modemNetworkMode is from an accepted value
                if (modemNetworkMode == Phone.NT_MODE_WCDMA_PREF ||
                        modemNetworkMode == Phone.NT_MODE_GSM_ONLY ||
                        modemNetworkMode == Phone.NT_MODE_WCDMA_ONLY ||
                        modemNetworkMode == Phone.NT_MODE_GSM_UMTS ||
                        modemNetworkMode == Phone.NT_MODE_CDMA ||
                        modemNetworkMode == Phone.NT_MODE_CDMA_NO_EVDO ||
                        modemNetworkMode == Phone.NT_MODE_EVDO_NO_CDMA ||
                        modemNetworkMode == Phone.NT_MODE_GLOBAL ||
                        modemNetworkMode == Phone.NT_MODE_LTE_CDMA_AND_EVDO ||
                        modemNetworkMode == Phone.NT_MODE_LTE_GSM_WCDMA ||
                        modemNetworkMode == Phone.NT_MODE_LTE_CMDA_EVDO_GSM_WCDMA ||
                        modemNetworkMode == Phone.NT_MODE_LTE_ONLY ||
                        modemNetworkMode == Phone.NT_MODE_LTE_WCDMA) {
                    if (DBG) {
                        log("handleGetPreferredNetworkTypeResponse: if 1: modemNetworkMode = " +
                                modemNetworkMode);
                    }

                    //check changes in modemNetworkMode and updates settingsNetworkMode
                    if (modemNetworkMode != settingsNetworkMode) {
                        if (DBG) {
                            log("handleGetPreferredNetworkTypeResponse: if 2: " +
                                    "modemNetworkMode != settingsNetworkMode");
                        }

                        settingsNetworkMode = modemNetworkMode;

                        if (DBG) { log("handleGetPreferredNetworkTypeResponse: if 2: " +
                                "settingsNetworkMode = " + settingsNetworkMode);
                        }

                        //changes the Settings.System accordingly to modemNetworkMode
                        android.provider.Settings.Global.putInt(
                                mPhone.getContext().getContentResolver(),
                                android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                                settingsNetworkMode );
                    }

                    UpdatePreferredNetworkModeSummary(modemNetworkMode);
                    UpdateEnabledNetworksValueAndSummary(modemNetworkMode);
                    // changes the mButtonPreferredNetworkMode accordingly to modemNetworkMode
                    mButtonPreferredNetworkMode.setValue(Integer.toString(modemNetworkMode));
                } else {
                    if (DBG) log("handleGetPreferredNetworkTypeResponse: else: reset to default");
                    resetNetworkModeToDefault();
                }
            }
        }

        private void handleSetPreferredNetworkTypeResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;

            mButtonPreferredNetworkMode.setEnabled(true);
            mButtonEnabledNetworks.setEnabled(true);
            if (ar.exception == null) {
                int networkMode = Integer.valueOf(
                        mButtonPreferredNetworkMode.getValue()).intValue();
                android.provider.Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                        android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                        networkMode );
                networkMode = Integer.valueOf(
                        mButtonEnabledNetworks.getValue()).intValue();
                android.provider.Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                        android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                        networkMode );
            } else {
                mPhone.getPreferredNetworkType(obtainMessage(MESSAGE_GET_PREFERRED_NETWORK_TYPE));
            }
        }

        private void resetNetworkModeToDefault() {
            //set the mButtonPreferredNetworkMode
            mButtonPreferredNetworkMode.setValue(Integer.toString(preferredNetworkMode));
            mButtonEnabledNetworks.setValue(Integer.toString(preferredNetworkMode));
            //set the Settings.System
            android.provider.Settings.Global.putInt(mPhone.getContext().getContentResolver(),
                        android.provider.Settings.Global.PREFERRED_NETWORK_MODE,
                        preferredNetworkMode );
            //Set the Modem
            mPhone.setPreferredNetworkType(preferredNetworkMode,
                    this.obtainMessage(MyHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
        }

        private void handleGetDvPStateDone(int state) {
            if (!isDestroyed()) {
                mButtonDvPEnabled.setEnabled(true);

                if (state == OemHookConstants.DVP_STATE_ENABLED) {
                    mButtonDvPEnabled.setChecked(true);
                } else {
                    mButtonDvPEnabled.setChecked(false);
                    if (state != OemHookConstants.DVP_STATE_DISABLED) {
                        Toast message = Toast.makeText(MobileNetworkSettings.this,
                                getResources().getText(R.string.dvp_get_failed), Toast.LENGTH_SHORT);
                        message.show();
                    }
                }
            }
        }

        private void handleSetDvPStateDone(boolean result, boolean oldSetting) {
            if (!isDestroyed()) {
                mButtonDvPEnabled.setEnabled(true);

                if (!result) {
                    mButtonDvPEnabled.setChecked(oldSetting);
                    Toast message = Toast.makeText(MobileNetworkSettings.this,
                            getResources().getText(R.string.dvp_set_failed), Toast.LENGTH_SHORT);
                    message.show();
                }
            }
        }
    }

    private void UpdatePreferredNetworkModeSummary(int NetworkMode) {
        switch(NetworkMode) {
            case Phone.NT_MODE_WCDMA_PREF:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_wcdma_perf_summary);
                break;
            case Phone.NT_MODE_GSM_ONLY:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_gsm_only_summary);
                break;
            case Phone.NT_MODE_WCDMA_ONLY:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_wcdma_only_summary);
                break;
            case Phone.NT_MODE_GSM_UMTS:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_gsm_wcdma_summary);
                break;
            case Phone.NT_MODE_CDMA:
                switch (mPhone.getLteOnCdmaMode()) {
                    case PhoneConstants.LTE_ON_CDMA_TRUE:
                        mButtonPreferredNetworkMode.setSummary(
                            R.string.preferred_network_mode_cdma_summary);
                    break;
                    case PhoneConstants.LTE_ON_CDMA_FALSE:
                    default:
                        mButtonPreferredNetworkMode.setSummary(
                            R.string.preferred_network_mode_cdma_evdo_summary);
                        break;
                }
                break;
            case Phone.NT_MODE_CDMA_NO_EVDO:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_cdma_only_summary);
                break;
            case Phone.NT_MODE_EVDO_NO_CDMA:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_evdo_only_summary);
                break;
            case Phone.NT_MODE_LTE_ONLY:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_lte_summary);
                break;
            case Phone.NT_MODE_LTE_GSM_WCDMA:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_lte_gsm_wcdma_summary);
                break;
            case Phone.NT_MODE_LTE_CDMA_AND_EVDO:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_lte_cdma_evdo_summary);
                break;
            case Phone.NT_MODE_LTE_CMDA_EVDO_GSM_WCDMA:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_global_summary);
                break;
            case Phone.NT_MODE_GLOBAL:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_cdma_evdo_gsm_wcdma_summary);
                break;
            case Phone.NT_MODE_LTE_WCDMA:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_lte_wcdma_summary);
                break;
            default:
                mButtonPreferredNetworkMode.setSummary(
                        R.string.preferred_network_mode_global_summary);
        }
    }

    private void UpdateEnabledNetworksValueAndSummary(int NetworkMode) {
        switch (NetworkMode) {
            case Phone.NT_MODE_WCDMA_ONLY:
            case Phone.NT_MODE_GSM_UMTS:
            case Phone.NT_MODE_WCDMA_PREF:
                if (!mIsGlobalCdma) {
                    mButtonEnabledNetworks.setValue(
                            Integer.toString(Phone.NT_MODE_WCDMA_PREF));
                    mButtonEnabledNetworks.setSummary(R.string.network_3G);
                } else {
                    mButtonEnabledNetworks.setValue(
                            Integer.toString(Phone.NT_MODE_LTE_CMDA_EVDO_GSM_WCDMA));
                    mButtonEnabledNetworks.setSummary(R.string.network_global);
                }
                break;
            case Phone.NT_MODE_GSM_ONLY:
                if (!mIsGlobalCdma) {
                    mButtonEnabledNetworks.setValue(
                            Integer.toString(Phone.NT_MODE_GSM_ONLY));
                    mButtonEnabledNetworks.setSummary(R.string.network_2G);
                } else {
                    mButtonEnabledNetworks.setValue(
                            Integer.toString(Phone.NT_MODE_LTE_CMDA_EVDO_GSM_WCDMA));
                    mButtonEnabledNetworks.setSummary(R.string.network_global);
                }
                break;
            case Phone.NT_MODE_LTE_GSM_WCDMA:
            case Phone.NT_MODE_LTE_ONLY:
            case Phone.NT_MODE_LTE_WCDMA:
                if (!mIsGlobalCdma) {
                    mButtonEnabledNetworks.setValue(
                            Integer.toString(Phone.NT_MODE_LTE_GSM_WCDMA));
                    mButtonEnabledNetworks.setSummary((mShow4GForLTE == true)
                            ? R.string.network_4G : R.string.network_lte);
                } else {
                    mButtonEnabledNetworks.setValue(
                            Integer.toString(Phone.NT_MODE_LTE_CMDA_EVDO_GSM_WCDMA));
                    mButtonEnabledNetworks.setSummary(R.string.network_global);
                }
                break;
            case Phone.NT_MODE_LTE_CDMA_AND_EVDO:
                mButtonEnabledNetworks.setValue(
                        Integer.toString(Phone.NT_MODE_LTE_CDMA_AND_EVDO));
                mButtonEnabledNetworks.setSummary(R.string.network_lte);
                break;
            case Phone.NT_MODE_CDMA:
            case Phone.NT_MODE_EVDO_NO_CDMA:
            case Phone.NT_MODE_GLOBAL:
                mButtonEnabledNetworks.setValue(
                        Integer.toString(Phone.NT_MODE_CDMA));
                mButtonEnabledNetworks.setSummary(R.string.network_3G);
                break;
            case Phone.NT_MODE_CDMA_NO_EVDO:
                mButtonEnabledNetworks.setValue(
                        Integer.toString(Phone.NT_MODE_CDMA_NO_EVDO));
                mButtonEnabledNetworks.setSummary(R.string.network_1x);
                break;
            case Phone.NT_MODE_LTE_CMDA_EVDO_GSM_WCDMA:
                mButtonEnabledNetworks.setValue(
                        Integer.toString(Phone.NT_MODE_LTE_CMDA_EVDO_GSM_WCDMA));
                mButtonEnabledNetworks.setSummary(R.string.network_global);
                break;
            default:
                String errMsg = "Invalid Network Mode (" + NetworkMode + "). Ignore.";
                loge(errMsg);
                mButtonEnabledNetworks.setSummary(errMsg);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch(requestCode) {
        case REQUEST_CODE_EXIT_ECM:
            Boolean isChoiceYes =
                data.getBooleanExtra(EmergencyCallbackModeExitDialog.EXTRA_EXIT_ECM_RESULT, false);
            if (isChoiceYes) {
                // If the phone exits from ECM mode, show the CDMA Options
                mCdmaOptions.showDialog(mClickedPreference);
            } else {
                // do nothing
            }
            break;

        default:
            break;
        }
    }

    private PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onServiceStateChanged(ServiceState state) {
            if (System.getInt(getContentResolver(), System.AIRPLANE_MODE_ON, 0) != 0 ) {
                finish();
            }
        }
    };

    private static void log(String msg) {
        Log.d(LOG_TAG, msg);
    }

    private static void loge(String msg) {
        Log.e(LOG_TAG, msg);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == android.R.id.home) {  // See ActionBar#setDisplayHomeAsUpEnabled()
            // Commenting out "logical up" capability. This is a workaround for issue 5278083.
            //
            // Settings app may not launch this activity via UP_ACTIVITY_CLASS but the other
            // Activity that looks exactly same as UP_ACTIVITY_CLASS ("SubSettings" Activity).
            // At that moment, this Activity launches UP_ACTIVITY_CLASS on top of the Activity.
            // which confuses users.
            // TODO: introduce better mechanism for "up" capability here.
            /*Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setClassName(UP_ACTIVITY_PACKAGE, UP_ACTIVITY_CLASS);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);*/
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     *  Manual to Auto mode handling.
     *  When changing Manual mode to auto, we use current primary sim as 3G card.
     *  A new handler is created because we don't want mess up with android's design.
     */
    private void handleManualToAutoMode() {
        int rat1 = RILConstants.NETWORK_MODE_WCDMA_PREF;
        rat1 = Global.getInt(mPhone.getContext().getContentResolver(), Global.PREFERRED_NETWORK_MODE, rat1);
        int rat2 = RILConstants.NETWORK_MODE_WCDMA_PREF;
        rat2 = Global.getInt(mPhone.getContext().getContentResolver(), Global.PREFERRED_NETWORK2_MODE, rat2);

        Phone phone1 = PhoneGlobals.getInstance().getPhoneBySlot(0);
        Phone phone2 = PhoneGlobals.getInstance().getPhoneBySlot(1);

        log("3g mode change -- rat 1: " + rat1 + "   rat 2: " + rat2);
        if (DualPhoneController.isPrimaryOnSim1()) {
            switch (rat1) {
                case RILConstants.NETWORK_MODE_WCDMA_PREF:
                    // Do nothing
                    break;

                case RILConstants.NETWORK_MODE_GSM_ONLY:
                    switch (rat2) {
                        case RILConstants.NETWORK_MODE_WCDMA_PREF:
						    log("handleManualToAutoMode -2 " );
                            mButton3GSelection.setEnabled(false);

                            Message msg = m3gHandler.obtainMessage(My3gHandler.MESSAGE_PS_SWAP_DONE);
                            OnlyOne3gRatSwitcher switcher = new OnlyOne3gRatSwitcher(TelephonyConstants.DSDS_SLOT_1_ID, msg);
                            switcher.startSwitch(true);
                            break;
                        case RILConstants.NETWORK_MODE_GSM_ONLY:
                            mButton3GSelection.setEnabled(false);
                            Global.putInt(mPhone.getContext().getContentResolver(),
                                    Global.PREFERRED_NETWORK_MODE, RILConstants.NETWORK_MODE_WCDMA_PREF);
                            phone1.setPreferredNetworkType(RILConstants.NETWORK_MODE_WCDMA_PREF,
                                m3gHandler.obtainMessage(My3gHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
                            break;
                        default:
                            android.util.Log.e(LOG_TAG, "handleManualToAutoMode Wrong rat 2 setting: " + rat2);
                            break;
                    }
                    break;
                case RILConstants.NETWORK_MODE_WCDMA_ONLY:

                    mButton3GSelection.setEnabled(true);

                    Global.putInt(mPhone.getContext().getContentResolver(),
                        Global.PREFERRED_NETWORK_MODE, RILConstants.NETWORK_MODE_WCDMA_PREF);
                    phone1.setPreferredNetworkType(RILConstants.NETWORK_MODE_WCDMA_PREF,
                        m3gHandler.obtainMessage(My3gHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
                    break;
                default:
                    android.util.Log.e(LOG_TAG, "handleManualToAutoMode Wrong rat 1 setting: " + rat1);
                    break;
            }
        } else {
            switch (rat2) {
                case RILConstants.NETWORK_MODE_WCDMA_PREF:
                    // Do nothing

                    break;
                case RILConstants.NETWORK_MODE_GSM_ONLY:
                    switch (rat1) {
                        case RILConstants.NETWORK_MODE_WCDMA_PREF:

                            mButton3GSelection.setEnabled(false);

                            Message msg = m3gHandler.obtainMessage(My3gHandler.MESSAGE_PS_SWAP_DONE);
                            OnlyOne3gRatSwitcher switcher = new OnlyOne3gRatSwitcher(TelephonyConstants.DSDS_SLOT_2_ID, msg);
                            switcher.startSwitch(true);
                            break;
                        case RILConstants.NETWORK_MODE_GSM_ONLY:

                            mButton3GSelection.setEnabled(false);
                            Global.putInt(mPhone.getContext().getContentResolver(),
                                    Global.PREFERRED_NETWORK2_MODE, RILConstants.NETWORK_MODE_WCDMA_PREF);
                            phone2.setPreferredNetworkType(RILConstants.NETWORK_MODE_WCDMA_PREF,
                                    m3gHandler.obtainMessage(My3gHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
                            break;
                        default:
                            android.util.Log.e(LOG_TAG, " handleManualToAutoMode-7Wrong rat 1 setting: " + rat1);
                            break;
                    }
                    break;
                case RILConstants.NETWORK_MODE_WCDMA_ONLY:

                    mButton3GSelection.setEnabled(true);
                    Global.putInt(mPhone.getContext().getContentResolver(),
                        Global.PREFERRED_NETWORK_MODE, RILConstants.NETWORK_MODE_WCDMA_PREF);
                    phone2.setPreferredNetworkType(RILConstants.NETWORK_MODE_WCDMA_PREF,
                        m3gHandler.obtainMessage(My3gHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
                    break;
                default:
                    android.util.Log.e(LOG_TAG, "handleManualToAutoMode-8 Wrong rat 2 setting: " + rat2);
                    break;
            }
        }
    }

    /**
     *  Manual to Auto mode handling.
     *  When changing Manual mode to auto, we use current primary sim as 3G card.
     *  A new handler is created because we don't want mess up with android's design.
     */
    private void handleManualTo3gMode() {
        int rat1 = RILConstants.NETWORK_MODE_WCDMA_ONLY;
        rat1 = Global.getInt(mPhone.getContext().getContentResolver(), Global.PREFERRED_NETWORK_MODE, rat1);
        int rat2 = RILConstants.NETWORK_MODE_WCDMA_ONLY;
        rat2 = Global.getInt(mPhone.getContext().getContentResolver(), Global.PREFERRED_NETWORK2_MODE, rat2);

        Phone phone1 = PhoneGlobals.getInstance().getPhoneBySlot(0);
        Phone phone2 = PhoneGlobals.getInstance().getPhoneBySlot(1);

        log("3g only mode change -- rat 1: " + rat1 + "   rat 2: " + rat2);
        if (DualPhoneController.isPrimaryOnSim1()) {
            switch (rat1) {
                case RILConstants.NETWORK_MODE_WCDMA_ONLY:
                    // Do nothing

                    break;
                case RILConstants.NETWORK_MODE_GSM_ONLY:
                    switch (rat2) {
                        case RILConstants.NETWORK_MODE_WCDMA_ONLY:

                            mButton3Gonly.setEnabled(false);

                            Message msg = m3gHandler.obtainMessage(My3gHandler.MESSAGE_PS_SWAP_DONE);
                            OnlyOne3gRatSwitcher switcher = new OnlyOne3gRatSwitcher(TelephonyConstants.DSDS_SLOT_1_ID, msg);
                            switcher.startSwitch(true);
                            break;
                        case RILConstants.NETWORK_MODE_GSM_ONLY:

                            mButton3Gonly.setEnabled(false);
                            Global.putInt(mPhone.getContext().getContentResolver(),
                                    Global.PREFERRED_NETWORK_MODE, RILConstants.NETWORK_MODE_WCDMA_ONLY);
                            phone1.setPreferredNetworkType(RILConstants.NETWORK_MODE_WCDMA_ONLY,
                                m3gHandler.obtainMessage(My3gHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
                            break;
                        default:
                            android.util.Log.e(LOG_TAG, " 3g only mode change Wrong rat 2 setting: " + rat2);
                            break;
                    }
                    break;
                case RILConstants.NETWORK_MODE_WCDMA_PREF:

                    mButton3Gonly.setEnabled(true);
                    Global.putInt(mPhone.getContext().getContentResolver(),
                        Global.PREFERRED_NETWORK_MODE, RILConstants.NETWORK_MODE_WCDMA_ONLY);
                    phone1.setPreferredNetworkType(RILConstants.NETWORK_MODE_WCDMA_ONLY,
                        m3gHandler.obtainMessage(My3gHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
                    break;
                default:
                    android.util.Log.e(LOG_TAG, "3g only mode change Wrong rat 1 setting: " + rat1);
                    break;
            }
        } else {
            switch (rat2) {
                case RILConstants.NETWORK_MODE_WCDMA_ONLY:
                    // Do nothing

                    break;
                case RILConstants.NETWORK_MODE_GSM_ONLY:
                    switch (rat1) {
                        case RILConstants.NETWORK_MODE_WCDMA_ONLY:

                            mButton3Gonly.setEnabled(false);

                            Message msg = m3gHandler.obtainMessage(My3gHandler.MESSAGE_PS_SWAP_DONE);
                            OnlyOne3gRatSwitcher switcher = new OnlyOne3gRatSwitcher(TelephonyConstants.DSDS_SLOT_2_ID, msg);
                            switcher.startSwitch(true);
                            break;
                        case RILConstants.NETWORK_MODE_GSM_ONLY:

                            mButton3Gonly.setEnabled(false);
                            Global.putInt(mPhone.getContext().getContentResolver(),
                                    Global.PREFERRED_NETWORK2_MODE, RILConstants.NETWORK_MODE_WCDMA_ONLY);
                            phone2.setPreferredNetworkType(RILConstants.NETWORK_MODE_WCDMA_ONLY,
                                    m3gHandler.obtainMessage(My3gHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
                            break;
                        default:
                            android.util.Log.e(LOG_TAG, "3g only mode change Wrong rat 1 setting: " + rat1);
                            break;
                    }
                    break;
                case RILConstants.NETWORK_MODE_WCDMA_PREF:

                    mButton3Gonly.setEnabled(true);
                    Global.putInt(mPhone.getContext().getContentResolver(),
                        Global.PREFERRED_NETWORK_MODE, RILConstants.NETWORK_MODE_WCDMA_ONLY);
                    phone2.setPreferredNetworkType(RILConstants.NETWORK_MODE_WCDMA_ONLY,
                        m3gHandler.obtainMessage(My3gHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
                    break;
                default:
                    android.util.Log.e(LOG_TAG, "3g only mode change Wrong rat 2 setting: " + rat2);
                    break;
            }
        }
    }

    private class My3gHandler extends Handler {

        private static final int MESSAGE_SET_PREFERRED_NETWORK_TYPE = 0;
        private static final int MESSAGE_PS_SWAP_DONE               = 1;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_SET_PREFERRED_NETWORK_TYPE:
                    handleSetPreferredNetworkTypeResponse(msg);
                    break;

                case MESSAGE_PS_SWAP_DONE:
                    handlePsSwapResponse(msg);
                    break;
            }
        }

        private void handleSetPreferredNetworkTypeResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;

            if (ar.exception != null) {
                // though rat set fails, we still keep 'auto' setting
                Log.i(LOG_TAG, "set preferred network type, exception=" + ar.exception);
            } else {
                Log.i(LOG_TAG, "set preferred network type (3g) done");
            }
            
            if (mButton3GSelection.isChecked()){
                mButton3GSelection.setEnabled(true);
            }
            if (mButton3Gonly.isChecked()){
                mButton3Gonly.setEnabled(true);
            }
            DualPhoneController.broadcastSimWidgetUpdateIntent();
        }

        private void handlePsSwapResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;

            if (ar.exception != null) {
                Log.i(LOG_TAG, "protocol stack swap, exception");
            } else {
                Log.i(LOG_TAG, "protocol stack swap done.");
            }
            if (mButton3GSelection.isChecked()){
                mButton3GSelection.setEnabled(true);
            }
            if (mButton3Gonly.isChecked()){
                mButton3Gonly.setEnabled(true);
            }
            DualPhoneController.broadcastSimWidgetUpdateIntent();
        }
    }

    private boolean isCallIdle() {
        int state = mTelephony.getCallState() != TelephonyManager.CALL_STATE_IDLE
                ? mTelephony.getCallState() : TelephonyManager.get2ndTm().getCallState();
        return TelephonyManager.CALL_STATE_IDLE == state;
    }

    /**
     * Thread worker class that call synchronous request to OemTelephony.
     */
    private class WorkerHandler extends Handler {
        public WorkerHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            Message reply;
            switch (msg.what) {
                case CMD_GET_DVP:
                    int state = OemHookConstants.DVP_STATE_INVALID;
                    try {
                        Log.i(LOG_TAG, "About to get DvP.");
                        state = mOemTelephony.getDvPState();
                    } catch (RemoteException e) {
                        Log.i(LOG_TAG, "remote exception while Getting DvP.");
                    }
                    Log.i(LOG_TAG, "DvP Setting: " + state);

                    // send the reply to the enclosing class.
                    reply = MobileNetworkSettings.this.mHandler.obtainMessage(MESSAGE_GET_DVP_DONE);
                    reply.arg1 = state;
                    reply.sendToTarget();
                    break;
                case CMD_SET_DVP:
                    boolean result = false;
                    boolean newSetting = msg.arg1 == 1? true : false;
                    try {
                        Log.i(LOG_TAG, "About to set DvP to ." + newSetting);
                        result = mOemTelephony.setDvPEnabled(newSetting);
                    } catch (RemoteException e) {
                        Log.i(LOG_TAG, "remote exception while Setting DvP.");
                    }
                    Log.i(LOG_TAG, "DvP Setting Result: " + result);

                    // send the reply to the enclosing class.
                    reply = MobileNetworkSettings.this.mHandler.obtainMessage(MESSAGE_SET_DVP_DONE);
                    reply.arg1 = result ? 1 : 0;
                    reply.arg2 = newSetting? 0 : 1; //old setting
                    reply.sendToTarget();
                    break;
                default:
                    break;
            }
        }
    }
}
