/*
 * Copyright (C) 2012 Intel Corporation, All Rights Reserved
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

import android.content.pm.PackageManager;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ServiceManager;
import android.util.Log;

import com.android.internal.telephony.IOemHook;
import com.android.internal.telephony.OemHookConstants;
import com.android.internal.telephony.Phone;

/**
 * Implementation of the IOemTelephony interface.
 */
public class OemHookInterfaceManager extends IOemHook.Stub {
    private static final String LOG_TAG = "OemHookInterfaceManager";
    private static final boolean DBG = true;

    // Message codes used with mMainThreadHandler
    private static final int CMD_GET_DVP = 11;
    private static final int EVENT_GET_DVP_DONE = 12;
    private static final int CMD_SET_DVP = 13;
    private static final int EVENT_SET_DVP_DONE = 14;

    // Permissions
    private static final String READ_PHONE_STATE =
                        android.Manifest.permission.READ_PHONE_STATE;
    private static final String MODIFY_PHONE_STATE =
                    android.Manifest.permission.MODIFY_PHONE_STATE;
    private static final String WRITE_SETTINGS =
                    android.Manifest.permission.WRITE_SETTINGS;
    /** The singleton instance. */
    private static OemHookInterfaceManager sInstance;

    // PhoneApp mApp;
    Phone mPhone;
    MainThreadHandler mMainThreadHandler;

    /*
     * A request object for use with {@link MainThreadHandler}. Requesters
     * should wait() on the request after sending. The main thread will notify
     * the request when it is complete.
     */
    private static final class MainThreadRequest {
        /** The argument to use for the request */
        public Object argument;
        /** The result of the request that is run on the main thread */
        public Object result;

        public MainThreadRequest(Object argument) {
            this.argument = argument;
        }
    }

    /*
     * A handler that processes messages on the main thread in the phone
     * process. Since many of the Phone calls are not thread safe this is needed
     * to shuttle the requests from the inbound binder threads to the main
     * thread in the phone process. The Binder thread may provide a
     * {@link MainThreadRequest} object in the msg.obj field that they are
     * waiting on, which will be notified when the operation completes and will
     * contain the result of the request.
     *
     * <p>
     * If a MainThreadRequest object is provided in the msg.obj field, note that
     * request.result must be set to something non-null for the calling thread
     * to unblock.
     */
    private final class MainThreadHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            MainThreadRequest request;
            String[] requestStr;
            Message onCompleted;
            AsyncResult ar;

            switch (msg.what) {
                case CMD_GET_DVP:
                    request = (MainThreadRequest) msg.obj;
                    onCompleted = obtainMessage(EVENT_GET_DVP_DONE, request);
                    requestStr = new String[1];
                    requestStr[0] = Integer.toString(
                           OemHookConstants.RIL_OEM_HOOK_STRING_GET_DVP_STATE);
                    mPhone.invokeOemRilRequestStrings(requestStr, onCompleted);
                    break;

                case EVENT_GET_DVP_DONE:
                    ar = (AsyncResult) msg.obj;
                    request = (MainThreadRequest) ar.userObj;

                    int ret = OemHookConstants.DVP_STATE_INVALID;
                    if (ar.exception == null && ar.result != null) {
                       String response[] = (String[])ar.result;
                        if (response.length > 0) {
                            ret = "1".equals(response[0]) ? OemHookConstants.DVP_STATE_ENABLED :
                                    OemHookConstants.DVP_STATE_DISABLED;
                        } else {
                            ret = OemHookConstants.DVP_STATE_DISABLED;
                        }
                    }

                    request.result = Integer.valueOf(ret);
                    synchronized (request) {
                        request.notifyAll();
                    }
                    break;

                case CMD_SET_DVP:
                    request = (MainThreadRequest) msg.obj;
                    onCompleted = obtainMessage(EVENT_SET_DVP_DONE, request);
                    requestStr = new String[2];
                    requestStr[0] = Integer.toString(
                           OemHookConstants.RIL_OEM_HOOK_STRING_SET_DVP_ENABLED);
                    requestStr[1] = ((Boolean) request.argument).booleanValue()? "1" : "0";
                    mPhone.invokeOemRilRequestStrings(requestStr, onCompleted);
                    break;

                case EVENT_SET_DVP_DONE:
                    ar = (AsyncResult) msg.obj;
                    request = (MainThreadRequest) ar.userObj;

                    boolean result = false;
                    if (ar.exception == null && ar.result != null) {
                        result = true;
                    }

                    request.result = result;
                    synchronized (request) {
                        request.notifyAll();
                    }
                    break;

                default:
                    Log.w(LOG_TAG, "MainThreadHandler: unexpected message code: "+ msg.what);
                    break;
            }
        }
    }

    /*
     * Posts the specified command to be executed on the main thread, waits for
     * the request to complete, and returns the result.
     *
     * @see sendRequestAsync
     */
    private Object sendRequest(int command, Object argument) {
        if (Looper.myLooper() == mMainThreadHandler.getLooper()) {
            throw new RuntimeException("This method will deadlock if called from the main thread.");
        }
        MainThreadRequest request = new MainThreadRequest(argument);
        Message msg = mMainThreadHandler.obtainMessage(command, request);
        msg.sendToTarget();
        // Wait for the request to complete
        synchronized (request) {
            while (request.result == null) {
                try {
                    request.wait();
                } catch (InterruptedException e) {
                    // Do nothing, go back and wait until the request is
                    // complete
                }
            }
        }
        return request.result;
    }

    /*
     * Asynchronous ("fire and forget") version of sendRequest(): Posts the
     * specified command to be executed on the main thread, and returns
     * immediately.
     *
     * @see sendRequest
     */
    private void sendRequestAsync(int command) {
        mMainThreadHandler.sendEmptyMessage(command);
    }

    /* Private constructor; @see init() */
    private OemHookInterfaceManager() {
        mPhone = PhoneGlobals.getPhone();
        mMainThreadHandler = new MainThreadHandler();
        publish();
    }

    private void publish() {
        ServiceManager.addService("oemtelephony", this);
    }

    /**
     * Initialize the singleton PhoneInterfaceManager instance. This is only
     * done once, at startup, from PhoneApp.onCreate().
     */
    /* package */public static OemHookInterfaceManager init() {
        synchronized (OemHookInterfaceManager.class) {
            if (sInstance == null) {
                sInstance = new OemHookInterfaceManager();
            } else {
                Log.wtf(LOG_TAG, "init() called multiple times!  sInstance = "+ sInstance);
            }
            return sInstance;
        }
    }

    public int getDvPState() {
        int ret = OemHookConstants.DVP_STATE_INVALID;
        if (mPhone.getContext().checkCallingOrSelfPermission(
                READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            ret = (Integer) sendRequest(CMD_GET_DVP, null);
            if (DBG) Log.d(LOG_TAG, "getDVP " + ret);
        } else {
            Log.e(LOG_TAG, "Permission denied - getDVP");
        }

        return ret;
    }

    public boolean setDvPEnabled(boolean enable) {
        boolean ret = false;
        if (mPhone.getContext().checkCallingOrSelfPermission(
                WRITE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {

            ret = (Boolean) sendRequest(CMD_SET_DVP, enable);
            if (DBG) Log.d(LOG_TAG, "setDVP " + ret);
        } else {
            Log.e(LOG_TAG, "Permission denied - setDVP");
        }

        return ret;
    }
}
