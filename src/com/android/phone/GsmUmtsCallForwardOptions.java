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

import com.android.internal.telephony.CallForwardInfo;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.TelephonyConstants;

import android.app.ActionBar;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.util.Log;
import android.view.MenuItem;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.TelephonyConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyIntents2;

import java.util.ArrayList;


public class GsmUmtsCallForwardOptions extends TimeConsumingPreferenceActivity {
    private static final String LOG_TAG = "GsmUmtsCallForwardOptions";
    private final boolean DBG = (PhoneGlobals.DBG_LEVEL >= 2);

    private static final String NUM_PROJECTION[] = {Phone.NUMBER};

    private static final String BUTTON_CFU_KEY   = "button_cfu_key";
    private static final String BUTTON_CFB_KEY   = "button_cfb_key";
    private static final String BUTTON_CFNRY_KEY = "button_cfnry_key";
    private static final String BUTTON_CFNRC_KEY = "button_cfnrc_key";

    private static final String KEY_TOGGLE = "toggle";
    private static final String KEY_STATUS = "status";
    private static final String KEY_NUMBER = "number";

    private CallForwardEditPreference mButtonCFU;
    private CallForwardEditPreference mButtonCFB;
    private CallForwardEditPreference mButtonCFNRy;
    private CallForwardEditPreference mButtonCFNRc;

    private final ArrayList<CallForwardEditPreference> mPreferences =
            new ArrayList<CallForwardEditPreference> ();
    private int mInitIndex= 0;

    private boolean mFirstResume;
    private Bundle mIcicle;

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

        addPreferencesFromResource(R.xml.callforward_options);

        PreferenceScreen prefSet = getPreferenceScreen();
        mButtonCFU   = (CallForwardEditPreference) prefSet.findPreference(BUTTON_CFU_KEY);
        mButtonCFB   = (CallForwardEditPreference) prefSet.findPreference(BUTTON_CFB_KEY);
        mButtonCFNRy = (CallForwardEditPreference) prefSet.findPreference(BUTTON_CFNRY_KEY);
        mButtonCFNRc = (CallForwardEditPreference) prefSet.findPreference(BUTTON_CFNRC_KEY);

        mButtonCFU.setParentActivity(this, mButtonCFU.reason);
        mButtonCFB.setParentActivity(this, mButtonCFB.reason);
        mButtonCFNRy.setParentActivity(this, mButtonCFNRy.reason);
        mButtonCFNRc.setParentActivity(this, mButtonCFNRc.reason);

        mPreferences.add(mButtonCFU);
        mPreferences.add(mButtonCFB);
        mPreferences.add(mButtonCFNRy);
        mPreferences.add(mButtonCFNRc);

        if (TelephonyConstants.IS_DSDS) {
            mIntentFilter = new IntentFilter(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
            mIntentFilter.addAction(TelephonyIntents2.ACTION_SIM_STATE_CHANGED);
        }

        // we wait to do the initialization until onResume so that the
        // TimeConsumingPreferenceActivity dialog can display as it
        // relies on onResume / onPause to maintain its foreground state.

        mFirstResume = true;
        mIcicle = icicle;

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            // android.R.id.home will be triggered in onOptionsItemSelected()
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (TelephonyConstants.IS_DSDS) {
            registerReceiver(mSimStateListener, mIntentFilter);
        }
        if (mFirstResume) {
            if (mIcicle == null) {
                if (DBG) Log.d(LOG_TAG, "start to init ");
                mPreferences.get(mInitIndex).init(this, false);
            } else {
                mInitIndex = mPreferences.size();

                for (CallForwardEditPreference pref : mPreferences) {
                    Bundle bundle = mIcicle.getParcelable(pref.getKey());
                    pref.setToggled(bundle.getBoolean(KEY_TOGGLE));
                    CallForwardInfo cf = new CallForwardInfo();
                    cf.number = bundle.getString(KEY_NUMBER);
                    cf.status = bundle.getInt(KEY_STATUS);
                    pref.handleCallForwardResult(cf);
                    pref.init(this, true);
                }
            }
            mFirstResume = false;
            mIcicle=null;
        }

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
        if (TelephonyConstants.IS_DSDS) {
            unregisterReceiver(mSimStateListener);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        for (CallForwardEditPreference pref : mPreferences) {
            Bundle bundle = new Bundle();
            bundle.putBoolean(KEY_TOGGLE, pref.isToggled());
            if (pref.callForwardInfo != null) {
                bundle.putString(KEY_NUMBER, pref.callForwardInfo.number);
                bundle.putInt(KEY_STATUS, pref.callForwardInfo.status);
            }
            outState.putParcelable(pref.getKey(), bundle);
        }
    }

    @Override
    protected void onRestoreInstanceState(Bundle inState) {
        super.onRestoreInstanceState(inState);

        for (CallForwardEditPreference pref : mPreferences) {
            Bundle bundle = inState.getParcelable(pref.getKey());
            pref.setToggled(bundle.getBoolean(KEY_TOGGLE));
            CallForwardInfo cf = new CallForwardInfo();
            cf.number = bundle.getString(KEY_NUMBER);
            cf.status = bundle.getInt(KEY_STATUS);
            pref.handleCallForwardResult(cf);
            pref.init(this, true);
        }
    }

    @Override
    public void onFinished(Preference preference, boolean reading) {
        if (mInitIndex < mPreferences.size()-1 && !isFinishing()) {
            mInitIndex++;
            mPreferences.get(mInitIndex).init(this, false);
        }

        super.onFinished(preference, reading);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (DBG) Log.d(LOG_TAG, "onActivityResult: done");
        if (resultCode != RESULT_OK) {
            if (DBG) Log.d(LOG_TAG, "onActivityResult: contact picker result not OK.");
            return;
        }
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(data.getData(),
                NUM_PROJECTION, null, null, null);
            if ((cursor == null) || (!cursor.moveToFirst())) {
                if (DBG) Log.d(LOG_TAG, "onActivityResult: bad contact data, no results found.");
                return;
            }

            switch (requestCode) {
                case CommandsInterface.CF_REASON_UNCONDITIONAL:
                    mButtonCFU.onPickActivityResult(cursor.getString(0));
                    break;
                case CommandsInterface.CF_REASON_BUSY:
                    mButtonCFB.onPickActivityResult(cursor.getString(0));
                    break;
                case CommandsInterface.CF_REASON_NO_REPLY:
                    mButtonCFNRy.onPickActivityResult(cursor.getString(0));
                    break;
                case CommandsInterface.CF_REASON_NOT_REACHABLE:
                    mButtonCFNRc.onPickActivityResult(cursor.getString(0));
                    break;
                default:
                    // TODO: may need exception here.
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
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
}
