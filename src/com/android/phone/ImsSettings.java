/*
 * Copyright (C) 2013 The Android Open Source Project
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
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;

import com.intel.internal.telephony.OemTelephony.OemTelephonyConstants;

/**
 * "IMS settings" screen.  This preference screen lets you
 * enable/disable IMS, and control IMS features.  It's used on voice-capable
 * devices.
 *
 * Note that this PreferenceActivity should be part of a separate ims class as
 * you reach it from the "Wireless and Networks" section of the main
 * Settings app.
 */
public class ImsSettings extends PreferenceActivity
        implements Preference.OnPreferenceChangeListener {

    // debug data
    private static final String LOG_TAG = "ImsSettings";

    private CheckBoxPreference mImsEnabled;
    private EditTextPreference mImsApn;
    private EditTextPreference mPcscf;
    private EditTextPreference mPcscfPort;
    private ListPreference mImsAuthMode;
    private EditTextPreference mPhoneContext;
    private ListPreference mLocalBreakout;
    private EditTextPreference mXcapApn;
    private EditTextPreference mXcapRootUri;
    private EditTextPreference mXcapUsername;
    private EditTextPreference mXcapPassword;

    private String mImsApnSavedValue;
    private String mPcscfSavedValue;
    private String mPcscfPortSavedValue;
    private String mImsAuthModeSavedValue;
    private String mPhoneContextSavedValue;
    private String mLocalBreakoutSavedValue;
    private String mXcapApnSavedValue;
    private String mXcapRootUriSavedValue;
    private String mXcapUsernameSavedValue;
    private String mXcapPasswordSavedValue;

    // Resource
    private Resources mRes;
    private Phone mPhone = null;

    // Configuration management
    private static final Object REGISTRATION_STATE_LOCK = new Object();
    private static final Object CONFIGURATION_LOCK = new Object();
    private static final int UNREGISTER_IMS = 0;
    private static final int REGISTER_IMS = 1;
    final static String mApnDataKey = "ApnDataKey";

    private final static String sNotSet = "default";

    private static final int OEM_HOOK_ID = 0;
    private static final int IMS_APN_INDEX = 1;
    private static final int PCSCF_INDEX = 2;
    private static final int PCSCF_PORT_INDEX = 3;
    private static final int IMS_AUTH_MODE_INDEX = 4;
    private static final int IMS_PHONE_CONTEXT_INDEX = 5;
    private static final int LOCAL_BREAKOUT_INDEX = 6;
    private static final int XCAP_APN_INDEX = 7;
    private static final int XCAP_ROOT_URI_INDEX = 8;
    private static final int XCAP_USERNAME_INDEX = 9;
    private static final int XCAP_PASSWORD_INDEX = 10;
    // Not part of the XICFG - should be at the end
    private static final int IMS_ENABLED_INDEX = 11;
    private static final int XICFG_LAST_INDEX = IMS_ENABLED_INDEX;

    private static final int MENU_DELETE = Menu.FIRST;
    private static final int MENU_SAVE = Menu.FIRST + 1;
    private static final int MENU_CANCEL = Menu.FIRST + 2;
    private static final int ERROR_DIALOG_ID = 0;

    // RIL
    private static final int EVENT_RIL_OEM_HOOK_COMMAND_COMPLETE = 1300;
    private static final int EVENT_UNSOL_RIL_OEM_HOOK_RAW = 500;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        PreferenceManager prefMgr = getPreferenceManager();
        prefMgr.setSharedPreferencesName("ims_shared_pref");
        prefMgr.setSharedPreferencesMode(Context.MODE_WORLD_READABLE);

        addPreferencesFromResource(R.xml.ims_settings);

        // get UI object references
        PreferenceScreen prefSet = getPreferenceScreen();

        mImsEnabled = (CheckBoxPreference) prefSet.findPreference("ims_enabled");
        mImsApn = (EditTextPreference) prefSet.findPreference("ims_apn");
        mPcscf = (EditTextPreference) prefSet.findPreference("pcscf");
        mPcscfPort = (EditTextPreference) prefSet.findPreference("pcscf_port");
        mImsAuthMode = (ListPreference) prefSet.findPreference("ims_auth_mode");
        mPhoneContext = (EditTextPreference) prefSet.findPreference("phone_context");
        mLocalBreakout = (ListPreference) prefSet.findPreference("local_breakout");
        mXcapApn = (EditTextPreference) prefSet.findPreference("xcap_apn");
        mXcapRootUri = (EditTextPreference) prefSet.findPreference("xcap_root_uri");
        mXcapUsername = (EditTextPreference) prefSet.findPreference("xcap_username");
        mXcapPassword = (EditTextPreference) prefSet.findPreference("xcap_password");

        mImsEnabled.setOnPreferenceChangeListener(this);
        mImsApn.setOnPreferenceChangeListener(this);
        mPcscf.setOnPreferenceChangeListener(this);
        mPcscfPort.setOnPreferenceChangeListener(this);
        mImsAuthMode.setOnPreferenceChangeListener(this);
        mPhoneContext.setOnPreferenceChangeListener(this);
        mLocalBreakout.setOnPreferenceChangeListener(this);
        mXcapApn.setOnPreferenceChangeListener(this);
        mXcapRootUri.setOnPreferenceChangeListener(this);
        mXcapUsername.setOnPreferenceChangeListener(this);
        mXcapPassword.setOnPreferenceChangeListener(this);

        mRes = getResources();

        mPhone = PhoneFactory.getDefaultPhone();

        mImsApnSavedValue = mImsApn.getText();
        mPcscfSavedValue = mPcscf.getText();
        mPcscfPortSavedValue = mPcscfPort.getText();
        mImsAuthModeSavedValue = mImsAuthMode.getValue();
        mPhoneContextSavedValue = mPhoneContext.getText();
        mLocalBreakoutSavedValue = mLocalBreakout.getValue();
        mXcapApnSavedValue = mXcapApn.getText();
        mXcapRootUriSavedValue = mXcapRootUri.getText();
        mXcapUsernameSavedValue = mXcapUsername.getText();
        mXcapPasswordSavedValue = mXcapPassword.getText();

        // Fill the Settings
        fillUi();
    }

    private void fillUi() {
        mImsApn.setSummary(checkNull(mImsApnSavedValue));
        mPcscf.setSummary(checkNull(mPcscfSavedValue));
        mPcscfPort.setSummary(checkNull(mPcscfPortSavedValue));
        if (mImsAuthModeSavedValue != null) {
            int authModeIndex = mImsAuthMode.findIndexOfValue(mImsAuthModeSavedValue);
            mImsAuthMode.setValueIndex(authModeIndex);
            mImsAuthMode.setSummary(mImsAuthMode.getEntry());
        } else {
            mImsAuthMode.setSummary(sNotSet);
        }
        mPhoneContext.setSummary(checkNull(mPhoneContextSavedValue));
        if (mLocalBreakoutSavedValue != null) {
            int breakoutIndex = mLocalBreakout.findIndexOfValue(mLocalBreakoutSavedValue);
            mLocalBreakout.setValueIndex(breakoutIndex);
            mLocalBreakout.setSummary(mLocalBreakout.getEntry());
        } else {
            mLocalBreakout.setSummary(sNotSet);
        }
        mXcapApn.setSummary(checkNull(mXcapApnSavedValue));
        mXcapRootUri.setSummary(checkNull(mXcapRootUriSavedValue));
        mXcapUsername.setSummary(checkNull(mXcapUsernameSavedValue));
        mXcapPassword.setSummary(starify(mXcapPasswordSavedValue));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);

        menu.add(0, MENU_SAVE, 0, R.string.menu_save)
            .setIcon(android.R.drawable.ic_menu_save);
        menu.add(0, MENU_CANCEL, 0, R.string.menu_cancel)
            .setIcon(android.R.drawable.ic_menu_close_clear_cancel);
        return true;
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_SAVE:
                if (validateAndSave()) {
                    finish();
                }
                break;
            case MENU_CANCEL:
                // Discard config
                SharedPreferences.Editor prefs
                    = PreferenceManager.getDefaultSharedPreferences(this).edit();
                prefs.putString("ims_apn", mImsApnSavedValue);
                prefs.putString("pcscf", mPcscfSavedValue);
                prefs.putString("pcscf_port", mPcscfPortSavedValue);
                prefs.putString("ims_auth_mode", mImsAuthModeSavedValue);
                prefs.putString("phone_context", mPhoneContextSavedValue);
                prefs.putString("local_breakout", mLocalBreakoutSavedValue);
                prefs.putString("xcap_root_uri", mXcapRootUriSavedValue);
                prefs.putString("xcap_apn", mXcapApnSavedValue);
                prefs.putString("xcap_username", mXcapUsernameSavedValue);
                prefs.putString("xcap_password", mXcapPasswordSavedValue);
                prefs.commit();
                finish();
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onSaveInstanceState(Bundle icicle) {
        super.onSaveInstanceState(icicle);
        validateAndSave();
    }

    /* Response Message Handler */
    private Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            AsyncResult ar;
            Toast toast;

            switch (msg.what) {
                case EVENT_RIL_OEM_HOOK_COMMAND_COMPLETE:
                    ar = (AsyncResult) msg.obj;
                    if (ar.exception != null) {
                        Log.e(LOG_TAG, "IMS configuration change error, " +
                              " , result=" + ar.exception);
                        toast = Toast.makeText(ImsSettings.this,
                                               "Error applying configuration:" +
                                               ar.exception,
                                               Toast.LENGTH_LONG);
                        toast.show();
                        return;
                    }

                    if (ar.result != null) {
                       String[] r = (String[])ar.result;
                       String result = "";

                       if (r.length > 0)
                       {
                         result= r[0];
                         Log.i(LOG_TAG, "IMS configuration change, " +
                               " , lengthresult=" + r.length);
                       }
                       Log.i(LOG_TAG, "Completed IMS configuration change, " +
                               "userObj=" + ar.userObj + " , result=" + result);
                       toast = Toast.makeText(ImsSettings.this,
                                              "Applied configuration",
                               Toast.LENGTH_LONG);
                       toast.show();
                    }
                    break;

                case EVENT_UNSOL_RIL_OEM_HOOK_RAW:
                    break;
            }
        }
    };
/**
 * Check the key fields' validity and save if valid.
 * Then, send the settings to the modem.
 * @return true if the data was saved
 */
    private boolean validateAndSave() {

        if (getErrorMsg() != null) {
            showDialog(ERROR_DIALOG_ID);
            return false;
        }

        Log.i(LOG_TAG, "Saving IMS params...");

        // Now inform the modem about the new settings
        synchronized (CONFIGURATION_LOCK) {

            String[] request = new String[XICFG_LAST_INDEX];
            String mImsAuthModeValue;
            String mLocalBreakoutValue;

            // Header OEM_HOOK_ID
            request[OEM_HOOK_ID]
                = Integer.toString(OemTelephonyConstants.RIL_OEM_HOOK_STRING_IMS_CONFIG);
            // APN
            request[IMS_APN_INDEX] = checkNull(mImsApn.getText());
            // PCSCF
            request[PCSCF_INDEX] = checkNull(mPcscf.getText());
            request[PCSCF_PORT_INDEX] = checkNull(mPcscfPort.getText());
            // Authentication Mode
            mImsAuthModeValue = checkNull(mImsAuthMode.getValue());
            if (!mImsAuthModeValue.equals(sNotSet)) {
                request[IMS_AUTH_MODE_INDEX] =
                    Integer.toString(mImsAuthMode.findIndexOfValue(mImsAuthModeValue));
            } else {
                request[IMS_AUTH_MODE_INDEX] = sNotSet;
            }
            // Phone Context
            request[IMS_PHONE_CONTEXT_INDEX] = checkNull(mPhoneContext.getText());
            // Local Breakout
            mLocalBreakoutValue = checkNull(mLocalBreakout.getValue());
            if (!mLocalBreakoutValue.equals(sNotSet)) {
                request[LOCAL_BREAKOUT_INDEX] =
                    Integer.toString(mLocalBreakout.findIndexOfValue(mLocalBreakoutValue));
            } else {
                request[LOCAL_BREAKOUT_INDEX] = sNotSet;
            }
            // XCAP settings
            request[XCAP_APN_INDEX] = checkNull(mXcapApn.getText());
            request[XCAP_ROOT_URI_INDEX] = checkNull(mXcapRootUri.getText());
            request[XCAP_USERNAME_INDEX] = checkNull(mXcapUsername.getText());
            request[XCAP_PASSWORD_INDEX] = checkNull(mXcapPassword.getText());

            Message msg = mHandler.obtainMessage(EVENT_RIL_OEM_HOOK_COMMAND_COMPLETE);
            mPhone.invokeOemRilRequestStrings(request, msg);

            Log.i(LOG_TAG, "Sent Config cmd to RIL, APN = " + request[IMS_APN_INDEX].toString());
        }
        return true;
    }

    private String getErrorMsg() {
        String errorMsg = null;

        String ImsApn = checkNull(mImsApn.getText());
        String PcscfPort = checkNull(mPcscfPort.getText());

        if (ImsApn.length() < 1) {
            errorMsg = mRes.getString(R.string.error_ims_apn_empty);
        }

        if ((!PcscfPort.equals(sNotSet))&&
            ((Integer.parseInt(PcscfPort) < 1024)||
             (Integer.parseInt(PcscfPort) > 65535))) {
            errorMsg = mRes.getString(R.string.error_ims_pcscf_port);
        }

        return errorMsg;
    }

    @Override
    protected Dialog onCreateDialog(int id) {

        if (id == ERROR_DIALOG_ID) {
            String msg = getErrorMsg();

            return new AlertDialog.Builder(this)
                    .setTitle(R.string.error_title)
                    .setPositiveButton(android.R.string.ok, null)
                    .setMessage(msg)
                    .create();
        }

        return super.onCreateDialog(id);
    }

    @Override
    protected void onPrepareDialog(int id, Dialog dialog) {
        super.onPrepareDialog(id, dialog);

        if (id == ERROR_DIALOG_ID) {
            String msg = getErrorMsg();

            if (msg != null) {
                ((AlertDialog)dialog).setMessage(msg);
            }
        }
    }

    private String starify(String value) {
        if (value == null || value.length() == 0) {
            return sNotSet;
        } else {
            char[] password = new char[value.length()];
            for (int i = 0; i < password.length; i++) {
                password[i] = '*';
            }
            return new String(password);
        }
    }

    private String checkNull(String value) {
        if (value == null || value.length() == 0) {
            return sNotSet;
        } else {
            return value;
        }
    }

    private void initiateImsRegistration(int state) {
        synchronized (REGISTRATION_STATE_LOCK) {
            int temp = (state == 1) ? 1 : 0;
            String[] request = new String[2];
            request[0] = Integer
                    .toString(OemTelephonyConstants.RIL_OEM_HOOK_STRING_IMS_REGISTRATION);
            request[1] = Integer.toString(temp);

            Message msg = mHandler.obtainMessage(EVENT_RIL_OEM_HOOK_COMMAND_COMPLETE);
            mPhone.invokeOemRilRequestStrings(request, msg);
        }
    }

    @Override
    public boolean onPreferenceChange(Preference pref, Object newValue) {
        Log.i(LOG_TAG, "IMS onPreferenceChange called with value = " + newValue.toString());
        if (pref != null) {
            if (pref.equals(mImsEnabled)) {
                // Update the Checkbox
                mImsEnabled.setChecked(!(mImsEnabled.isChecked()));

                if (mImsEnabled.isChecked()) {
                    // User enabled IMS - need to update the modem
                    Log.i(LOG_TAG, "User enabled IMS");
                    initiateImsRegistration(REGISTER_IMS);
                } else {
                    // User disabled IMS - need to update the modem
                    Log.i(LOG_TAG, "User disabled IMS");
                    initiateImsRegistration(UNREGISTER_IMS);
                }

            } else if (pref.equals(mXcapPassword)) {
                mXcapPassword.setSummary(starify(newValue.toString()));
            } else if (pref.equals(mImsAuthMode)) {
                int authModeIndex = mImsAuthMode.findIndexOfValue(newValue.toString());
                mImsAuthMode.setValueIndex(authModeIndex);
                mImsAuthMode.setSummary(mImsAuthMode.getEntry());
            } else if (pref.equals(mLocalBreakout)) {
                int breakoutIndex = mLocalBreakout.findIndexOfValue(newValue.toString());
                mLocalBreakout.setValueIndex(breakoutIndex);
                mLocalBreakout.setSummary(mLocalBreakout.getEntry());
            } else {
                pref.setSummary(checkNull(newValue.toString()));
            }
        }

        // always let the preference setting proceed.
        return true;
    }
}
