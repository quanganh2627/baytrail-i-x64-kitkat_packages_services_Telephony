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

import android.content.ContentResolver;
import android.os.Handler;
import android.os.Message;
import android.os.AsyncResult;
import android.os.SystemProperties;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.TelephonyProperties;

import static com.android.internal.telephony.RILConstants.NETWORK_MODE_GSM_ONLY;
import static com.android.internal.telephony.RILConstants.NETWORK_MODE_WCDMA_PREF;
import static com.android.internal.telephony.OemHookConstants.SWAP_PS_FLAG_NORMAL;
import static com.android.internal.telephony.TelephonyConstants.ACTION_DATA_SIM_SWITCH;
import static com.android.internal.telephony.TelephonyConstants.DSDS_SLOT_1_ID;
import static com.android.internal.telephony.TelephonyConstants.DSDS_SLOT_2_ID;
import static com.android.internal.telephony.TelephonyConstants.ENABLED;

public class OnlyOne3gRatSwitcher extends Handler {
    private static final String     TAG = "OnlyOne3g";
    private static final boolean    DBG = false;

    private static final int RET_FAILURE = 0;
    private static final int RET_SUCC    = 1;

    private static final int EVENT_PS_SWAP_DONE = 1;

    private int mNext3gSlotId      = -1;
    private boolean mInProgress    = false;
    private boolean mUpdateSetting = false;

    private Message mResponseMsg;

    public OnlyOne3gRatSwitcher(int slotId, Message response) {
        mNext3gSlotId = slotId;
        mResponseMsg = response;
    }

    @Override
    public void handleMessage (Message msg) {
        AsyncResult ar;
        Message onComplete;

        switch (msg.what) {
            case EVENT_PS_SWAP_DONE:
                handlePsSwapDone(msg);
            break;
         }
    }

    public boolean startSwitch(boolean settingUpdate) {
        if (mInProgress == true) {
            if (DBG) log("Warning: rat is in swapping.");
            return false;
        }

        if (mNext3gSlotId != DSDS_SLOT_1_ID && mNext3gSlotId != DSDS_SLOT_2_ID) {
            return false;
        }

        if (DBG) log("start ps swap, switch to " + mNext3gSlotId + " update: " + settingUpdate);
        mInProgress = true;
        mUpdateSetting = settingUpdate;

        int cur3gId = mNext3gSlotId == DSDS_SLOT_2_ID ? DSDS_SLOT_1_ID : DSDS_SLOT_2_ID;

        TelephonyManager tm = TelephonyManager.getDefault();
        if (tm.isSimOff(cur3gId) == false) {
            Phone phone = PhoneGlobals.getInstance().getPhoneBySlot(cur3gId);
            ((PhoneProxy)phone)
                          .requestProtocolStackSwap(obtainMessage(EVENT_PS_SWAP_DONE), SWAP_PS_FLAG_NORMAL);
        } else if (tm.isSimOff(mNext3gSlotId) == false) {
            Phone phone = PhoneGlobals.getInstance().getPhoneBySlot(mNext3gSlotId);
            ((PhoneProxy)phone)
                          .requestProtocolStackSwap(obtainMessage(EVENT_PS_SWAP_DONE), SWAP_PS_FLAG_NORMAL);
        } else {
            if (DBG) log("UI has issue.");
            return false;
        }

        return true;
    }

    private void handlePsSwapDone(Message msg) {
        AsyncResult ar = (AsyncResult) msg.obj;

        if (ar.exception != null) {
            if (DBG) log("ps swap, exception=" + ar.exception);
            responseResult(RET_FAILURE);
        } else {
            if (DBG) log("ps swap done");
            responseResult(RET_SUCC);
        }
    }

    private void applyNewRatSetting() {
        PhoneGlobals app = PhoneGlobals.getInstance();

        if (mNext3gSlotId == DSDS_SLOT_1_ID) {
            Settings.Global.putInt(app.getContentResolver(),
                             Settings.Global.PREFERRED_NETWORK_MODE, NETWORK_MODE_WCDMA_PREF);
            Settings.Global.putInt(app.getContentResolver(),
                             Settings.Global.PREFERRED_NETWORK2_MODE, NETWORK_MODE_GSM_ONLY);
        } else {
            Settings.Global.putInt(app.getContentResolver(),
                             Settings.Global.PREFERRED_NETWORK_MODE, NETWORK_MODE_GSM_ONLY);
            Settings.Global.putInt(app.getContentResolver(),
                             Settings.Global.PREFERRED_NETWORK2_MODE, NETWORK_MODE_WCDMA_PREF);
        }
    }

    private void responseResult(int ret) {
        if (DBG) log("rat switch finish with result: " + ret);
        if (mResponseMsg != null) {
            mResponseMsg.arg1 = ret;
            if (ret == RET_SUCC) {
                if (mUpdateSetting) {
                    applyNewRatSetting();
                }
                AsyncResult.forMessage(mResponseMsg, null, null);
            } else {
                AsyncResult.forMessage(mResponseMsg, null, new Throwable());
            }
            mResponseMsg.sendToTarget();
        }
        mInProgress = false;
        mUpdateSetting = false;
    }

    private void log(String msg) {
        Log.d(TAG, msg);
    }
}
