/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.app.ActionBar;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.util.Log;
import android.view.MenuItem;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.TelephonyConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyIntents2;

import java.util.ArrayList;

public class GsmUmtsAdditionalCallOptions extends
        TimeConsumingPreferenceActivity {
    private static final String LOG_TAG = "GsmUmtsAdditionalCallOptions";
    private final boolean DBG = (PhoneGlobals.DBG_LEVEL >= 2);

    private static final String BUTTON_CLIR_KEY  = "button_clir_key";
    private static final String BUTTON_CW_KEY    = "button_cw_key";

    private CLIRListPreference mCLIRButton;
    private CallWaitingCheckBoxPreference mCWButton;

    private final ArrayList<Preference> mPreferences = new ArrayList<Preference>();
    private int mInitIndex= 0;

    private IntentFilter mIntentFilter;
    private final BroadcastReceiver mSimStateListener = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (DualPhoneController.isSimStateChanged(action,
                    TelephonyConstants.IS_DSDS ? CallFeaturesSettingTab.getCurrentSimSlot() : 0)) {
                boolean isSimOpAllowed = true;
                String stateExtra = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
                if (stateExtra != null
                        && (IccCardConstants.INTENT_VALUE_ICC_NOT_READY.equals(stateExtra)
                        || IccCardConstants.INTENT_VALUE_ICC_ABSENT.equals(stateExtra))) {

                    isSimOpAllowed = false;
                }

                PreferenceScreen screen = getPreferenceScreen();
                if (screen != null) {
                    int count = screen.getPreferenceCount();
                    for (int i = 0 ; i < count ; ++i) {
                       screen.getPreference(i).setEnabled(isSimOpAllowed);
                    }
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        addPreferencesFromResource(R.xml.gsm_umts_additional_options);

        PreferenceScreen prefSet = getPreferenceScreen();
        mCLIRButton = (CLIRListPreference) prefSet.findPreference(BUTTON_CLIR_KEY);
        mCWButton = (CallWaitingCheckBoxPreference) prefSet.findPreference(BUTTON_CW_KEY);

        mPreferences.add(mCLIRButton);
        mPreferences.add(mCWButton);

        mIntentFilter = new IntentFilter(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        if (TelephonyConstants.IS_DSDS) {
            mIntentFilter.addAction(TelephonyIntents2.ACTION_SIM_STATE_CHANGED);
        }

        if (icicle == null) {
            if (DBG) Log.d(LOG_TAG, "start to init ");
            mCLIRButton.init(this, false);
        } else {
            if (DBG) Log.d(LOG_TAG, "restore stored states");
            mInitIndex = mPreferences.size();
            mCLIRButton.init(this, true);
            mCWButton.init(this, true);
            int[] clirArray = icicle.getIntArray(mCLIRButton.getKey());
            if (clirArray != null) {
                if (DBG) Log.d(LOG_TAG, "onCreate:  clirArray[0]="
                        + clirArray[0] + ", clirArray[1]=" + clirArray[1]);
                mCLIRButton.handleGetCLIRResult(clirArray);
            } else {
                mCLIRButton.init(this, false);
            }
        }

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            // android.R.id.home will be triggered in onOptionsItemSelected()
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        if (mCLIRButton.clirArray != null) {
            outState.putIntArray(mCLIRButton.getKey(), mCLIRButton.clirArray);
        }
    }

    @Override
    public void onFinished(Preference preference, boolean reading) {
        if (mInitIndex < mPreferences.size()-1 && !isFinishing()) {
            mInitIndex++;
            Preference pref = mPreferences.get(mInitIndex);
            if (pref instanceof CallWaitingCheckBoxPreference) {
                ((CallWaitingCheckBoxPreference) pref).init(this, false);
            }
        }
        super.onFinished(preference, reading);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == android.R.id.home) {  // See ActionBar#setDisplayHomeAsUpEnabled()
            if (TelephonyConstants.IS_DSDS) {
                CallFeaturesSettingTab.goUpToTopLevelSetting(this);
            } else {
                CallFeaturesSetting.goUpToTopLevelSetting(this);
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onResume() {
        super.onResume();
        registerReceiver(mSimStateListener, mIntentFilter);
        if (TelephonyConstants.IS_DSDS) {

            ActionBar actionBar = getActionBar();
            if (actionBar != null) {
                final int resId = CallFeaturesSettingTab.getCurrentSimSlot() == 0?
                        R.drawable.ic_launcher_phone_sim1 : R.drawable.ic_launcher_phone_sim2;
                actionBar.setIcon(resId);
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
            unregisterReceiver(mSimStateListener);
    }

}
