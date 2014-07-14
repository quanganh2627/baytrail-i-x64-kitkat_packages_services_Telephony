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

import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.TelephonyConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyIntents2;
/**
 * This controller is used to manager correct state for dual sims.
 * It stores active sim id. Since primary sim may be sim 1 or sim 2.
 * It has to convert primary sim id to correct sim id so that the
 * correct phone could be provided.
 */

public class DualPhoneController {

    private static final String LOG_TAG = "DualPhoneController";
    private static final boolean DBG = false;

    /** The singleton DualPhoneController instance. */
    private static DualPhoneController sInstance;

    private PhoneGlobals mApp;
    private CallManager mCM;
    private CallManager mCM2;

    public static final String EXTRA_DSDS_CALL_FROM_SLOT_2 =
                                     TelephonyConstants.EXTRA_DSDS_CALL_FROM_SLOT_2;
    public static final String EXTRA_PRIMARY_PHONE = "com.imc.phone.extra.PRIMARY_PHONE";

    public static final int DISABLED = 0;
    public static final int ENABLED  = 1;

    public static final int ID_PRIMARY_SIM = -1;
    public static final int ID_SIM_1       = TelephonyConstants.DSDS_SLOT_1_ID;
    public static final int ID_SIM_2       = TelephonyConstants.DSDS_SLOT_2_ID;

    private static int mPrimaryId;
    private static int mDataSimId;
    private int mActiveSimId = ID_SIM_1;

    /**
     * Private constructor (this is a singleton).
     * @see init()
     */
    private DualPhoneController(PhoneGlobals app) {
        mApp = app;
        mCM = app.mCM;
        mCM2 = app.mCM2;

        mPrimaryId = Settings.Global.getInt(PhoneGlobals.getInstance().getContentResolver(),
                Settings.Global.MOBILE_DATA_SIM,
                TelephonyConstants.DSDS_SLOT_1_ID);
    }

    /**
     * Initialize the singleton DSDSPhoneState instance.
     *
     * This is only done once, at startup, from PhoneApp.onCreate().
     * From then on, the NotificationMgr instance is available via the
     * PhoneApp's public "notificationMgr" field, which is why there's no
     * getInstance() method here.
     */
    /* package */ static DualPhoneController init(PhoneGlobals app) {
        synchronized (NotificationMgr.class) {
            if (sInstance == null) {
                sInstance = new DualPhoneController(app);
            } else {
                Log.wtf(LOG_TAG, "init() called multiple times!  sInstance = " + sInstance);
            }
            return sInstance;
        }
    }

    static DualPhoneController getInstance() {
        return sInstance;
    }

    void setActiveSimId(boolean primarySim) {
        if (primarySim) {
            mActiveSimId = isPrimaryOnSim1() ? ID_SIM_1 : ID_SIM_2;
        } else {
            mActiveSimId = isPrimaryOnSim1() ? ID_SIM_2 : ID_SIM_1;
        }
        PhoneGlobals.getInstance().setActiveSimId(mActiveSimId);
    }

    void setActiveSimId(Phone p) {
        if (isPrimaryOnSim1()) {
            mActiveSimId = p.getPhoneName().equals("GSM") ?
                ID_SIM_1 : ID_SIM_2;
        } else {
            mActiveSimId = p.getPhoneName().equals("GSM") ?
                ID_SIM_2 : ID_SIM_1;
        }
        PhoneGlobals.getInstance().setActiveSimId(mActiveSimId);
    }

    int getActiveSimId() {
        return mActiveSimId;
    }

    CallManager getActiveCM() {
        if (isPrimaryOnSim1()) {
            return mActiveSimId == ID_SIM_1 ? mApp.mCM : mApp.mCM2;
        }
        return mActiveSimId == ID_SIM_1 ? mApp.mCM2 : mApp.mCM;
    }

    Ringer getActiveRinger() {
        if (isPrimaryOnSim1()) {
            return mActiveSimId == ID_SIM_1 ? mApp.getRinger() : mApp.get2ndRinger();
        }
        return mActiveSimId == ID_SIM_1 ? mApp.get2ndRinger() : mApp.getRinger();
    }

    CallNotifier getActiveNotifier() {
        if (isPrimaryOnSim1()) {
            return mActiveSimId == ID_SIM_1  ? mApp.notifier : mApp.notifier2;
        }
        return mActiveSimId == ID_SIM_1  ? mApp.notifier2 : mApp.notifier;
    }

    Phone getPhoneBySimId(int simId) {
        if (isPrimaryOnSim1()) {
            return simId == ID_SIM_1 ? mApp.phone : mApp.phone2;
        } else {
            return simId == ID_SIM_1 ? mApp.phone2 : mApp.phone;
        }
    }

    CallManager getCmByPhone(Phone phone) {
        return isPrimaryPhone(phone) ? mApp.mCM : mApp.mCM2;
    }

    Phone getActivePhone() {
        if (isPrimaryOnSim1()) {
            return mActiveSimId == ID_SIM_1 ? mApp.phone : mApp.phone2;
        }
        return mActiveSimId == ID_SIM_1 ? mApp.phone2 : mApp.phone;
    }

    CallModeler getActiveCallModeler() {
        if (isPrimaryOnSim1()) {
            return mActiveSimId == ID_SIM_1 ? mApp.getCallModeler() : mApp.get2ndCallModeler();
        }
        return mActiveSimId == ID_SIM_1 ? mApp.get2ndCallModeler() : mApp.getCallModeler();
    }

    DTMFTonePlayer getActiveDTMFTonePlayer() {
        if (isPrimaryOnSim1()) {
            return mActiveSimId == ID_SIM_1 ? mApp.getDTMFTonePlayer() : mApp.get2ndDTMFTonePlayer();
        }
        return mActiveSimId == ID_SIM_1 ? mApp.get2ndDTMFTonePlayer() : mApp.getDTMFTonePlayer();
    }

    static boolean isPrimarySimId(int simId) {
        return simId == mPrimaryId;
    }

    static int getPrimarySimId() {
        return mPrimaryId;
    }

    static int getDataSimId() {
        return mDataSimId;
    }

    static boolean isPrimaryOnSim1() {
        return mPrimaryId == TelephonyConstants.DSDS_SLOT_1_ID;
    }

    /**
     *
     *   Find out the sim that the intent specifies.
     *   If there is no extra, returns primary sim.
     *
     */
    static int findSimId(Intent intent) {
        if (intent.hasExtra(EXTRA_DSDS_CALL_FROM_SLOT_2)) {
            boolean usingSlot2 = intent.getBooleanExtra(EXTRA_DSDS_CALL_FROM_SLOT_2, false);
            if (DBG) Log.d(LOG_TAG, "usingSlot2: " + usingSlot2);
            return usingSlot2 ? ID_SIM_2 : ID_SIM_1;
        }
        return ID_PRIMARY_SIM;
    }

    static boolean usingSimA(Intent intent) {
        switch (findSimId(intent)) {
            case ID_SIM_1:
                return true;
            case ID_SIM_2:
                return false;
            default:
                return isPrimaryOnSim1() ? true : false;
        }
    }

    static boolean usingPrimaryPhone(Intent intent) {
        if (!TelephonyConstants.IS_DSDS) {
            return true;
        }
        String sipPhoneUri = intent.getStringExtra(
                OutgoingCallBroadcaster.EXTRA_SIP_PHONE_URI);
        if (sipPhoneUri != null) {
            return true;
        }

        switch (findSimId(intent)) {
            case ID_SIM_1:
                return isPrimaryOnSim1() ? true : false;
            case ID_SIM_2:
                return isPrimaryOnSim1() ? false : true;
            default:
                return true;
        }
    }

    /**
     *
     *  Check out if the phone is primary phone or not.
     *
     */
    static boolean isPrimaryPhone(Phone p) {
        // In case there is phone which has no name.
        return !p.getPhoneName().equals("GSM2");
    }

    /**
     *
     *   Check out if the phone is listenting to SIM card on slot 1.
     *
     */
    static boolean isSim1Phone(Phone p) {
        return isPrimaryOnSim1() ? p == PhoneGlobals.getInstance().phone
                                 : p == PhoneGlobals.getInstance().phone2;
    }

    static Uri getFdnURI(int slot) {
        // TODO  may use ID_SIM_1 later
        if (DualPhoneController.isPrimaryOnSim1()) {
            return slot == 0 ? Uri.parse("content://icc/fdn") :
                    Uri.parse("content://icc2/fdn");
        } else {
            return slot == 0 ? Uri.parse("content://icc2/fdn") :
                    Uri.parse("content://icc/fdn");
        }
    }

    static Uri getAdnURI(int slot) {
        // TODO  may use ID_SIM_1 later
        if (DualPhoneController.isPrimaryOnSim1()) {
            return slot == 0 ? Uri.parse("content://icc/adn") :
                    Uri.parse("content://icc2/adn");
        } else {
            return slot == 0 ? Uri.parse("content://icc2/adn") :
                    Uri.parse("content://icc/adn");
        }
    }

    static boolean simModeEnabled(int slotId) {
        if (slotId == 0) {
            int sim1Enabled = Settings.Global.getInt(
                    PhoneGlobals.getInstance().getContentResolver(),
                    Settings.Global.DUAL_SLOT_1_ENABLED, ENABLED);

            return sim1Enabled == ENABLED;
        }

        int sim2Enabled = Settings.Global.getInt(
                PhoneGlobals.getInstance().getContentResolver(),
                Settings.Global.DUAL_SLOT_2_ENABLED, ENABLED);

        return sim2Enabled == ENABLED;
    }

    static void broadcastSimWidgetUpdateIntent() {
         Intent intent = new Intent(TelephonyConstants.ACTION_SIM_WIDGET_GENERIC_UPDATE);
         PhoneGlobals.getInstance().getApplicationContext().sendBroadcast(intent);
    }

    static void updateSlotEnabledSettings(boolean enabled, int slot) {
        final String prop = slot == 0 ? Settings.Global.DUAL_SLOT_1_ENABLED
                : Settings.Global.DUAL_SLOT_2_ENABLED;
        Settings.Global.putInt(PhoneGlobals.getInstance().getContentResolver(),
                prop, enabled ? ENABLED : DISABLED);
    }

    static int getSlotByIntent(Intent intent) {
        if (!intent.hasExtra(EXTRA_DSDS_CALL_FROM_SLOT_2)) {
            return mPrimaryId;
        }
        boolean usingSlot2 = intent.getBooleanExtra(EXTRA_DSDS_CALL_FROM_SLOT_2, false);
        if (DBG) Log.d(LOG_TAG, "usingSlot2: " + usingSlot2);

        return usingSlot2 ? ID_SIM_2 : ID_SIM_1;
    }

    static int getSlotId(boolean isPrimary) {
        if (isPrimaryOnSim1()) {
            return isPrimary ? ID_SIM_1 : ID_SIM_2;
        }
        return isPrimary ? ID_SIM_2 : ID_SIM_1;
    }

    static void updatePrimarySim() {
        mPrimaryId = TelephonyManager.getPrimarySim();
    }

    static void updateDataSim() {
        mDataSimId = Settings.Global.getInt(PhoneGlobals.getInstance().getContentResolver(),
                Settings.Global.MOBILE_DATA_SIM, TelephonyConstants.DSDS_SLOT_1_ID);
    }

    static int getSecondarySimId() {
        return 1 - mDataSimId;
    }

    boolean isSimReallyAbsent(int slot) {
        TelephonyManager tm = TelephonyManager.getTmBySlot(slot);
        return tm.isSimAbsent() && !tm.isSimOff(slot);
    }

    boolean isSecondarySimOnly() {
        //int simId = getPrimarySimId();
        int simId = mDataSimId;
        return isSimReallyAbsent(simId) && !TelephonyManager.getTmBySlot(1 - simId).isSimAbsent();
    }

    static boolean isDataFollowSingleSim() {
        boolean ret = android.provider.Settings.Global.getInt(PhoneGlobals.getInstance().getContentResolver(),
                android.provider.Settings.Global.DATA_FOLLOW_SINGLE_SIM, 0) == 1;
        return ret;
    }

    static private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }

    static boolean isSimStateChanged(String action, int slotId) {
        if (!TelephonyConstants.IS_DSDS) {
            return TelephonyIntents.ACTION_SIM_STATE_CHANGED.equals(action);
        }

        if (TelephonyIntents.ACTION_SIM_STATE_CHANGED.equals(action)) {
            return isPrimarySimId(slotId);
        }

        if (TelephonyIntents2.ACTION_SIM_STATE_CHANGED.equals(action)) {
            return !isPrimarySimId(slotId);
        }
        return false;
    }

    static void notifySimActivity(int slot) {
        if (DBG) log("notifySimActivity, slot" + slot);

        Intent intent = new Intent(TelephonyConstants.INTENT_SIM_ACTIVITY);
        intent.putExtra(TelephonyConstants.EXTRA_SLOT, slot);
        PhoneGlobals.getInstance().getApplicationContext().sendBroadcast(intent);
    }
};
