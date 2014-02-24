package com.android.phone;

import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.PhoneConstants;
import com.android.phone.CallBarringPreference.CallBarringPreferenceType;

import java.util.ArrayList;
import java.util.List;

public class CallBarringOptions extends TimeConsumingPreferenceActivity
    implements EditPinPreference.OnPinEnteredListener,
               Preference.OnPreferenceClickListener {
    private static final String LOG_TAG = "CallBarringOptions";
    public static final int EXCEPTION_UNAVAILABLE = 1000;
    private static final boolean VDBG = Log.isLoggable(LOG_TAG, Log.VERBOSE);

    private static final String BUTTON_CBO_KEY = "button_barring_outgoing_key";
    private static final String BUTTON_CBI_KEY = "button_barring_incoming_key";
    private static final String BUTTON_PWD_KEY = "button_pwd_key";
    private static final String BUTTON_ACR_KEY = "button_acr_key";

    private CallBarringPreference mButtonCBO;
    private CallBarringPreference mButtonCBI;
    private EditPinPreference mButtonPWD;
    private ACRCheckBoxPreference mButtonACR = null;

    private final List<CallBarringPreference> mPreferences =
            new ArrayList<CallBarringPreference>();

    private boolean mFirstResume;
    private CallBarringPreference mPre;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        addPreferencesFromResource(R.xml.callbarring_options);

        PreferenceScreen prefSet = getPreferenceScreen();
        if (prefSet != null) {
            mButtonCBO = (CallBarringPreference) prefSet.findPreference(BUTTON_CBO_KEY);
            mButtonCBI = (CallBarringPreference) prefSet.findPreference(BUTTON_CBI_KEY);
            mButtonPWD = (EditPinPreference) prefSet.findPreference(BUTTON_PWD_KEY);
            mButtonACR = (ACRCheckBoxPreference) prefSet.findPreference(BUTTON_ACR_KEY);
        }

        // we wait to do the initialization until onResume so that the
        // TimeConsumingPreferenceActivity dialog can display as it
        // relies on onResume / onPause to maintain its foreground state.

        mFirstResume = true;

        mPreferences.add(mButtonCBO);
        mPreferences.add(mButtonCBI);

        if (mButtonCBO != null) {
            mButtonCBO.setPreferenceType(CallBarringPreferenceType.CB_OUTGOING);
        }
        if (mButtonCBI != null) {
            mButtonCBI.setPreferenceType(CallBarringPreferenceType.CB_INCOMING);
        }
        if (mButtonPWD != null) {
            mButtonPWD.setOnPreferenceClickListener(this);
        }

        if (CallManager.getInstance().getDefaultPhone().getPhoneType()
                != PhoneConstants.PHONE_TYPE_IMS) {
            Log.v(LOG_TAG, "Removing ACR menu as Phone type is not ImsPhone");
            if (prefSet != null && mButtonACR != null) {
                prefSet.removePreference(mButtonACR);
            }
            mButtonACR = null;
        } else {
            /* TODO : remove this else once the ACR MMI code is supported by MMTel */
            mButtonACR = null;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mFirstResume) {
            mFirstResume = false;

            /* Read the status for in/out calls barring */
            mPreferences.get(0).init(this, false);

            if (mButtonACR != null) {
                mButtonACR.init(this, false);
            }
        }
    }

    @Override
    public void onStarted(Preference preference, boolean reading) {
        if (preference instanceof CallBarringPreference) {
            if (VDBG) Log.d(LOG_TAG, "onStarted, reading:" + reading);
            mButtonPWD.setOnPinEnteredListener(this);
            if (!reading && CallBarringPreference.isPendingCommandsNeedPin()) {
                mButtonPWD.showPinDialog();
                mButtonPWD.getEditText().selectAll();
                // cache the preference reference for next caller
                mPre = (CallBarringPreference) preference;
                return;
            }
        }
        super.onStarted(preference, reading);
    }

    public void onPinEntered(EditPinPreference preference, boolean positiveResult) {
        String pin = preference.getEditText().getText().toString();
        if (VDBG) Log.d(LOG_TAG, "onPinEntered get pin : " + pin);
        if (positiveResult) {
            if (isValidPin(pin)) {
                CallBarringPreference.executePendingCommands(pin);
            } else {
                onStarted(mPre, false);
            }
            mPre = null;
        } else {
            CallBarringPreference.clearPendingCommand();
        }
        preference.setText("");
    }

    private boolean isValidPin(String pin) {
        boolean isPinValid = false;
        if (null != pin) {
            int len = pin.length();
            if (len >= 4 && len <= 8 && TextUtils.isDigitsOnly(pin)) {
                isPinValid = true;
            }
        }
        return isPinValid;
    }

    public boolean onPreferenceClick(Preference preference) {
        if (preference instanceof  EditPinPreference) {
            if (VDBG) Log.d(LOG_TAG, "EditPinPreference clicked");
            mPre = mButtonCBO;
            /* Clear the barring for outgoing and incoming calls */
            if (mButtonCBO != null) {
                mButtonCBO.addPendingCommand(0);
            }
            if (mButtonCBI != null) {
                mButtonCBI.addPendingCommand(0);
            }
        }
        return false;
    }

    @Override
    public void onFinished(Preference preference, boolean reading) {
        if (preference instanceof CallBarringPreference) {
            if (!reading) {
                CallBarringPreference.executePendingCommands();
            }

            for (CallBarringPreference callBarringPref:mPreferences) {
                // Update index value with value read from network
                callBarringPref.init(this, true);
                // Show the list button
                callBarringPref.setEnabled(true);
            }
            mButtonPWD.setEnabled(true);
            if (VDBG) Log.d(LOG_TAG, "onfinished");

        } else if (preference instanceof ACRCheckBoxPreference && mButtonACR != null) {
            mButtonACR.init(this, false);
            mButtonACR.setEnabled(true);
        }
        super.onFinished(preference, reading);
    }

    @Override
    public void onError(Preference preference, int error) {
        // disable items
        if (error == EXCEPTION_UNAVAILABLE) {
            super.onError(preference, EXCEPTION_ERROR);
        } else {
            super.onError(preference, error);
        }
    }
}
