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


import android.app.Activity;
import android.app.TabActivity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Window;
import android.widget.TabHost;
import android.widget.TabWidget;
import android.widget.TextView;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.TelephonyConstants;

/**
 * The dialer activity that has one tab with the virtual 12key
 * dialer, a tab with recent calls in it, a tab with the contacts and
 * a tab with the favorite. This is the container and the tabs are
 * embedded using intents.
 * The dialer tab's title is 'phone', a more common name (see strings.xml).
 */
public class CallFeaturesSettingTab extends TabActivity implements TabHost.OnTabChangeListener {
    private static final String LOG_TAG = "CallFeaturesSettingTab";
    private static final boolean DBG = false;

    private static final int TAB_INDEX_SIM_A = 0;
    private static final int TAB_INDEX_SIM_B = 1;

    private static final String PREF_LAST_MANUALLY_SELECTED_TAB = "last_call_settings_tab";
    private static final int PREF_LAST_MANUALLY_SELECTED_TAB_DEFAULT = TAB_INDEX_SIM_A;


    private static TabHost mTabHost;
    /**
     * The index of the tab that has last been manually selected (the user clicked on a tab).
     * This value does not keep track of programmatically set Tabs (e.g. Call Log after a Call)
     */
    private int mLastManuallySelectedTab;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        final Intent intent = getIntent();

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dual_sim_tab);

        mTabHost = getTabHost();
        mTabHost.setOnTabChangedListener(this);

        // Setup the tabs
        setupTab(0);
        setupTab(1);

        setupCommonTab();

        setTabColor();
        setCurrentTab(intent);
    }

    void setCurrentTab(Intent intent) {
        if (intent.hasExtra(DualPhoneController.EXTRA_PRIMARY_PHONE) == false) {
            restoreLastTab();
        } else {
            boolean primary = intent.getBooleanExtra(DualPhoneController.EXTRA_PRIMARY_PHONE, true);
            if (DBG) log("primary phone: " + primary);
            if (DualPhoneController.isPrimaryOnSim1()) {
                mTabHost.setCurrentTab(primary ? 0 : 1);
            } else {
                mTabHost.setCurrentTab(primary ? 1 : 0);
            }
        }
    }

    void enableUi() {
        for (int i = 0;i < 2; i++) {
            boolean enabled = isSimReady(i);
            mTabHost.getTabWidget().getChildTabViewAt(i).setEnabled(enabled);
        }
    }

    static int getCurrentSimSlot() {
        if (DBG) log("get currentSimSlot: " + mTabHost.getCurrentTab());
        return mTabHost.getCurrentTab();
    }

    void restoreLastTab() {
        // Load the last manually loaded tab
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        mLastManuallySelectedTab = prefs.getInt(PREF_LAST_MANUALLY_SELECTED_TAB,
                PREF_LAST_MANUALLY_SELECTED_TAB_DEFAULT);
        if (DBG) log("restoreLastTab, mLastManuallySelectedTab: " + mLastManuallySelectedTab);
        mTabHost.setCurrentTab(mLastManuallySelectedTab);
    }

    @Override
    protected void onPause() {
        super.onPause();

        final int currentTabIndex = mTabHost.getCurrentTab();
        final SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(this).edit();
        editor.putInt(PREF_LAST_MANUALLY_SELECTED_TAB, mLastManuallySelectedTab);

        editor.commit();
    }

    boolean isSimReady(int slot) {
        Phone phone = PhoneGlobals.getInstance().getPhoneBySlot(slot);
        return phone.getIccCard().getState() == IccCardConstants.State.READY;
    }

    private void setupTab(int slot) {
        Intent intent = new Intent("android.intent.action.MAIN");
        intent.putExtra(TelephonyConstants.EXTRA_SLOT, slot);
        intent.setClass(this, CallFeaturesSetting.class);

        String title = slot == 0 ? getString(R.string.tab_1_title)
                                 : getString(R.string.tab_2_title);
        final int iconId = slot == 0 ? R.drawable.ic_tab_sim_a
                                     : R.drawable.ic_tab_sim_b;

        mTabHost.addTab(mTabHost.newTabSpec("SIM " + slot)
                .setIndicator(title,
                        getResources().getDrawable(iconId))
                .setContent(intent));
    }

    private void setupCommonTab() {
        Intent intent = new Intent("android.intent.action.MAIN");
        intent.setClass(this, CommonCallSetting.class);

        mTabHost.addTab(mTabHost.newTabSpec("Common")
                .setIndicator(getString(R.string.common),
                              getResources().getDrawable(R.drawable.ic_tab_sim_a))
                .setContent(intent));
    }

    private void setTabColor() {
        TabWidget tabWidget = mTabHost.getTabWidget();
        for (int i = 0; i < tabWidget.getChildCount(); i++) {
            TextView tv = ((TextView)tabWidget.getChildAt(i).findViewById(android.R.id.title));
            if (i == 0) {
                tv.setTextColor(TelephonyConstants.DSDS_TEXT_COLOR_SLOT_1);
            } else if (i == 1) {
                tv.setTextColor(TelephonyConstants.DSDS_TEXT_COLOR_SLOT_2);
            }
        }
    }

    @Override
    public void onNewIntent(Intent newIntent) {
        log("onNewIntent: intent = " + newIntent);
        setCurrentTab(newIntent);
    }

    /** {@inheritDoc} */
    public void onTabChanged(String tabId) {
        // Because we're using Activities as our tab children, we trigger
        // onWindowFocusChanged() to let them know when they're active.  This may
        // seem to duplicate the purpose of onResume(), but it's needed because
        // onResume() can't reliably check if a keyguard is active.
        Activity activity = getLocalActivityManager().getActivity(tabId);
        if (activity != null) {
            activity.onWindowFocusChanged(true);
        }

        // Remember this tab index. This function is also called, if the tab is set automatically
        // in which case the setter (setCurrentTab) has to set this to its old value afterwards
        mLastManuallySelectedTab = mTabHost.getCurrentTab();
        if (DBG) log("onTabChanged, new tab: " + mLastManuallySelectedTab);
    }

    /**
     * Finish current Activity and go up to the top level Settings ({@link CallFeaturesSettingTab}).
     * This is useful for implementing "HomeAsUp" capability for second-level Settings.
     */
    public static void goUpToTopLevelSetting(Activity activity) {
        Intent intent = new Intent(activity, CallFeaturesSettingTab.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        activity.startActivity(intent);
        activity.finish();
    }

    private static void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}
