/*
 * Enable Viacam for Android, a camera based mouse emulator
 *
 * Copyright (C) 2015-17 Cesar Mauri Loba (CREA Software Systems)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.crea_si.eviacam.a11yservice;

import android.accessibilityservice.AccessibilityService;

import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.util.Log;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.crea_si.eviacam.EngineSelector;
import com.crea_si.eviacam.R;
import com.crea_si.eviacam.common.CrashRegister;
import com.crea_si.eviacam.common.EVIACAM;
import com.crea_si.eviacam.common.Engine;
import com.crea_si.eviacam.common.Preferences;
import com.crea_si.eviacam.common.AlertDialog;
import com.crea_si.eviacam.wizard.WizardUtils;

import org.acra.ACRA;

/**
 * The Enable Viacam accessibility service
 */
public class TheAccessibilityService extends AccessibilityService
        implements ComponentCallbacks, Engine.OnInitListener {

    private static final String TAG = "TheAccessibilityService";

    private static TheAccessibilityService sTheAccessibilityService;
    private static final int ALERT_UID = 1;

    // reference to the engine
    private AccessibilityServiceModeEngine mEngine;

    // stores whether the accessibility service was previously started (see comments on init())
    private boolean mServiceStarted = false;

    // reference to the notification management stuff
    private ServiceNotification mServiceNotification;

    private boolean mBlockAllKey = false;

    // Receiver listener for the service notification
    private final BroadcastReceiver mServiceNotificationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            int action = intent.getIntExtra(ServiceNotification.NOTIFICATION_ACTION_NAME, -1);

            if (action == ServiceNotification.NOTIFICATION_ACTION_STOP) {
                /* Ask for confirmation before stopping */
                /*
                AlertDialog ad = new AlertDialog.Builder(c)
                    .setMessage(c.getResources().getString(R.string.notification_stop_confirmation))
                    .setPositiveButton(c.getResources().getString(
                            R.string.notification_stop_confirmation_no), null)
                    .setNegativeButton(c.getResources().getString(
                            R.string.notification_stop_confirmation_yes),
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    cleanupEngine();
                                    Preferences.get().setEngineWasRunning(false);
                                }
                            })
                   .create();
                //noinspection ConstantConditions
                ad.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                ad.show();
                */

                Log.d(EVIACAM.TAG+"->"+TAG, "alertdialog intent");

                Intent inte = new Intent("alertdialog");
                inte.putExtra("uid",ALERT_UID);
                inte.putExtra("text",c.getResources().getString(R.string.notification_stop_confirmation));
                inte.putExtra("negativebuttontext",c.getResources().getString(R.string.notification_stop_confirmation_no));
                inte.putExtra("positivebuttontext",c.getResources().getString(R.string.notification_stop_confirmation_yes));
                inte.putExtra("checkboxtext","");

                LocalBroadcastManager.getInstance(getBaseContext()).sendBroadcast(inte);

            } else if (action == ServiceNotification.NOTIFICATION_ACTION_START) {
                initEngine();
            } else {
                // ignore intent
                Log.d(EVIACAM.TAG+"->"+TAG, "mServiceNotificationReceiver: Got unknown intent");
            }
        }
    };

    private BroadcastReceiver mAlertReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            Log.d(EVIACAM.TAG+"->"+TAG, "mAlertReceiver: Got alertreply intent");

            // Get extra data included in the Intent
            int res = intent.getIntExtra("result",0);
            int  ch = intent.getIntExtra("checkbox",0);
            int uid = intent.getIntExtra("uid",0);

            Log.d(EVIACAM.TAG+"->"+TAG, "mAlertReceiver: Got alertreply intent uid:"+String.valueOf(uid));
            Log.d(EVIACAM.TAG+"->"+TAG, "mAlertReceiver: Got alertreply intent res:"+String.valueOf(res));

            if (uid==ALERT_UID) {
                if (res==AlertDialog.ALERTDIALOG_POSITIVERESULT){
                    Log.d(EVIACAM.TAG+"->"+TAG, "mAlertReceiver: Got alertreply intent stopping!");
                    cleanupEngine();
                    Preferences.get().setEngineWasRunning(false);

                }
            }

        }
    };





        @Override
    public void onConfigurationChanged(Configuration newConfig) {

        if (newConfig.orientation==Configuration.ORIENTATION_PORTRAIT) {


            Intent intent = new Intent("orientation");
            intent.putExtra("dir", 0);

            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);

        }else{


            Intent intent = new Intent("orientation");
            intent.putExtra("dir", 1);

            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        }



        super.onConfigurationChanged(newConfig);
    }

    private BroadcastReceiver mMessageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Get extra data included in the Intent
           Boolean kc = intent.getBooleanExtra("enable",false);
           if (kc){
               mBlockAllKey=true;
           }else{
               mBlockAllKey=false;
           }
        }
    };

    /**
     * Start the initialization sequence of the accessibility service.
     */
    private void init() {
        /*
         * Check if service has been already started.
         * Under certain circumstances onUnbind is not called (e.g. running
         * on an emulator happens quite often) and the service continues running
         * although it shows it is disabled. This does not solve the issue but at
         * least the service does not crash
         *
         * http://stackoverflow.com/questions/28752238/accessibilityservice-onunbind-not-always-
         * called-when-running-on-emulator
         */
        if (mServiceStarted) {
            Log.w(EVIACAM.TAG+"->"+TAG, "Accessibility service already running! Stop here.");
            ACRA.getErrorReporter().handleException(new IllegalStateException(
                    "Accessibility service already running! Stop here."), true);
            return;
        }

        mServiceStarted= true;
        sTheAccessibilityService= this;

         /* When preferences are not properly initialized (i.e. is in slave mode)
           the call will return null. As is not possible to stop the accessibility
           service just take into account an avoid further actions. */
        if (Preferences.initForA11yService(this) == null) return;

        // Service notification
        mServiceNotification= new ServiceNotification(this, mServiceNotificationReceiver);
        mServiceNotification.init();

        /*
         * If crashed recently, abort initialization to avoid several crash messages in a row.
         * The user will need to enable it again through the notification icon.
         */
        if (CrashRegister.crashedRecently(this)) {
            Log.w(EVIACAM.TAG+"->"+TAG, "Recent crash detected. Aborting initialization.");
            CrashRegister.clearCrash(this);
            mServiceNotification.update(ServiceNotification.NOTIFICATION_ACTION_START);
            return;
        }

        /*
         * If the device has been rebooted and the engine was stopped before
         * such a reboot, do not start.
         */
        if (!Preferences.get().getEngineWasRunning()) {
            mServiceNotification.update(ServiceNotification.NOTIFICATION_ACTION_START);
            return;
        }

        LocalBroadcastManager.getInstance(this.getBaseContext()).registerReceiver(
                mMessageReceiver, new IntentFilter("BlockAllKey"));

        LocalBroadcastManager.getInstance(this.getBaseContext()).registerReceiver(
                mAlertReceiver, new IntentFilter("alertdialogreply"));

        initEngine();
    }

    /**
     * Cleanup accessibility service before exiting completely
     */
    private void cleanup() {
        sTheAccessibilityService= null;

        cleanupEngine();

        if (Preferences.get() != null) {
            Preferences.get().cleanup();
        }

        if (mServiceNotification!= null) {
            mServiceNotification.cleanup();
            mServiceNotification= null;
        }

        mServiceStarted = false;
    }

    /**
     * Start engine initialization sequence. When finished, onInit is called
     */
    private void initEngine() {
        if (null != mEngine) {
            Log.d(EVIACAM.TAG+"->"+TAG, "Engine already initialized. Ignoring.");
            return;
        }

        // During initialization cannot send new commands
        mServiceNotification.update(ServiceNotification.NOTIFICATION_ACTION_NONE);

        /* Init the main engine */
        mEngine = EngineSelector.initAccessibilityServiceModeEngine();
        if (mEngine == null) {
            Log.e(EVIACAM.TAG+"->"+TAG, "Cannot initialize CoreEngine in A11Y mode");
        } else {
            mEngine.init(this, this);
        }
    }

    /**
     * Callback for engine initialization completion
     * @param status 0 if initialization completed successfully
     */
    @Override
    public void onInit(int status) {
        if (status == Engine.OnInitListener.INIT_SUCCESS) {
            initEnginePhase2();
        }
        else {
            // Initialization failed
            Log.e(EVIACAM.TAG+"->"+TAG, "Cannot initialize CoreEngine in A11Y mode");
            cleanupEngine();
            mServiceNotification.update(ServiceNotification.NOTIFICATION_ACTION_START);
        }
    }

    public void stop() {

        Log.d(EVIACAM.TAG+"->"+TAG, "Stop everything");
        cleanupEngine();
        Preferences.get().setEngineWasRunning(false);

    }

    public void restart() {

        Log.d(EVIACAM.TAG+"->"+TAG, "Start everything");
        initEngine();

    }

    /**
     * Completes the initialization of the engine
     */
    private void initEnginePhase2() {
        //Analytics.get().trackStartService();

        Preferences.get().setEngineWasRunning(true);

        /* Start wizard or the full engine? */
        if (Preferences.get().getRunTutorial()) {
            // register notification receiver
            LocalBroadcastManager.getInstance(this).registerReceiver(
                    this.mFinishWizardReceiver,
                    new IntentFilter(WizardUtils.WIZARD_CLOSE_EVENT_NAME));

            Intent dialogIntent = new Intent(this, com.crea_si.eviacam.wizard.WizardActivity.class);
            dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            this.startActivity(dialogIntent);
        }
        else {
            Log.d(EVIACAM.TAG+"->"+TAG, "initEnginePhase2 - engine.start()");
            mEngine.start();
        }
        mServiceNotification.update(ServiceNotification.NOTIFICATION_ACTION_STOP);
    }

    /**
     * Receiver listener for the event triggered when the wizard is finished
     */
    private final BroadcastReceiver mFinishWizardReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mEngine!= null) {
                Log.d(EVIACAM.TAG+"->"+TAG, "mFinishWizardReceiver - engine.start()");
                mEngine.start();
                mServiceNotification.update(ServiceNotification.NOTIFICATION_ACTION_STOP);
            }
            LocalBroadcastManager.getInstance(TheAccessibilityService.this).
                    unregisterReceiver(mFinishWizardReceiver);
        }
    };

    /**
     * Stop the engine and free resources
     */
    private void cleanupEngine() {
        if (null == mEngine) return;

        //Analytics.get().trackStopService();

        if (mEngine!= null) {
            mEngine.cleanup();
            mEngine= null;
        }

        EngineSelector.releaseAccessibilityServiceModeEngine();

        mServiceNotification.update(ServiceNotification.NOTIFICATION_ACTION_START);
    }

    /**
     * Get the current instance of the accessibility service
     *
     * @return reference to the accessibility service or null
     */
    public static @Nullable TheAccessibilityService get() {
        return sTheAccessibilityService;
    }

    public void openNotifications() {
        performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
    }

    /**
     * Called when the accessibility service is started
     */
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(EVIACAM.TAG+"->"+TAG, "onCreate");
    }

    /**
     * Called every time the service is switched ON
     */
    @Override
    public void onServiceConnected() {
        Log.d(EVIACAM.TAG+"->"+TAG, "onServiceConnected");
        init();
    }

    /**
     * Called when service is switched off
     */
    @Override
    public boolean onUnbind(Intent intent) {
        /* TODO: it seems that, at this point, views are already destroyed
         * which might be related with the spurious crashes when switching
         * off the accessibility service. Tested on Nexus 7 Android 5.1.1
         */
        Log.d(EVIACAM.TAG+"->"+TAG, "onUnbind");
        cleanup();
        return false;
    }

    /**
     * Called when service is switched off after onUnbind
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(EVIACAM.TAG+"->"+TAG, "onDestroy");
        cleanup();
    }

    /**
     * (required) This method is called back by the system when it detects an
     * AccessibilityEvent that matches the event filtering parameters specified
     * by your accessibility service.
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (mEngine != null) {
            mEngine.onAccessibilityEvent(event);
        }
    }

    /**
     * (required) This method is called when the system wants to interrupt the
     * feedback your service is providing, usually in response to a user action
     * such as moving focus to a different control. This method may be called
     * many times over the life cycle of your service.
     */
    @Override
    public void onInterrupt() {
        Log.d(EVIACAM.TAG+"->"+TAG, "onInterrupt");
    }


    @Override
    protected boolean onKeyEvent(KeyEvent event) {

            Log.d(EVIACAM.TAG+"->"+TAG, "key event:" + event.getKeyCode()+" type:"+event.getAction());
            Intent intent = new Intent("KeyEvent");
            // You can also include some extra data.
            intent.putExtra("keycode", event.getKeyCode());
            intent.putExtra("keyaction", event.getAction());

            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);

        Log.d(EVIACAM.TAG+"->"+TAG, "getenablekeyblock:"+String.valueOf(Preferences.get().getEnableBlockKey()));

        Log.d(EVIACAM.TAG+"->"+TAG, "getclickkey:"+String.valueOf(Preferences.get().getClickKey()));

        if ((Preferences.get().getEnableBlockKey() && Preferences.get().getClickKey()==event.getKeyCode()) || mBlockAllKey ){

            Log.d(EVIACAM.TAG+"->"+TAG, "key event:blocked");
            return true;
        }else {
            Log.d(EVIACAM.TAG+"->"+TAG, "key event:notblocked");
            return super.onKeyEvent(event);
        }
    }


}
