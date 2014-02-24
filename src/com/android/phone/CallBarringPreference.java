package com.android.phone;

import static com.android.phone.TimeConsumingPreferenceActivity.EXCEPTION_ERROR;

import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.preference.ListPreference;
import android.util.AttributeSet;
import android.util.Log;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Phone;

import java.util.LinkedList;
import java.util.ListIterator;

public class CallBarringPreference extends ListPreference {
    private static final String LOG_TAG = "CallBarringPreference";
    private static final boolean DBG = true;
    private static final boolean VDBG = Log.isLoggable(LOG_TAG, Log.VERBOSE);
    private static final String INIT_PIN = "";

    private static final int ACTIVATED_STATE = 1;
    private static final int DEACTIVATED_STATE = 0;

    // CB type value
    private static int sCBAOState = DEACTIVATED_STATE;
    private static int sCBOIState = DEACTIVATED_STATE;
    private static int sCBOXState = DEACTIVATED_STATE;
    private static int sCBAIState = DEACTIVATED_STATE;
    private static int sCBIRState = DEACTIVATED_STATE;

    private final Phone mPhone;
    private TimeConsumingPreferenceListener mTCPListener;

    private static final int CURRENT_CB_SERVICE = CommandsInterface.SERVICE_CLASS_VOICE;
    private static int sOutCBIndex = 0;
    private static int sInCBIndex = 0;
    private static int sIndexWantedToBe = 0;

    private final MyHandler mHandler = new MyHandler();

    private static LinkedList<SetCBCommand> sCommands =
            new LinkedList<SetCBCommand>();

    // type of CallBarringPreference
    public enum CallBarringPreferenceType {
        CB_OUTGOING, CB_INCOMING, CB_END_DEFINED
    }

    public CallBarringPreferenceType mCBType =
            CallBarringPreferenceType.CB_END_DEFINED;

    private class SetCBCommand {
        private String mPin;
        private final int mIndex;

        SetCBCommand(int index, String pin) {
            mIndex = index;
            mPin = pin;
        }

        void supplyPin(String pin) {
            mPin = pin;
        }

        boolean noPin() {
            return mPin.equals(INIT_PIN);
        }

        void run() {
            updateState(mPin, mIndex);
        }
    }

    private int currentCBValuetoIndex() {
        int ret = 0;

        if (mCBType == CallBarringPreferenceType.CB_OUTGOING)
            ret = sOutCBIndex;
        else if (mCBType == CallBarringPreferenceType.CB_INCOMING)
            ret = sInCBIndex;

        if (VDBG) Log.d(LOG_TAG, "currentCBValuetoIndex:" + ret);
        return ret;
    }

    private void initCBStateCache() {
        sCBAOState = DEACTIVATED_STATE;
        sCBOIState = DEACTIVATED_STATE;
        sCBOXState = DEACTIVATED_STATE;
        sCBAIState = DEACTIVATED_STATE;
        sCBIRState = DEACTIVATED_STATE;
    }

    public CallBarringPreference(Context context, AttributeSet attrs) {
        super(context, attrs);

        mPhone = PhoneGlobals.getPhone();
    }

    public CallBarringPreference(Context context) {
        this(context, null);
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        if (VDBG) Log.d(LOG_TAG, "onDialogClosed, tcpListener " + mTCPListener);

        super.onDialogClosed(positiveResult);

        if (mTCPListener != null && positiveResult) {
            int idx = findIndexOfValue(getValue());
            if (VDBG) Log.d(LOG_TAG, "onDialogClosed select:" + idx);
            // In case user changed the barring mode send the new one to network
            if (idx != currentCBValuetoIndex()) {
                addPendingCommand(idx);
                mTCPListener.onStarted(this, false);
            }
        }
    }

    public void addPendingCommand(int idx) {
        if (VDBG) Log.d(LOG_TAG, "addPendingCommand: type:" + this.mCBType
                + "index :" + idx);
        sCommands.add(new SetCBCommand(idx, INIT_PIN));
    }

    public static void clearPendingCommand() {
        if (VDBG) Log.d(LOG_TAG, "clear all pending command");
        sCommands.clear();
    }

    public static void executePendingCommands(String pin) {
        if (VDBG) Log.d(LOG_TAG, "executePendingCommands:");
        ListIterator<SetCBCommand> it = sCommands.listIterator();
        while (it.hasNext()) {
            it.next().supplyPin(pin);
        }
        executePendingCommands();
    }

    public static boolean isPendingCommandsNeedPin() {
        ListIterator<SetCBCommand> it = sCommands.listIterator();
        boolean bool = false;
        while (it.hasNext()) {
            if (it.next().noPin()) {
                bool = true;
                break;
            }
        }
        return bool;
    }

    public static void executePendingCommands() {
        if (sCommands.size() > 0) {
            SetCBCommand command = sCommands.remove();
            if (command != null)
                command.run();
        }
    }

    private void updateState(String password, int idx) {
        if (mTCPListener == null)
            return;
        mTCPListener.onStarted(this, false);
        if (DBG) Log.d(LOG_TAG, "updateState mCBType :" + mCBType
                + " index:" + idx);

        boolean changed = true;
        sIndexWantedToBe = idx;

        switch (mCBType) {
            case CB_OUTGOING:
                switch (idx) { // outgoing call barring changed!
                    case 0: // off
                        if (sCBAOState == ACTIVATED_STATE)
                            setCB(CommandsInterface.CB_FACILITY_BAOC,
                                    false, password, MyHandler.MESSAGE_SET_CBAO);
                        else if (sCBOIState == ACTIVATED_STATE)
                            setCB(CommandsInterface.CB_FACILITY_BAOIC,
                                    false, password, MyHandler.MESSAGE_SET_CBOI);
                        else if (sCBOXState == ACTIVATED_STATE)
                            setCB(CommandsInterface.CB_FACILITY_BAOICxH,
                                    false, password, MyHandler.MESSAGE_SET_CBOX);
                        else
                            changed = false;
                        break;
                    case 1: // International call
                        setCB(CommandsInterface.CB_FACILITY_BAOIC,
                                true, password, MyHandler.MESSAGE_SET_CBOI);
                        break;
                    case 2: // Roaming
                        setCB(CommandsInterface.CB_FACILITY_BAOICxH,
                                true, password, MyHandler.MESSAGE_SET_CBOX);
                        break;
                    case 3: // all
                        setCB(CommandsInterface.CB_FACILITY_BAOC,
                                true, password, MyHandler.MESSAGE_SET_CBAO);
                        break;
                    default:
                        changed = false;
                        break;
                }
                break;
            case CB_INCOMING:
                switch (idx) {
                    case 0: // off
                        if (sCBAIState == ACTIVATED_STATE)
                            setCB(CommandsInterface.CB_FACILITY_BAIC,
                                    false, password, MyHandler.MESSAGE_SET_CBAI);
                        else if (sCBIRState == ACTIVATED_STATE)
                            setCB(CommandsInterface.CB_FACILITY_BAICr,
                                    false, password, MyHandler.MESSAGE_SET_CBIR);
                        else
                            changed = false;
                        break;
                    case 1: // Roaming
                        setCB(CommandsInterface.CB_FACILITY_BAICr,
                                true, password, MyHandler.MESSAGE_SET_CBIR);
                        break;
                    case 2: // all
                        setCB(CommandsInterface.CB_FACILITY_BAIC,
                                true, password, MyHandler.MESSAGE_SET_CBAI);
                        break;
                    default:
                        changed = false;
                        break;
                }
                break;
            default:
                if (DBG) Log.w(LOG_TAG, "mCBType is error");
                break;
        }

        if (!changed)
            mTCPListener.onFinished(CallBarringPreference.this, false);
    }

    public void init(TimeConsumingPreferenceListener listener, boolean skipReading) {
        mTCPListener = listener;

        if (skipReading) {
            initIndexValue();
        } else {
            if (DBG) Log.d(LOG_TAG, "query call barring now");
            initCBStateCache();
            if (DBG) Log.d(LOG_TAG, "query call barring CB_FACILITY_BAOC");
            mPhone.getCallBarring(CommandsInterface.CB_FACILITY_BAOC,
                    mHandler.obtainMessage(
                        MyHandler.MESSAGE_GET_CBAO,
                        MyHandler.MESSAGE_GET_CBAO,
                        MyHandler.MESSAGE_GET_CBAO),
                    CommandsInterface.SERVICE_CLASS_VOICE);
            if (mTCPListener != null) {
                mTCPListener.onStarted(this, true);
            }
        }
    }

    private class MyHandler extends Handler {
        private static final int MESSAGE_GET_CBIR = 1;
        private static final int MESSAGE_GET_CBAO = 2;
        private static final int MESSAGE_GET_CBOI = 3;
        private static final int MESSAGE_GET_CBOX = 4;
        private static final int MESSAGE_GET_CBAI = 5;

        private static final int MESSAGE_SET_CBAI = 6;
        private static final int MESSAGE_SET_CBAO = 7;
        private static final int MESSAGE_SET_CBIR = 8;
        private static final int MESSAGE_SET_CBOI = 9;
        private static final int MESSAGE_SET_CBOX = 10;

        private static final int EVENT_CB_EXECUTED = 11;

        @Override
        public void handleMessage(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;

            if (VDBG) Log.d(LOG_TAG, "Get Event:" + "; msg.arg1:"
                    + msg.arg1 + "; msg.arg2:" + msg.arg2);

            boolean isReading = (msg.arg1 != EVENT_CB_EXECUTED);

            if (VDBG) Log.d(LOG_TAG, "isReading:" + isReading);

            if (ar.exception != null) {
                if (VDBG) Log.d(LOG_TAG, "CallBarring exception: ar.exception="
                        + ar.exception);

                mTCPListener.onFinished(CallBarringPreference.this, isReading);
                mTCPListener.onError(CallBarringPreference.this,
                        isReading ? CallBarringOptions.EXCEPTION_UNAVAILABLE
                        : EXCEPTION_ERROR);

            } else if (ar.userObj instanceof Throwable) {
                if (VDBG) Log.d(LOG_TAG, "CallBarring Throwable");

                mTCPListener.onFinished(CallBarringPreference.this, isReading);
                mTCPListener.onError(CallBarringPreference.this,
                        isReading ? CallBarringOptions.EXCEPTION_UNAVAILABLE
                        : EXCEPTION_ERROR);

            } else { // command execute OKay!
                if (isReading) {
                    handleQueryCBResult(msg.arg1, ar);
                } else {
                    handleSetCBResult(ar);
                }
            }
        }
    }

    private void handleQueryCBResult(int eventType, AsyncResult ar) {
        int [] callBarringState = (int[]) ar.result;
        int nextType = -1;
        String nextString = "";

        if (VDBG) Log.d(LOG_TAG, " CB state successfully queried ,"
                + "value return from cp ------------>:" + callBarringState[0]);

        // for voice data fax status now only set 1
        if (callBarringState[0] > 0)
            callBarringState[0] = callBarringState[0] & 0x01; // the lowest bit

        switch (eventType) {
            case MyHandler.MESSAGE_GET_CBAO:
                if (VDBG) Log.d(LOG_TAG, "handleCBResponse:  querying CBOI!");
                sCBAOState = callBarringState[0];
                if (sCBAOState == ACTIVATED_STATE)
                    sCBOIState = sCBOXState = DEACTIVATED_STATE;
                nextString = CommandsInterface.CB_FACILITY_BAOIC;
                nextType = MyHandler.MESSAGE_GET_CBOI;
                break;

            case MyHandler.MESSAGE_GET_CBOI:
                if (VDBG) Log.d(LOG_TAG, "handleCBResponse:  querying CBOX!");
                sCBOIState = callBarringState[0];
                if (sCBOIState == ACTIVATED_STATE)
                    sCBAOState = sCBOXState = DEACTIVATED_STATE;
                nextString = CommandsInterface.CB_FACILITY_BAOICxH;
                nextType = MyHandler.MESSAGE_GET_CBOX;
                break;

            case MyHandler.MESSAGE_GET_CBOX:
                if (VDBG) Log.d(LOG_TAG, "handleCBResponse:  querying CBAI!");
                sCBOXState = callBarringState[0];
                if (sCBOXState == ACTIVATED_STATE)
                    sCBAOState = sCBOIState = DEACTIVATED_STATE;
                nextString = CommandsInterface.CB_FACILITY_BAIC;
                nextType = MyHandler.MESSAGE_GET_CBAI;
                break;

            case MyHandler.MESSAGE_GET_CBAI:
                if (VDBG) Log.d(LOG_TAG, "handleCBResponse:  querying CBIR!");
                sCBAIState = callBarringState[0];
                if (sCBAIState == ACTIVATED_STATE)
                    sCBIRState = DEACTIVATED_STATE;
                nextString = CommandsInterface.CB_FACILITY_BAICr;
                nextType = MyHandler.MESSAGE_GET_CBIR;
                break;

            case MyHandler.MESSAGE_GET_CBIR:
                if (VDBG) Log.d(LOG_TAG, "handleCBResponse: ALL query done!");
                sCBIRState = callBarringState[0];
                if (sCBIRState == ACTIVATED_STATE)
                    sCBAIState = DEACTIVATED_STATE;
                break;

            default:
                if (VDBG) Log.d(LOG_TAG, "handleCBResponse: msg not handled");
                break;
        }

        if (nextType == -1) {
            if (DBG) Log.d(LOG_TAG, "onFinished normal");
            syncCBStateToIndex();
            mTCPListener.onFinished(CallBarringPreference.this, true);

        } else {
            mPhone.getCallBarring(nextString,
                    mHandler.obtainMessage(nextType, nextType, nextType),
                    CommandsInterface.SERVICE_CLASS_VOICE);

        }
    }

    private void handleSetCBResult(AsyncResult ar) {
        if (VDBG) Log.d(LOG_TAG, "handleSetCBResult");

        if (null != ar.exception) {
            if (VDBG) Log.d(LOG_TAG, "set complete, response from network: + r.exception: "
                    + ar.exception.toString());

            mTCPListener.onFinished(CallBarringPreference.this, false);
            mTCPListener.onError(CallBarringPreference.this, EXCEPTION_ERROR);
        } else {
            if (mCBType == CallBarringPreferenceType.CB_OUTGOING)
                sOutCBIndex = sIndexWantedToBe;
            else if (mCBType == CallBarringPreferenceType.CB_INCOMING)
                sInCBIndex = sIndexWantedToBe;
            syncCBIndexToState();
            mTCPListener.onFinished(CallBarringPreference.this, false);
        }
        sIndexWantedToBe = 0;
    }

    private void syncCBStateToIndex() {
        // outing call barring state
        if (sCBAOState == ACTIVATED_STATE)
            sOutCBIndex = 3;
        else if (sCBOIState == ACTIVATED_STATE)
            sOutCBIndex = 1;
        else if (sCBOXState == ACTIVATED_STATE)
            sOutCBIndex = 2;
        else
            sOutCBIndex = 0;

        // incoming call barring state
        if (sCBAIState == ACTIVATED_STATE)
            sInCBIndex = 2;
        else if (sCBIRState == ACTIVATED_STATE)
            sInCBIndex = 1;
        else
            sInCBIndex = 0;
    }

    private void syncCBIndexToState() {
        switch(mCBType) {
            case CB_OUTGOING:
                sCBAOState = DEACTIVATED_STATE;
                sCBOIState = DEACTIVATED_STATE;
                sCBOXState = DEACTIVATED_STATE;
                switch(sOutCBIndex) {
                    case 1:
                        sCBOIState = ACTIVATED_STATE;
                        break;
                    case 2:
                        sCBOXState = ACTIVATED_STATE;
                        break;
                    case 3:
                        sCBAOState = ACTIVATED_STATE;
                        break;
                    default:
                }
                break;
            case CB_INCOMING:
                sCBAIState = DEACTIVATED_STATE;
                sCBIRState = DEACTIVATED_STATE;
                switch(sInCBIndex) {
                    case 1:
                        sCBIRState = ACTIVATED_STATE;
                        break;
                    case 2:
                        sCBAIState = ACTIVATED_STATE;
                        break;
                    default:
                }
                break;
            default:
                break;
        }
    }

    public void setPreferenceType(CallBarringPreferenceType cbtype) {
        mCBType = cbtype;
        initIndexValue();
    }

    @Override
    protected void onClick() {
        initIndexValue();
        super.onClick();
    }

    private void initIndexValue() {
        switch(mCBType) {
            case CB_OUTGOING:
                setValueIndex(sOutCBIndex);
                break;
            case CB_INCOMING:
                setValueIndex(sInCBIndex);
                break;
            default:
                break;
        }
        setSummary(getValue());
    }

    private void setCB(String facility, boolean lockstate,
            String password, int type) {

        if (VDBG) Log.d(LOG_TAG,
                "setCB: facility<" + facility
                + "> lockstate<" + lockstate
                + "> password<" + password
                + "> type<" + type);

        mPhone.setCallBarring(facility, lockstate, password,
                mHandler.obtainMessage(
                    MyHandler.EVENT_CB_EXECUTED,
                    MyHandler.EVENT_CB_EXECUTED,
                    type),
                    CURRENT_CB_SERVICE);
    }

}
