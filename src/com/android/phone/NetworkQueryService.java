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

import android.app.Service;
import android.content.Intent;
import com.android.internal.telephony.OperatorInfo;
import android.os.AsyncResult;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.TelephonyConstants;
import android.util.Log;

import java.util.ArrayList;

/**
 * Service code used to assist in querying the network for service
 * availability.   
 */
public class NetworkQueryService extends Service {
    // debug data
    private static final String LOG_TAG = "NetworkQuery";
    private static final boolean DBG = false;

    // static events
    private static final int EVENT_NETWORK_SCAN_COMPLETED = 100; 
    private static final int EVENT_NETWORK_SCAN_COMPLETED2 = 200;
    
    // static states indicating the query status of the service 
    private static final int QUERY_READY = -1;
    private static final int QUERY_IS_RUNNING = -2;

    // error statuses that will be retured in the callback.
    public static final int QUERY_OK = 0;
    public static final int QUERY_EXCEPTION = 1;
    
    /** state of the query service */
    private int mState;
    
    /** local handle to the phone object */
    private Phone mPhone;

    /** state for SIM 2 **/
    private int mState2;
    private Phone mPhone2;
    /**
     * List of callback objects for SIM 2, also used to synchronize access to
     * itself and to changes in state.
     */
    final RemoteCallbackList<INetworkQueryServiceCallback> mCallbacks2 =
        new RemoteCallbackList<INetworkQueryServiceCallback> ();
    
    /**
     * Class for clients to access.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with
     * IPC.
     */
    public class LocalBinder extends Binder {
        INetworkQueryService getService() {
            return mBinder;
        }
    }
    private final IBinder mLocalBinder = new LocalBinder();

    /**
     * Local handler to receive the network query compete callback
     * from the RIL.
     */
    Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                // if the scan is complete, broadcast the results.
                // to all registerd callbacks.
                case EVENT_NETWORK_SCAN_COMPLETED:
                    if (DBG) log("scan completed, broadcasting results");
                    broadcastQueryResults((AsyncResult) msg.obj, 0);
                    break;
                case EVENT_NETWORK_SCAN_COMPLETED2:
                    if (DBG) log("scan completed, broadcasting results");
                    broadcastQueryResults((AsyncResult) msg.obj, 1);
                    break;
            }
        }
    };
    
    /** 
     * List of callback objects, also used to synchronize access to 
     * itself and to changes in state.
     */
    final RemoteCallbackList<INetworkQueryServiceCallback> mCallbacks =
        new RemoteCallbackList<INetworkQueryServiceCallback> ();

    /**
     * Implementation of the INetworkQueryService interface.
     */
    private final INetworkQueryService.Stub mBinder = new INetworkQueryService.Stub() {
        
        /**
         * Starts a query with a INetworkQueryServiceCallback object if
         * one has not been started yet.  Ignore the new query request
         * if the query has been started already.  Either way, place the
         * callback object in the queue to be notified upon request 
         * completion.
         */
        public void startNetworkQuery(INetworkQueryServiceCallback cb) {
            startNetworkQueryExt(cb, 0);
        }

        public void startNetworkQueryExt(INetworkQueryServiceCallback cb, int slot) {
            if (cb != null) {
                // register the callback to the list of callbacks.
                synchronized (mCallbacks) {
                    if (!TelephonyConstants.IS_DSDS && slot != 0) {
                        slot = 0;
                    }

                    RemoteCallbackList<INetworkQueryServiceCallback> callbacks =
                            slot == 0? mCallbacks : mCallbacks2;
                    int state = slot == 0? mState : mState2;
                    Phone phone = DualPhoneController.getPrimarySimId() == slot? mPhone: mPhone2;


                    callbacks.register(cb);
                    if (DBG) log("registering callback " + cb.getClass().toString());
                    
                    switch (state) {
                        case QUERY_READY:
                            // TODO: we may want to install a timeout here in case we
                            // do not get a timely response from the RIL.
                            phone.getAvailableNetworks(
                                    mHandler.obtainMessage(slot == 0? EVENT_NETWORK_SCAN_COMPLETED :
                                    EVENT_NETWORK_SCAN_COMPLETED2));

                            if (slot == 0 || !TelephonyConstants.IS_DSDS) {
                                mState = QUERY_IS_RUNNING;
                            } else {
                                mState2 = QUERY_IS_RUNNING;
                            }
                            if (DBG) log("starting new query");
                            break;
                            
                        // do nothing if we're currently busy.
                        case QUERY_IS_RUNNING:
                            if (DBG) log("query already in progress");
                            break;
                        default:
                    }

                }
            }
        }
        
        /**
         * Stops a query with a INetworkQueryServiceCallback object as
         * a token.
         */
        public void stopNetworkQuery(INetworkQueryServiceCallback cb) {
            stopNetworkQueryExt(cb, 0);
        }

        public void stopNetworkQueryExt(INetworkQueryServiceCallback cb, int slot) {
            // currently we just unregister the callback, since there is 
            // no way to tell the RIL to terminate the query request.  
            // This means that the RIL may still be busy after the stop 
            // request was made, but the state tracking logic ensures 
            // that the delay will only last for 1 request even with 
            // repeated button presses in the NetworkSetting activity. 
            if (cb != null) {
                synchronized (mCallbacks) {
                    if (!TelephonyConstants.IS_DSDS && slot != 0) {
                        slot = 0;
                    }

                    if (slot == 0) {
                        if (DBG) log("unregistering callback " + cb.getClass().toString());
                        mCallbacks.unregister(cb);
                    } else {
                        if (DBG) log("unregistering callback " + cb.getClass().toString());
                        mCallbacks2.unregister(cb);
                    }
                }
            }
        }
    };
    
    @Override
    public void onCreate() {
        mState = QUERY_READY;
        mPhone = PhoneFactory.getDefaultPhone();

        if (TelephonyConstants.IS_DSDS) {
            mState2 = QUERY_READY;
            mPhone2 = PhoneFactory.get2ndPhone();
        }
    }
    
    /**
     * Required for service implementation.
     */
    @Override
    public void onStart(Intent intent, int startId) {
    }
    
    /**
     * Handle the bind request.
     */
    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Currently, return only the LocalBinder instance.  If we
        // end up requiring support for a remote binder, we will need to 
        // return mBinder as well, depending upon the intent.
        if (DBG) log("binding service implementation");
        return mLocalBinder;
    }

    /**
     * Broadcast the results from the query to all registered callback
     * objects. 
     */
    private void broadcastQueryResults (AsyncResult ar, int slot) {
        // reset the state.
        synchronized (mCallbacks) {
            RemoteCallbackList<INetworkQueryServiceCallback> callbacks = mCallbacks;
            if (slot == 0) {
                mState = QUERY_READY;
            } else {
                mState2 = QUERY_READY;
                callbacks = mCallbacks2;
            }
            
            // see if we need to do any work.
            if (ar == null) {
                if (DBG) log("AsyncResult is null.");
                return;
            }
    
            // TODO: we may need greater accuracy here, but for now, just a
            // simple status integer will suffice.
            int exception = (ar.exception == null) ? QUERY_OK : QUERY_EXCEPTION;
            if (DBG) log("AsyncResult has exception " + exception);
            
            // Make the calls to all the registered callbacks.
            for (int i = (callbacks.beginBroadcast() - 1); i >= 0; i--) {
                INetworkQueryServiceCallback cb = callbacks.getBroadcastItem(i);
                if (DBG) log("broadcasting results to " + cb.getClass().toString());
                try {
                    cb.onQueryComplete((ArrayList<OperatorInfo>) ar.result, exception);
                } catch (RemoteException e) {
                }
            }
            
            // finish up.
            callbacks.finishBroadcast();
        }
    }
    
    private static void log(String msg) {
        Log.d(LOG_TAG, msg);
    }    
}
