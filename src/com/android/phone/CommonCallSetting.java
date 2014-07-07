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

import com.android.phone.sip.SipSharedPreferences;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.media.RingtoneManager;
import android.net.Uri;
import android.net.sip.SipManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Vibrator;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;

/**
 *
 * Note that this activity is part of the package com.android.phone, even
 * though you reach it from the "Phone" app (i.e. DialtactsActivity) which
 * is from the package com.android.contacts.
 */
public class CommonCallSetting extends PreferenceActivity
        implements Preference.OnPreferenceChangeListener {

    // debug data
    private static final String LOG_TAG = "CommonCallSetting";
    private static final boolean DBG = (PhoneGlobals.DBG_LEVEL >= 2);

    private static final String BUTTON_RINGTONE_KEY    = "button_ringtone_key";
    private static final String BUTTON_VIBRATE_ON_RING = "button_vibrate_on_ring";
    private static final String BUTTON_PLAY_DTMF_TONE  = "button_play_dtmf_tone";
    private static final String BUTTON_DTMF_KEY        = "button_dtmf_settings";
    private static final String BUTTON_SIP_CALL_OPTIONS =
            "sip_call_options_key";
    private static final String BUTTON_SIP_CALL_OPTIONS_WIFI_ONLY =
            "sip_call_options_wifi_only_key";
    private static final String SIP_SETTINGS_CATEGORY_KEY =
            "sip_settings_category_key";

    private SipManager mSipManager;

    private ListPreference mButtonSipCallOptions;
    private SipSharedPreferences mSipSharedPreferences;

    private Runnable mRingtoneLookupRunnable;
    private final Handler mRingtoneLookupComplete = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MSG_UPDATE_RINGTONE_SUMMARY:
                mRingtonePreference.setSummary((CharSequence) msg.obj);
                break;
            }
        }
    };

    private static final int MSG_UPDATE_RINGTONE_SUMMARY = 1;

    // Dtmf tone types
    static final int DTMF_TONE_TYPE_NORMAL = 0;
    static final int DTMF_TONE_TYPE_LONG   = 1;

    private Preference mRingtonePreference;
    private CheckBoxPreference mVibrateWhenRinging;
    /** Whether dialpad plays DTMF tone or not. */
    private CheckBoxPreference mPlayDtmfTone;
    private ListPreference mButtonDTMF;

    // Click listener for all toggle events
    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        if (preference == mPlayDtmfTone) {
            Settings.System.putInt(getContentResolver(), Settings.System.DTMF_TONE_WHEN_DIALING,
                    mPlayDtmfTone.isChecked() ? 1 : 0);
        } else if (preference == mButtonDTMF) {
            return true;
        }
        return false;
    }

    /**
     * Implemented to support onPreferenceChangeListener to look for preference
     * changes.
     *
     * @param preference is the preference to be changed
     * @param objValue should be the value of the selection, NOT its localized
     * display value.
     */
    public boolean onPreferenceChange(Preference preference, Object objValue) {
        if (preference == mButtonSipCallOptions) {
            handleSipCallOptionsChange(objValue);
        } else if (preference == mVibrateWhenRinging) {
            boolean doVibrate = (Boolean) objValue;
            Settings.System.putInt(getApplicationContext().getContentResolver(),
                    Settings.System.VIBRATE_WHEN_RINGING, doVibrate ? 1 : 0);
        } else if (preference == mButtonDTMF) {
            int index = mButtonDTMF.findIndexOfValue((String) objValue);
            Settings.System.putInt(getApplicationContext().getContentResolver(),
                    Settings.System.DTMF_TONE_TYPE_WHEN_DIALING, index);
        } else if (preference == mButtonDTMF) {
            int index = mButtonDTMF.findIndexOfValue((String) objValue);
            Settings.System.putInt(getApplicationContext().getContentResolver(),
                    Settings.System.DTMF_TONE_TYPE_WHEN_DIALING, index);
        }
        // always let the preference setting proceed.
        return true;
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        if (DBG) log("Creating activity");

        addPreferencesFromResource(R.xml.dual_call_feature_setting);

        PreferenceScreen prefSet = getPreferenceScreen();
        mRingtonePreference = findPreference(BUTTON_RINGTONE_KEY);
        mVibrateWhenRinging = (CheckBoxPreference) findPreference(BUTTON_VIBRATE_ON_RING);
        mPlayDtmfTone = (CheckBoxPreference) findPreference(BUTTON_PLAY_DTMF_TONE);
        mButtonDTMF = (ListPreference) findPreference(BUTTON_DTMF_KEY);

        if (PhoneUtils.isVoipSupported()) {
            createSipCallSettings();
        }

        if (mVibrateWhenRinging != null) {
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                mVibrateWhenRinging.setOnPreferenceChangeListener(this);
            } else {
                prefSet.removePreference(mVibrateWhenRinging);
                mVibrateWhenRinging = null;
            }
        }

        if (mPlayDtmfTone != null) {
            mPlayDtmfTone.setChecked(Settings.System.getInt(getContentResolver(),
                    Settings.System.DTMF_TONE_WHEN_DIALING, 1) != 0);
        }

        if (mButtonDTMF != null) {
            if (getResources().getBoolean(R.bool.dtmf_type_enabled)) {
                mButtonDTMF.setOnPreferenceChangeListener(this);
            } else {
                prefSet.removePreference(mButtonDTMF);
                mButtonDTMF = null;
            }
        }

        mRingtoneLookupRunnable = new Runnable() {
            @Override
            public void run() {
                if (mRingtonePreference != null) {
                    updateRingtoneName(RingtoneManager.TYPE_RINGTONE, mRingtonePreference,
                            MSG_UPDATE_RINGTONE_SUMMARY);
                }
            }
        };

    }

    private void createSipCallSettings() {
        // Add Internet call settings.
        mSipManager = SipManager.newInstance(this);
        mSipSharedPreferences = new SipSharedPreferences(this);
        addPreferencesFromResource(R.xml.sip_settings_category);
        mButtonSipCallOptions = getSipCallOptionPreference();
        mButtonSipCallOptions.setOnPreferenceChangeListener(this);
        mButtonSipCallOptions.setValueIndex(
                mButtonSipCallOptions.findIndexOfValue(
                        mSipSharedPreferences.getSipCallOption()));
        mButtonSipCallOptions.setSummary(mButtonSipCallOptions.getEntry());
    }

    // Gets the call options for SIP depending on whether SIP is allowed only
    // on Wi-Fi only; also make the other options preference invisible.
    private ListPreference getSipCallOptionPreference() {
        ListPreference wifiAnd3G = (ListPreference)
                findPreference(BUTTON_SIP_CALL_OPTIONS);
        ListPreference wifiOnly = (ListPreference)
                findPreference(BUTTON_SIP_CALL_OPTIONS_WIFI_ONLY);
        PreferenceGroup sipSettings = (PreferenceGroup)
                findPreference(SIP_SETTINGS_CATEGORY_KEY);
        if (SipManager.isSipWifiOnly(this)) {
            sipSettings.removePreference(wifiAnd3G);
            return wifiOnly;
        } else {
            sipSettings.removePreference(wifiOnly);
            return wifiAnd3G;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mVibrateWhenRinging != null) {
            mVibrateWhenRinging.setChecked(getVibrateWhenRinging(this));
        }

        if (mButtonDTMF != null) {
            int dtmf = Settings.System.getInt(getContentResolver(),
                    Settings.System.DTMF_TONE_TYPE_WHEN_DIALING, DTMF_TONE_TYPE_NORMAL);
            mButtonDTMF.setValueIndex(dtmf);
        }

        lookupRingtoneName();
    }

    private void handleSipCallOptionsChange(Object objValue) {
        String option = objValue.toString();
        mSipSharedPreferences.setSipCallOption(option);
        mButtonSipCallOptions.setValueIndex(
                mButtonSipCallOptions.findIndexOfValue(option));
        mButtonSipCallOptions.setSummary(mButtonSipCallOptions.getEntry());
    }

    /**
     * Obtain the setting for "vibrate when ringing" setting.
     *
     * Watch out: if the setting is missing in the device, this will try obtaining the old
     * "vibrate on ring" setting from AudioManager, and save the previous setting to the new one.
     */
    public static boolean getVibrateWhenRinging(Context context) {
        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) {
            return false;
        }
        return Settings.System.getInt(context.getContentResolver(),
                Settings.System.VIBRATE_WHEN_RINGING, 0) != 0;
    }

    /**
     * Lookups ringtone name asynchronously and updates the relevant Preference.
     */
    private void lookupRingtoneName() {
        new Thread(mRingtoneLookupRunnable).start();
    }

    /**
     * Updates ringtone name. This is a method copied from com.android.settings.SoundSettings
     *
     * @see com.android.settings.SoundSettings
     */
    private void updateRingtoneName(int type, Preference preference, int msg) {
        if (preference == null) return;
        Uri ringtoneUri = RingtoneManager.getActualDefaultRingtoneUri(this, type);
        CharSequence summary = getString(com.android.internal.R.string.ringtone_unknown);
        // Is it a silent ringtone?
        if (ringtoneUri == null) {
            summary = getString(com.android.internal.R.string.ringtone_silent);
        } else {
            // Fetch the ringtone title from the media provider
            try {
                Cursor cursor = getContentResolver().query(ringtoneUri,
                        new String[] { MediaStore.Audio.Media.TITLE }, null, null, null);
                if (cursor != null) {
                    if (cursor.moveToFirst()) {
                        summary = cursor.getString(0);
                    }
                    cursor.close();
                }
            } catch (SQLiteException sqle) {
                // Unknown title for the ringtone
            }
        }
        mRingtoneLookupComplete.sendMessage(mRingtoneLookupComplete.obtainMessage(msg, summary));
    }

    private static void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}
