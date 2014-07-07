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

import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.TelephonyConstants;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.TelephonyProperties2;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyIntents2;
import com.android.internal.telephony.IccCardConstants;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.app.AlertDialog.Builder;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.DialerKeyListener;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.View.OnClickListener;
import android.view.View.OnFocusChangeListener;
import android.view.View.OnKeyListener;
import android.view.Window;
import android.view.WindowManager.LayoutParams;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.os.AsyncResult;
import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;

import android.text.InputType;
import android.view.MotionEvent;
import android.view.Gravity;
import android.text.Selection;
import android.net.ConnectivityManager;
import android.content.res.Configuration;
import android.telephony.TelephonyManager;

import com.android.phone.R;

public class PinPukActivity extends Activity implements OnClickListener,
       OnFocusChangeListener, OnKeyListener {
    private static final String TAG = "PinPukActivity";
    private static final boolean DBG = false;
    private static final int DIGIT_PRESS_WAKE_MILLIS = 5000;
    // intent action for launching emergency dialer activity.
    static final String ACTION_EMERGENCY_DIAL = "com.android.phone.EmergencyDialer.DIAL";

    private TextView mHeaderText;
    private TextView mPukText;
    private TextView mPinText;
    private TextView mEmergencyCallButton;

    private View mOkButton;
    private View mDelPukButton;
    private View mDelPinButton;

    private ViewGroup mPinViewGroup;
    private ViewGroup mPukViewGroup;

    private final int[] mEnteredPin = {0, 0, 0, 0, 0, 0, 0, 0};
    private int mEnteredDigits = 0;

    private ProgressDialog mSimUnlockProgressDialog = null;

    private int mCreationOrientation;

    private int mKeyboardHidden;

    private int mSlot;
    private int mType;
    private boolean mKeyguard;
    private static final char[] DIGITS = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9'};

    private static final int MIN_PIN_LENGTH = 4;
    private static final int MAX_PIN_LENGTH = 8;

    private TouchInput mTouchInput;

    public static final int UNLOCK_PIN = TelephonyManager.SIM_STATE_PIN_REQUIRED;
    public static final int UNLOCK_PUK = TelephonyManager.SIM_STATE_PUK_REQUIRED;
    public static final int UNLOCK_UNKNOWN = TelephonyManager.SIM_STATE_UNKNOWN;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                |WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                |WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                |WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

        setContentView(R.layout.sim_pin_puk_portrait);
        mKeyboardHidden = getResources().getConfiguration().hardKeyboardHidden;

        Intent intent = getIntent();
        mTouchInput = new TouchInput();
        mSlot = intent.getIntExtra(TelephonyConstants.EXTRA_SLOT, 0);
        mType = intent.getIntExtra("type", UNLOCK_PIN);
        if (DBG) Log.d(TAG, "mType:" + mType + ", slot:" + mSlot);

        mCreationOrientation = getResources().getConfiguration().orientation;
        initView();
    }

    private void initView() {
        TextView carrierView = (TextView) findViewById(R.id.carrier);
        if (carrierView != null) {
            carrierView.setVisibility(View.GONE);
        }

        mHeaderText = (TextView) findViewById(R.id.headerText);

        mPukText = (TextView) findViewById(R.id.pukDisplay);
        mPukText.setOnKeyListener(this);
        mPukText.setOnTouchListener(new View.OnTouchListener() {

                public boolean onTouch(View v, MotionEvent event) {
                    EditText editText = (EditText)v;
                    int inType = editText.getInputType(); // backup the input type
                    editText.setInputType(InputType.TYPE_NULL); // disable soft input
                    editText.onTouchEvent(event); // call native handler
                    editText.setInputType(inType); // restore input type
                    editText.setSelection(editText.getText().length());
                    return true;
                }
            });

        mPinText = (TextView) findViewById(R.id.pinDisplay);
        mPinText.setOnKeyListener(this);
        mPinText.setOnTouchListener(new View.OnTouchListener() {

                public boolean onTouch(View v, MotionEvent event) {
                    EditText editText = (EditText)v;
                    int inType = editText.getInputType(); // backup the input type
                    editText.setInputType(InputType.TYPE_NULL); // disable soft input
                    editText.onTouchEvent(event); // call native handler
                    editText.setInputType(inType); // restore input type
                    editText.setSelection(editText.getText().length());
                    return true;
                }
            });

        mDelPukButton = findViewById(R.id.pukDel);
        mDelPukButton.setOnClickListener(this);
        mDelPukButton.setOnFocusChangeListener(this);

        mDelPinButton = findViewById(R.id.pinDel);
        mDelPinButton.setOnClickListener(this);
        mDelPinButton.setOnFocusChangeListener(this);

        mOkButton = findViewById(com.android.internal.R.id.ok);
        mOkButton.setOnClickListener(this);

        updateHeaderText();
        // To make marquee work
        mHeaderText.setSelected(true);

        mPinText.setFocusableInTouchMode(true);
        mPukText.setFocusableInTouchMode(true);

        // Hide the IME
        InputMethodManager imm = (InputMethodManager)getSystemService(
                Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(mPinText.getWindowToken(), 0);
        imm.hideSoftInputFromWindow(mPukText.getWindowToken(), 0);

        mPinViewGroup = (ViewGroup) findViewById(R.id.pinDisplayGroup);
        mPukViewGroup = (ViewGroup) findViewById(R.id.pukDisplayGroup);

        if (mType == UNLOCK_PUK) {
            mPukViewGroup.setVisibility(View.VISIBLE);
            mPukText.requestFocus();
        } else {
            mPukViewGroup.setVisibility(View.GONE);
            mPinText.setHint("");
        }

        mEmergencyCallButton = (TextView) findViewById(R.id.emergencyCallButton);
        mEmergencyCallButton.setOnClickListener(this);
    }

    private void updateHeaderText() {
        StringBuilder title = new StringBuilder(mSlot == 0 ?
                getText(R.string.tab_1_title) : getText(R.string.tab_2_title));
        title.append(": ");

        if (mType == UNLOCK_PUK) {
//            title.append(getString(R.string.keyguard_password_enter_puk_code));
        } else {
//            title.append(getString(R.string.keyguard_password_enter_pin_code));
        }
        title.append(getRetryTip());

        if (TelephonyConstants.IS_DSDS) {
            final int color = mSlot == 0 ?
                    TelephonyConstants.DSDS_TEXT_COLOR_SLOT_1 : TelephonyConstants.DSDS_TEXT_COLOR_SLOT_2;
            mHeaderText.setTextColor(color);
        }
        mHeaderText.setText(title.toString());
    }

    String getRetryTip() {
        String ret = "";
        String prop = isPrimaryPhone() ?
            TelephonyProperties.PROPERTY_SIM_PIN_RETRY_LEFT :
            TelephonyProperties2.PROPERTY_SIM_PIN_RETRY_LEFT;
        int numOfRetry = SystemProperties.getInt(prop, 0);
        if (numOfRetry > 0) {
            ret = getString(R.string.pin_retry_left, numOfRetry);
        }
        return ret;
    }

    /** {@inheritDoc} */
    public boolean needsInput() {
        return true;
    }

    /** {@inheritDoc} */
    public void onPause() {
        super.onPause();
        unregisterReceiver(mBroadcastReceiver);
    }

    /** {@inheritDoc} */
    public void onResume() {
        super.onResume();
        final IntentFilter filter = new IntentFilter();
        filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        filter.addAction(TelephonyIntents2.ACTION_SIM_STATE_CHANGED);
        registerReceiver(mBroadcastReceiver, filter);
        // start fresh
        // make sure that the number of entered digits is consistent when we
        // erase the SIM unlock code
        if (mType == UNLOCK_PIN) {
            mPinText.setText("");
            mEnteredDigits = 0;
        }
        updateHeaderText();
    }

    /** {@inheritDoc} */
    public void onStop() {
        super.onStop();
        cleanup();
    }

    private void cleanup() {
        if (mSimUnlockProgressDialog != null) {
            mSimUnlockProgressDialog.dismiss();
            mSimUnlockProgressDialog = null;
        }
    }

    /**
     * Since the IPC can block, we want to run the request in a separate thread
     * with a callback.
     */
    private abstract class CheckSimPin extends Thread {

        private final String mPin;

        protected CheckSimPin(String pin) {
            mPin = pin;
        }

        abstract void onSimLockChangedResponse(boolean success);

        @Override
        public void run() {
            try {
                final String service = getSeviceBySlot(mSlot, PinPukActivity.this);
                final boolean result = ITelephony.Stub.asInterface(ServiceManager
                        .checkService(service)).supplyPin(mPin);
                runOnUiThread(new Runnable() {
                    public void run() {
                        onSimLockChangedResponse(result);
                    }
                });
            } catch (RemoteException e) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        onSimLockChangedResponse(false);
                    }
                });
            }
        }
    }


    /**
     * Since the IPC can block, we want to run the request in a separate thread
     * with a callback.
     */
    private abstract class CheckSimPuk extends Thread {

        private final String mPin, mPuk;

        protected CheckSimPuk(String puk, String pin) {
            mPuk = puk;
            mPin = pin;
        }

        abstract void onSimLockChangedResponse(boolean success);

        @Override
        public void run() {
            try {
                final String service = getSeviceBySlot(mSlot, PinPukActivity.this);
                final boolean result = ITelephony.Stub.asInterface(ServiceManager
                        .checkService(service)).supplyPuk(mPuk, mPin);

                runOnUiThread(new Runnable() {
                    public void run() {
                        onSimLockChangedResponse(result);
                    }
                });
            } catch (RemoteException e) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        onSimLockChangedResponse(false);
                    }
                });
            }
        }
    }

    public void onClick(View v) {
        if (v == mDelPukButton) {
            if (!mPukText.isFocused())
                mPukText.requestFocus();
            final Editable digits = mPukText.getEditableText();
            final int len = digits.length();
            if (len > 0) {
                digits.delete(len-1, len);
            }
        } else if (v == mDelPinButton) {
            if (!mPinText.isFocused())
                mPinText.requestFocus();
            final Editable digits = mPinText.getEditableText();
            final int len = digits.length();
            if (len > 0) {
                digits.delete(len-1, len);
            }
        } else if (v == mOkButton) {
           if (mType == UNLOCK_PUK) {
              checkPuk();
           } else {
              checkPin();
           }
        } else if (v == mEmergencyCallButton) {
             Intent intent = new Intent(ACTION_EMERGENCY_DIAL);
             intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
             startActivity(intent);
        }
    }

    public void onFocusChange(View v, boolean hasFocus) {
        if (hasFocus) {
            if (v == mDelPukButton) {
                mPukText.requestFocus();
            } else if (v == mDelPinButton) {
                mPinText.requestFocus();
            }
        }
    }

    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && (v == mPukText || v == mPinText)) {
            if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                    keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                setNextFocusId(v, v.getId());
                if (v == mPukText) {
                    checkPuk();
                } else {
                    checkPin();
                }
                return true;
            } else {
                setNextFocusId(v, View.NO_ID);
            }
        }
        return false;
    }

    private void setNextFocusId(View view, int id) {
        view.setNextFocusDownId(id);
        view.setNextFocusForwardId(id);
        view.setNextFocusLeftId(id);
        view.setNextFocusRightId(id);
        view.setNextFocusUpId(id);
    }

    private Dialog getSimUnlockProgressDialog() {
        if (mSimUnlockProgressDialog == null) {
            mSimUnlockProgressDialog = new ProgressDialog(this);
//            mSimUnlockProgressDialog.setMessage(
//                    getString(com.android.internal.R.string.lockscreen_sim_unlock_progress_dialog_message));
            mSimUnlockProgressDialog.setIndeterminate(true);
            mSimUnlockProgressDialog.setCancelable(false);
        }
        return mSimUnlockProgressDialog;
    }

    private void checkPin() {

        // make sure that the pin is at least 4 digits long.
        if (!isValidPin(mPinText.getText().toString(), false) ) {
            // otherwise, display a message to the user, and don't submit.
            mHeaderText.setText(com.android.internal.R.string.invalidPin);
            mPinText.setText("");
            mEnteredDigits = 0;
            return;
        }
        getSimUnlockProgressDialog().show();

        new CheckSimPin(mPinText.getText().toString()) {
            void onSimLockChangedResponse(final boolean success) {
                mPinText.post(new Runnable() {
                    public void run() {
                        if (mSimUnlockProgressDialog != null) {
                            mSimUnlockProgressDialog.hide();
                        }
                        if (success) {
                           finish();
                        } else {
//                            StringBuilder msg = new StringBuilder(getText(R.string.keyguard_password_wrong_pin_code));
//                            msg.append(getRetryTip());
//                            mHeaderText.setText(msg.toString());
                            mPinText.setText("");
                            mEnteredDigits = 0;
                        }
                    }
                });
            }
        }.start();
    }


    private void checkPuk() {
        // make sure that the puk is at least 8 digits long.
        if (!isValidPin(mPukText.getText().toString(), true)) {
            // otherwise, display a message to the user, and don't submit.
            mHeaderText.setText(com.android.internal.R.string.invalidPuk);
            mPukText.setText("");
            return;
        }

        if (!isValidPin(mPinText.getText().toString(), false)) {
            // otherwise, display a message to the user, and don't submit.
            mHeaderText.setText(com.android.internal.R.string.invalidPin);
            mPinText.setText("");
            return;
        }

        getSimUnlockProgressDialog().show();

        new CheckSimPuk(mPukText.getText().toString(),
                mPinText.getText().toString()) {
            void onSimLockChangedResponse(final boolean success) {
                mPinText.post(new Runnable() {
                    public void run() {
                        if (mSimUnlockProgressDialog != null) {
                            mSimUnlockProgressDialog.hide();
                        }
                        if (success) {
                            finish();
                        } else {
                            StringBuilder msg = new StringBuilder(getText(com.android.internal.R.string.badPuk));
                            msg.append(getRetryTip());
                            mHeaderText.setText(msg.toString());//com.android.internal.R.string.badPuk);
                            mPukText.setText("");
                            mPinText.setText("");
                        }
                    }
                });
            }
        }.start();
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }

        final char match = event.getMatch(DIGITS);
        if (match != 0) {
            reportDigit(match - '0');
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            if (mEnteredDigits > 0) {
                Selection.setSelection(mPinText.getEditableText(), mEnteredDigits, mEnteredDigits);
                mPinText.onKeyDown(keyCode, event);
                mEnteredDigits--;
            }
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_ENTER) {
            checkPin();
            return true;
        }

        return false;
    }

    private void reportDigit(int digit) {
       if (mPinText.isFocused()) {
          mPinText.append(Integer.toString(digit));
       } else if (mPukText.isFocused()) {
          mPukText.append(Integer.toString(digit));
       }
    }

    /**
     * Helper class to handle input from touch dialer.  Only relevant when
     * the keyboard is shut.
     */
    private class TouchInput implements OnClickListener {
        private TextView mZero;
        private TextView mOne;
        private TextView mTwo;
        private TextView mThree;
        private TextView mFour;
        private TextView mFive;
        private TextView mSix;
        private TextView mSeven;
        private TextView mEight;
        private TextView mNine;
        private TextView mCancelButton;

        private TouchInput() {
            mZero = (TextView) findViewById(com.android.internal.R.id.zero);
            mOne = (TextView) findViewById(com.android.internal.R.id.one);
            mTwo = (TextView) findViewById(com.android.internal.R.id.two);
            mThree = (TextView) findViewById(com.android.internal.R.id.three);
            mFour = (TextView) findViewById(com.android.internal.R.id.four);
            mFive = (TextView) findViewById(com.android.internal.R.id.five);
            mSix = (TextView) findViewById(com.android.internal.R.id.six);
            mSeven = (TextView) findViewById(com.android.internal.R.id.seven);
            mEight = (TextView) findViewById(com.android.internal.R.id.eight);
            mNine = (TextView) findViewById(com.android.internal.R.id.nine);
            mCancelButton = (TextView) findViewById(com.android.internal.R.id.cancel);

            mZero.setText("0");
            mOne.setText("1");
            mTwo.setText("2");
            mThree.setText("3");
            mFour.setText("4");
            mFive.setText("5");
            mSix.setText("6");
            mSeven.setText("7");
            mEight.setText("8");
            mNine.setText("9");

            if (mKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO) {
                mZero.setVisibility(View.GONE);
                mOne.setVisibility(View.GONE);
                mTwo.setVisibility(View.GONE);
                mThree.setVisibility(View.GONE);
                mFour.setVisibility(View.GONE);
                mFive.setVisibility(View.GONE);
                mSix.setVisibility(View.GONE);
                mSeven.setVisibility(View.GONE);
                mEight.setVisibility(View.GONE);
                mNine.setVisibility(View.GONE);
            } else {
                mZero.setOnClickListener(this);
                mOne.setOnClickListener(this);
                mTwo.setOnClickListener(this);
                mThree.setOnClickListener(this);
                mFour.setOnClickListener(this);
                mFive.setOnClickListener(this);
                mSix.setOnClickListener(this);
                mSeven.setOnClickListener(this);
                mEight.setOnClickListener(this);
                mNine.setOnClickListener(this);
            }
            mCancelButton.setOnClickListener(this);
        }


        public void onClick(View v) {
            if (v == mCancelButton) {
                finish();
                return;
            }

            final int digit = checkDigit(v);
            if (digit >= 0) {
                reportDigit(digit);
            }
        }

        private int checkDigit(View v) {
            int digit = -1;
            if (v == mZero) {
                digit = 0;
            } else if (v == mOne) {
                digit = 1;
            } else if (v == mTwo) {
                digit = 2;
            } else if (v == mThree) {
                digit = 3;
            } else if (v == mFour) {
                digit = 4;
            } else if (v == mFive) {
                digit = 5;
            } else if (v == mSix) {
                digit = 6;
            } else if (v == mSeven) {
                digit = 7;
            } else if (v == mEight) {
                digit = 8;
            } else if (v == mNine) {
                digit = 9;
            }
            return digit;
        }
    }

    boolean isPrimaryPhone() {
        return (mSlot== TelephonyManager.getPrimarySim());
    }

    /**
     *  To get 'phone' service or 'phone2' service by slot.
     */
    static private String getSeviceBySlot(int slotId, Context ctx) {
        boolean isPrimary = true;
        if (TelephonyConstants.IS_DSDS) {
            isPrimary = (slotId == TelephonyManager.getPrimarySim());
        }
        return isPrimary ? "phone" : "phone2";
    }

    static boolean isValidPin(String pin, boolean isPUK) {

        // for pin, we have 4-8 numbers, or puk, we use 8+ digits.
        int pinMinimum = isPUK ? MAX_PIN_LENGTH : MIN_PIN_LENGTH;

        // check validity
        if (pin == null || pin.length() < pinMinimum || (!isPUK && pin.length() > MAX_PIN_LENGTH)) {
            return false;
        } else {
            return true;
        }
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (TelephonyIntents.ACTION_SIM_STATE_CHANGED.equals(action)
                    || TelephonyIntents2.ACTION_SIM_STATE_CHANGED.equals(action)) {
                final int slotId = intent.getIntExtra(TelephonyConstants.EXTRA_SLOT, 0);
                Log.d(TAG, "Receive SIM STATE CHANGED on Slot " + slotId);
                String stateExtra = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
                int newType = UNLOCK_UNKNOWN;
                if (slotId == mSlot) {
                    if (IccCardConstants.INTENT_VALUE_ICC_LOCKED.equals(stateExtra)) {
                        final String lockedReason =
                                intent.getStringExtra(IccCardConstants.INTENT_KEY_LOCKED_REASON);
                        if (IccCardConstants.INTENT_VALUE_LOCKED_ON_PIN.equals(lockedReason)) {
                            newType = UNLOCK_PIN;
                        } else if (IccCardConstants.INTENT_VALUE_LOCKED_ON_PUK.equals(lockedReason)) {
                            newType = UNLOCK_PUK;
                        } else {
                            Log.d(TAG, "Not UNLOCK_PIN nor UNLOCK_PUK status");
                            finish();
                        }
                        if (mType != newType) {
                            mType = newType;
                            Log.d(TAG, "mType is " + mType + " reInit pin/puk View");
                            initView();
                        }
                    } else {
                        Log.d(TAG, "Not In PIN nor PUK lock");
                        finish();
                    }
                }
            }
        }
    };
}
