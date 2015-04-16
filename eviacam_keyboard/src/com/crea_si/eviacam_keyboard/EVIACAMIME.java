package com.crea_si.eviacam_keyboard;

import android.util.Log;

/***
 * Constants and common stuff 
 */
public class EVIACAMIME {
    public static final String TAG = EVIACAMIME.class.getSimpleName();
    
    public static final boolean DEBUG = BuildConfig.DEBUG;
    
    public static void debug(String message) {
        if ( DEBUG ) {
            int pid= android.os.Process.myPid();
            Log.d(TAG, String.format("[%d]: %s", pid, message));
        }
    }
    
    public static void warning(String message) {
        int pid= android.os.Process.myPid();
        Log.w(TAG, String.format("[%d]: %s", pid, message));
    }
}