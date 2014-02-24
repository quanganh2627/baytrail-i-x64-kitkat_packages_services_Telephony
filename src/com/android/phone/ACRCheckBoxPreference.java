package com.android.phone;

import static com.android.phone.TimeConsumingPreferenceActivity.RESPONSE_ERROR;

import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.preference.CheckBoxPreference;
import android.util.AttributeSet;
import android.util.Log;

import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.ims.ImsPhoneBase;

public class ACRCheckBoxPreference extends CheckBoxPreference {
    private static final String LOG_TAG = "ACRCheckBoxPreference";
    private final static boolean DBG = true;

    private final MyHandler mHandler = new MyHandler();
    private final ImsPhoneBase mPhone;
    private TimeConsumingPreferenceListener mTcpListener;

    public ACRCheckBoxPreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        Phone phone = CallManager.getInstance().getDefaultPhone();

        if (phone.getPhoneType() == PhoneConstants.PHONE_TYPE_IMS) {
            mPhone = (ImsPhoneBase) phone;
        } else {
            mPhone = null;
        }
    }

    public ACRCheckBoxPreference(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.checkBoxPreferenceStyle);
    }

    public ACRCheckBoxPreference(Context context) {
        this(context, null);
    }

    public void init(TimeConsumingPreferenceListener listener, boolean skipReading) {
        mTcpListener = listener;

        if (!skipReading && mPhone != null) {
            mPhone.getACR(mHandler.obtainMessage(MyHandler.MESSAGE_GET_ACR,
                    MyHandler.MESSAGE_GET_ACR, MyHandler.MESSAGE_GET_ACR));
            if (mTcpListener != null) {
                mTcpListener.onStarted(this, true);
            }
        }
    }

    @Override
    protected void onClick() {
        super.onClick();

        if (mPhone != null) {
            mPhone.setACR(isChecked(), mHandler.obtainMessage(MyHandler.MESSAGE_SET_ACR));
        }

        if (mTcpListener != null) {
            mTcpListener.onStarted(this, false);
        }
    }

    private class MyHandler extends Handler {
        private static final int MESSAGE_GET_ACR = 0;
        private static final int MESSAGE_SET_ACR = 1;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_GET_ACR:
                    handleGetACRResponse(msg);
                    break;
                case MESSAGE_SET_ACR:
                    handleSetACRResponse(msg);
                    break;
                default:
                    break;
            }
        }

        private void handleGetACRResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;

            if (mTcpListener != null) {
                if (msg.arg2 == MESSAGE_SET_ACR) {
                    mTcpListener.onFinished(ACRCheckBoxPreference.this, false);
                } else {
                    mTcpListener.onFinished(ACRCheckBoxPreference.this, true);
                }
            }

            if (ar.exception != null) {
                if (DBG) Log.d(LOG_TAG, "handleGetACRResponse: ar.exception=" + ar.exception);

                if (mTcpListener != null) {
                    mTcpListener.onException(ACRCheckBoxPreference.this,
                            (CommandException) ar.exception);
                }
            } else if (ar.userObj instanceof Throwable) {
                if (mTcpListener != null) {
                    mTcpListener.onError(ACRCheckBoxPreference.this, RESPONSE_ERROR);
                }
            } else {
                if (DBG) Log.d(LOG_TAG, "handleGetACRResponse: ACR state successfully queried.");

                int[] cwArray = (int[]) ar.result;
                /* TODO : once ACR will be handled by imsstack need to check how result
                           will be presented */
                try {
                    setChecked(cwArray[0] == 1 && (cwArray[1] & 0x01) == 0x01);
                } catch (ArrayIndexOutOfBoundsException e) {
                    Log.e(LOG_TAG, "handleGetACRResponse: improper result: err ="
                            + e.getMessage());
                }
            }
        }

        private void handleSetACRResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;

            if (ar.exception != null) {
                if (DBG) Log.d(LOG_TAG, "handleSetACRResponse: ar.exception=" + ar.exception);
            }

            if (mPhone != null) {
                if (DBG) Log.d(LOG_TAG, "handleSetACRResponse: re get");
                mPhone.getACR(obtainMessage(MESSAGE_GET_ACR,
                        MESSAGE_SET_ACR, MESSAGE_SET_ACR, ar.exception));
            }
        }
    }
}
