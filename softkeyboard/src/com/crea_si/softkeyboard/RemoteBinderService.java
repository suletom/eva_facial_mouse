/*
 * Copyright (C) 2015 Cesar Mauri Loba (CREA Software Systems)
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

package com.crea_si.softkeyboard;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import com.crea_si.input_method_aidl.IClickableIME;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;

/**
 * Listens to and dispatches remote requests
 *
 * TODO: improve security
 */

public class RemoteBinderService extends Service {

    // handler used to forward calls to the main thread 
    Handler mMainThreadHandler;

    // binder stub, receives remote requests on a secondary thread
    private final IClickableIME.Stub mBinder= new IClickableIME.Stub() {
        @Override
        public boolean click(int x, int y) throws RemoteException {
            // pass the control to the main thread to facilitate implementation of the IME
            EVIACAMSOFTKBD.debug("RemoteBinderService: click"); 
            return click_main_thread(x, y);
        }

        @Override
        public void openIME() throws RemoteException {
            Runnable r= new Runnable() {
                @Override
                public void run() {
                    EVIACAMSOFTKBD.debug("RemoteBinderService: openIME"); 
                    SoftKeyboard.openIME();                    
                }                
            };
            mMainThreadHandler.post(r);
        }

        @Override
        public void closeIME() throws RemoteException {
            Runnable r= new Runnable() {
                @Override
                public void run() {
                    EVIACAMSOFTKBD.debug("RemoteBinderService: closeIME");
                    SoftKeyboard.closeIME();
                }
            };
            mMainThreadHandler.post(r);
        }
    };

    /** Calls click on the main thread and waits for the result */
    private boolean click_main_thread(final int x, final int y) {
        FutureTask<Boolean> futureResult = new FutureTask<Boolean>(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                // TODO: if an exception is thrown, calling code always receive
                // a RemoteException, it would be better to provide more information
                // on the caller. See here:
                // http://stackoverflow.com/questions/1800881/throw-a-custom-exception-from-a-service-to-an-activity
                return SoftKeyboard.click(x, y);
            }
        });

        mMainThreadHandler.post(futureResult);

        try {
            // this block until the result is calculated
            return futureResult.get();
        } 
        catch (ExecutionException e) {
            EVIACAMSOFTKBD.debug("RemoteBinderService: exception: " + e.getMessage()); 
        } 
        catch (InterruptedException e) {
            EVIACAMSOFTKBD.debug("RemoteBinderService: exception: " + e.getMessage()); 
        }
        return false;
    }

    @Override
    public void onCreate () {
        EVIACAMSOFTKBD.debug("RemoteBinderService: onCreate");
        mMainThreadHandler= new Handler();
    }

    /** When binding to the service, we return an interface to the client */
    @Override
    public IBinder onBind(Intent intent) {
        EVIACAMSOFTKBD.debug("RemoteBinderService: onBind");
        return mBinder;
    }

    @Override
    public boolean onUnbind (Intent intent) {
        EVIACAMSOFTKBD.debug("RemoteBinderService: onUnbind");
        return false;
    }

    @Override
    public void onDestroy () {
        EVIACAMSOFTKBD.debug("RemoteBinderService: onDestroy");
    }
 }