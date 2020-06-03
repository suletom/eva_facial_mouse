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
package com.crea_si.eviacam.common;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.CompoundButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.crea_si.eviacam.BuildConfig;
import com.crea_si.eviacam.R;
import com.crea_si.input_method_aidl.IClickableIME;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles the communication with the EVA Keyboard and provides other
 * utilities related with keyboards
 */

public class InputMethodAction implements ServiceConnection {
    private static final String TAG = "InputMethodAction";

    /* Binding identifiers */
    private static final String REMOTE_PACKAGE= "com.crea_si.eviacam.service";
    private static final String REMOTE_ACTION= "com.crea_si.softkeyboard.RemoteBinderService";

    /* Substring identifying custom and GBoard input methods */
    private static final String GBOARD_IME = "com.android.inputmethod.";
    private static final String VOICE_IME = "com.google.android.voicesearch.ime.";
    private static final String CUSTOM_IME = "com.crea_si.eviacam.service";

    // period (in milliseconds) to try to rebind again to the IME
    private static final int BIND_RETRY_PERIOD = 2000;

    private static final int ALERT3_UID = 3;
    private static final int ALERT4_UID = 4;
    private static final int ALERT5_UID = 5;
    private static final int ALERT6_UID = 6;
    private static final int ALERT7_UID = 11;
    
    private final Context mContext;

    // binder (proxy) with the remote input method service
    private volatile IClickableIME mRemoteService;
    
    // time stamp of the last time the thread ran
    private long mLastBindAttemptTimeStamp = 0;

    // show the remainder for GBoard installation? Use AtomicBoolean instead of MutableBoolean
    // because the later is not available since API 21+
    // change: this flag is used to "show/not remind" for the all the keyboard related suggestions!
    private static AtomicBoolean mShowGBoardInstallationReminder= new AtomicBoolean(true);
    //private static AtomicBoolean mCompMode= new AtomicBoolean(false);


    private final Handler mHandler= new Handler();

    private BroadcastReceiver mAlertReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            // Get extra data included in the Intent
            int res = intent.getIntExtra("result",0);
            int  ch = intent.getIntExtra("checkbox",0);
            int uid = intent.getIntExtra("uid",0);

            if (uid==ALERT3_UID) {
                if (res== AlertDialog.ALERTDIALOG_POSITIVERESULT){
                    textViewFocusedSequence2();
                }

                if (ch== AlertDialog.ALERTDIALOG_CHECKBOX){
                    mShowGBoardInstallationReminder.set(false);
                }else{
                    mShowGBoardInstallationReminder.set(true);
                }

            }
            if (uid==ALERT4_UID) {
                if (res== AlertDialog.ALERTDIALOG_POSITIVERESULT){
                    displayIMESettings(mContext);
                }

            }
            if (uid==ALERT5_UID) {
                if (res== AlertDialog.ALERTDIALOG_POSITIVERESULT){
                    String appPackageName= "com.google.android.inputmethod.latin";
                    try {
                        Intent intent1= new Intent(Intent.ACTION_VIEW,
                                Uri.parse("market://details?id=" + appPackageName));
                        int flags= intent1.getFlags();
                        flags|= Intent.FLAG_ACTIVITY_NEW_TASK;
                        intent1.setFlags(flags);

                        mContext.startActivity(intent1);
                    } catch (android.content.ActivityNotFoundException e) {
                        Intent intent1= new Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://play.google.com/store/apps/details?id=" +
                                        appPackageName));
                        int flags= intent1.getFlags();
                        flags|= Intent.FLAG_ACTIVITY_NEW_TASK;
                        intent1.setFlags(flags);

                        mContext.startActivity(intent1);
                    }
                }

            }
            if (uid==ALERT6_UID) {
                if (res== AlertDialog.ALERTDIALOG_POSITIVERESULT){
                    displayIMESettings(mContext);
                }

            }

            if (uid==ALERT7_UID) {
                if (res== AlertDialog.ALERTDIALOG_NEGATIVERESULT){
                    displayIMEPicker(mContext);
                }

            }

        }
    };

    /**
     * Constructor
     * @param c context
     */
    public InputMethodAction(@NonNull Context c) {
        mContext= c;

        // attempt to bind with IME
        keepBindAlive();

        LocalBroadcastManager.getInstance(c).registerReceiver(
                mAlertReceiver, new IntentFilter("alertdialogreply"));

    }

    /**
     * Free resources
     */
    public void cleanup() {
        if (mRemoteService == null) return;
        
        mContext.unbindService(this);
        mRemoteService= null;
    }
    
    /**
     * Bind to the remote IME when needed
     */
    private void keepBindAlive() {
        if (mRemoteService != null) return;
        
        /*
          no bind available, try to establish it if enough
          time passed since the last attempt
         */
        long tstamp= System.currentTimeMillis();
        
        if (tstamp - mLastBindAttemptTimeStamp < BIND_RETRY_PERIOD) {
            return;
        }

        mLastBindAttemptTimeStamp = tstamp;

        Log.d(EVIACAM.TAG+"->"+TAG, "Attempt to bind to remote IME");
        Intent intent= new Intent(REMOTE_ACTION);
        intent.setPackage(REMOTE_PACKAGE);
        try {
            if (!mContext.bindService(intent, this, Context.BIND_AUTO_CREATE)) {
                Log.d(EVIACAM.TAG+"->"+TAG, "Cannot bind remote IME");
            }
        }
        catch(SecurityException e) {
            Log.e(EVIACAM.TAG+"->"+TAG, "Cannot bind remote IME. Security exception.");
        }
    }
    
    @Override
    public void onServiceConnected(ComponentName className, IBinder service) {
        // This is called when the connection with the service has been
        // established, giving us the object we can use to
        // interact with the service.
        Log.d(EVIACAM.TAG+"->"+TAG, "remoteIME:onServiceConnected: " + className.toString());
        mRemoteService = IClickableIME.Stub.asInterface(service);
    }

    @Override
    public void onServiceDisconnected(ComponentName className) {
        // This is called when the connection with the service has been
        // unexpectedly disconnected -- that is, its process crashed.
        Log.d(EVIACAM.TAG+"->"+TAG, "remoteIME:onServiceDisconnected");
        mContext.unbindService(this);
        mRemoteService = null;
        keepBindAlive();
    }

    /** Try to click on an IME key
     * @param x - abscissa coordinate of the point (relative to the screen)
     * @param y - ordinate coordinate of the point (relative to the screen)
     * @return true if the point is within view bounds of the IME, false otherwise
     */
    public boolean click(int x, int y) {
        if (mRemoteService == null) {
            if (BuildConfig.DEBUG) {
                Log.d(EVIACAM.TAG+"->"+TAG, "InputMethodAction: click: no remote service available");
            }
            return false;
        }

        try {
            return mRemoteService.click(x, y);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Display the input method picker
     * @param c context
     */
    private static void displayIMEPicker(@NonNull Context c) {
        InputMethodManager imm=
                (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (null != imm) {
            imm.showInputMethodPicker();
        }
    }

    /**
     * Display the input method settings
     */
    private static void displayIMESettings(@NonNull Context c) {
        final Intent intent = new Intent();
        intent.setAction(Settings.ACTION_INPUT_METHOD_SETTINGS);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(intent);
    }

    public void textViewFocusedSequence2(){

        if (!checkCustomKeyboardEnabled(mContext, mHandler)) return;
        if (!checkGBoardEnabled(mContext, mHandler, mShowGBoardInstallationReminder)) return;

        boolean rightKeyboardSelected= isCustomKeyboardSelected(mContext);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            rightKeyboardSelected= rightKeyboardSelected || isGBoardSelected(mContext) ||
                    isVoiceSelected(mContext);
        }

        if (!rightKeyboardSelected) {
            displayIMEPicker(mContext);
        }

        try {
            mRemoteService.openIME();
        } catch (RemoteException e) {
            // Nothing to be done
            Log.e(EVIACAM.TAG+"->"+TAG, "InputMethodAction: exception while trying to open IME");
        }


    }

    /**
     * Sequence of operations to be executed when a text view is focused
     */
    public void textViewFocusedSequence() {


        /*textview focus?

        1. vagy EVA vagy Gboard aktiv-e?
        i: ok
        n: kérdezünk: Kiválasztod az evat vagy  gboardot?
            n: -> ha megjegyeztette akkor ennyi többet nem piszkáljuk a munkamenetben
            i: mindkettőt végigkérdezzük felteszi-e, aztán beállítja-e

        */
        if (mShowGBoardInstallationReminder.get()==false) {
            return;
        }

        boolean rightKeyboardSelected= isCustomKeyboardSelected(mContext);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            rightKeyboardSelected= rightKeyboardSelected || isGBoardSelected(mContext) ||
                    isVoiceSelected(mContext);
        }

        if (!rightKeyboardSelected) {
            String msg;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
                msg= mContext.getResources().getString(
                        R.string.service_dialog_eva_keyboard_not_selected);
            }
            else {
                msg= mContext.getResources().getString(
                        R.string.service_dialog_eva_keyboard_gboard_not_selected);
            }
            displayInformationDialog(ALERT3_UID,mContext, mHandler, msg, null,mShowGBoardInstallationReminder);

            return;
        }

    }

    /**
     * Close the EVA keyboard. Has no effect is EVA keyboard is not selected.
     */
    public void closeIME() {
        if (mRemoteService == null) {
            if (BuildConfig.DEBUG) {
                if (BuildConfig.DEBUG) {
                    Log.d(EVIACAM.TAG+"->"+TAG, "InputMethodAction: closeIME: no remote service available");
                }
            }
            keepBindAlive();
            return;
        }

        // Does not check mInputMethodManager.isActive because does not mean IME is open
        try {
            mRemoteService.closeIME();
        } catch (RemoteException e) {
            // Nothing to be done
            Log.d(EVIACAM.TAG+"->"+TAG, "InputMethodAction: exception while trying to close IME");
        }
    }

    /**
     * Sequence of actions when keyboard menu option is selected
     */
    public void dockMenuKeyboardSequence() {

        checkCustomKeyboardEnabled(mContext, mHandler);

        if (!isCustomKeyboardSelected(mContext)) {
            displayIMEPicker(mContext);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    EVIACAM.LongToast(mContext, R.string.service_dialog_eva_keyboard_not_selected);
                }
            });

            /*
             * Poll until the custom keyboard has been selected and open it
             */
            Runnable pollActiveKeyboard= new Runnable() {
                @Override
                public void run() {
                    for (int i= 0; i< 80; i++) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            // Do nothing
                        }
                        IClickableIME s= mRemoteService;
                        //Log.d(EVIACAM.TAG+"->"+TAG, i + ": s= " + s + "selected: " + isCustomKeyboardSelected(mContext));

                        if (null!= s && isCustomKeyboardSelected(mContext)) {
                            try {
                                s.openIME();
                                break;
                            } catch (RemoteException e) {
                                // Nothing to be done
                            }
                        }
                    }
                }
            };
            new Thread(pollActiveKeyboard).start();
        }
        else {
            // Does not check mInputMethodManager.isActive because does not mean IME is open
            try {
                mRemoteService.toggleIME();
            } catch (RemoteException e) {
                // Nothing to be done
                Log.e(EVIACAM.TAG+"->"+TAG, "InputMethodAction: exception while trying to toggle IME");
            }

        }

    }

    /**
     * Check if the custom keyboard is enabled and is the selected one
     * @param c context
     * @return true if enabled
     */
    public static boolean isCustomKeyboardSelected(@NonNull Context c) {
        String pkgName= Settings.Secure.getString(c.getContentResolver(),
                                                  Settings.Secure.DEFAULT_INPUT_METHOD);
        return null != pkgName && pkgName.contains(CUSTOM_IME);
    }

    /**
     * Check if the custom keyboard is enabled and is the selected one
     * @param c context
     * @return true if enabled
     */
    private static boolean isGBoardSelected(@NonNull Context c) {
        String pkgName = Settings.Secure.getString(c.getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD);
        return null != pkgName && pkgName.contains(GBOARD_IME);
    }

    /**
     * Check if the voice keyboard is enabled and is the selected one
     * @param c context
     * @return true if enabled
     */
    private static boolean isVoiceSelected(@NonNull Context c) {
        String pkgName = Settings.Secure.getString(c.getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD);
        return null != pkgName && pkgName.contains(VOICE_IME);
    }

    /**
     * Check if the keyboard identified by the substring is installed
     * @param c context
     * @param subString substring the the ID of the keyboard should contain
     * @return true if the specified keyboard is installed
     */
    private static boolean isKeyboardInstalled(@NonNull Context c, @NonNull String subString) {
        InputMethodManager imeManager = (InputMethodManager)
                c.getSystemService(Context.INPUT_METHOD_SERVICE);
        List<InputMethodInfo> inputMethods = imeManager.getInputMethodList();

        for (InputMethodInfo imi : inputMethods) {
            String id= imi.getId();
            if (null != id && id.contains(subString)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if the GBoard keyboard is enabled
     * @param c context
     * @return true if the GBoard keyboard is enabled
     */
    private static boolean isGBoardInstalled(@NonNull Context c) {
        return isKeyboardInstalled(c, GBOARD_IME);
    }

    /**
     * Check if the keyboard identified by the substring is enabled
     * @param c context
     * @param subString substring the the ID of the keyboard should contain
     * @return true if the specified keyboard is enabled
     */
    private static boolean isKeyboardEnabled(@NonNull Context c, @NonNull String subString) {
        InputMethodManager imeManager = (InputMethodManager)
                c.getSystemService(Context.INPUT_METHOD_SERVICE);
        List<InputMethodInfo> inputMethods = imeManager.getEnabledInputMethodList();

        for (InputMethodInfo imi : inputMethods) {
            String id= imi.getId();
            if (null != id && id.contains(subString)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if the custom keyboard is enabled
     * @param c context
     * @return true if the custom keyboard is enabled
     */
    private static boolean isCustomKeyboardEnabled(@NonNull Context c) {
        return isKeyboardEnabled(c, CUSTOM_IME);
    }

    /**
     * Check if the GBoard keyboard is enabled
     * @param c context
     * @return true if the GBoard keyboard is enabled
     */
    private static boolean isGBoardEnabled(@NonNull Context c) {
        return isKeyboardEnabled(c, GBOARD_IME);
    }

    private static void displayInformationDialog(int id, @NonNull final Context c,
                                                 @NonNull final Handler h,
                                                 @NonNull final CharSequence msg,
                                                 @NonNull final Runnable r,
                                                 @Nullable final AtomicBoolean showDialog) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            displayInformationDialog0(id ,c, msg, r, showDialog);
        }
        else {
            h.post(new Runnable() {
                @Override
                public void run() {
                    displayInformationDialog0(id, c, msg, r, showDialog);
                }
            });
        }
    }

    private static void displayInformationDialog0(int id, @NonNull Context c, @NonNull CharSequence msg,
                                                  @NonNull final Runnable r,
                                                  @Nullable final AtomicBoolean showDialog) {
        /*
        View checkBoxView= null;
        if (null != showDialog) {
            checkBoxView = View.inflate(c, R.layout.input_method_action_help, null);
            CheckBox checkBox = (CheckBox) checkBoxView.findViewById(R.id.checkbox);
            checkBox.setChecked(!showDialog.get());
            checkBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    showDialog.set(!isChecked);
                }
            });
        }

         */
    /*
        AlertDialog.Builder builder = new AlertDialog.Builder(c);
        builder.setTitle(c.getText(R.string.app_name));
        builder.setMessage(msg);
        if (null != checkBoxView) {
            builder.setView(checkBoxView);
        }
        builder.setPositiveButton(c.getText(android.R.string.ok),
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        r.run();
                    }
                });
        builder.setNeutralButton(c.getText(R.string.service_dialog_ime_action_help_not_now), null);

        AlertDialog ad = builder.create();

        //noinspection ConstantConditions
        ad.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        ad.show();
*/
        Intent inte = new Intent("alertdialog");
        inte.putExtra("uid",id);
        inte.putExtra("title",c.getText(R.string.app_name));
        inte.putExtra("text",msg);

        if (showDialog!=null) {
            inte.putExtra("checkbox", 1);
            inte.putExtra("checkboxtext",c.getText(R.string.service_dialog_ime_action_help_dont_remind));
        }

        inte.putExtra("positivebuttontext",c.getText(android.R.string.ok));
        inte.putExtra("negativebuttontext",c.getText(R.string.service_dialog_ime_action_help_not_now));


        LocalBroadcastManager.getInstance(c).sendBroadcast(inte);

    }

    /**
     * Checks whether the EVA custom keyboard is enabled and asks to do so when not
     *
     * @param c context
     * @return true if EVA custom keyboard is enabled
     */
    private static boolean checkCustomKeyboardEnabled(@NonNull final Context c,
                                                      @NonNull Handler h) {


        if (isCustomKeyboardEnabled(c)) {
            return true;
        }

        displayInformationDialog(ALERT4_UID,c, h, c.getResources().getString(
                R.string.service_dialog_eva_keyboard_not_enabled),
                new Runnable() {
                    @Override
                    public void run() {
                        displayIMESettings(c);
                    }
                }, null);

        return false;
    }

    /**
     * Checks whether the GBoard or any equivalent Google keyboard is installed and enabled.
     * When not is the case, guides the user to do so.
     *
     * @param c context
     * @param installCheckBoxState not used
     * @return true if GBoard is installed and enabled or if API < 22 or
     *
     * Remarks: for devices with API below 22 the GBoard keyboard does not respond to
     * performAction(), so do not ask the user to install and enable it
     */
    private boolean checkGBoardEnabled(@NonNull final Context c, @NonNull Handler h,
                                       @Nullable AtomicBoolean installCheckBoxState) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            return true;
        }


        /* Device with API 22+ */

        boolean gBoardInstalled= isGBoardInstalled(c);

        if (!gBoardInstalled) {
            /* Not installed */
            displayInformationDialog(ALERT5_UID,c, h, c.getResources().getString(
                    R.string.service_dialog_gboard_not_installed), new Runnable() {
                @Override
                public void run() {
                    String appPackageName= "com.google.android.inputmethod.latin";
                    try {
                        Intent intent= new Intent(Intent.ACTION_VIEW,
                                Uri.parse("market://details?id=" + appPackageName));
                        int flags= intent.getFlags();
                        flags|= Intent.FLAG_ACTIVITY_NEW_TASK;
                        intent.setFlags(flags);

                        c.startActivity(intent);
                    } catch (android.content.ActivityNotFoundException e) {
                        Intent intent= new Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://play.google.com/store/apps/details?id=" +
                                        appPackageName));
                        int flags= intent.getFlags();
                        flags|= Intent.FLAG_ACTIVITY_NEW_TASK;
                        intent.setFlags(flags);

                        c.startActivity(intent);
                    }
                }
            }, null);

            return false;
        }

        if (!isGBoardEnabled(c)) {
            /* Not enabled */
            displayInformationDialog(ALERT6_UID,c, h, c.getResources().getString(
                    R.string.service_dialog_gboard_not_enabled),
                    new Runnable() {
                        @Override
                        public void run() {
                            displayIMESettings(c);
                        }
                    }, null);
            return false;
        }

        return true;
    }
}
