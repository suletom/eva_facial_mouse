/*
 * Enable Viacam for Android, a camera based mouse emulator
 *
 * Copyright (C) 2015 Cesar Mauri Loba (CREA Software Systems)
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

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.crea_si.eviacam.R;
import com.crea_si.eviacam.util.PhysicalOrientation;

/***
 * Root relative layout which is overlaid over the entire screen of the device an to which
 * other views can be added.
 */
public class OverlayView extends RelativeLayout implements OrientationChange  {

    private PhysicalOrientation mOrientationManager = null;

    private BroadcastReceiver mMessageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Get extra data included in the Intent
            Integer kc = intent.getIntExtra("dir",0);

            onOrientationChanged(kc);

        }
    };

    OverlayView(Context c) {
        super(c);

        WindowManager.LayoutParams feedbackParams = new WindowManager.LayoutParams();

        feedbackParams.setTitle("FeedbackOverlay");

        // Transparent background
        feedbackParams.format = PixelFormat.TRANSLUCENT;

        /*
         * Type of window- Create an always on top window
         *
         * TYPE_PHONE: These are non-application windows providing user interaction with the
         *      phone (in particular incoming calls). These windows are normally placed above
         *      all applications, but behind the status bar. In multiuser systems shows on all
         *      users' windows.
         *
         * TYPE_SYSTEM_OVERLAY: system overlay windows, which need to be displayed on top of
         *      everything else. These windows must not take input focus, or they will interfere
         *      with the keyguard. In multiuser systems shows only on the owning user's window
         *
         *  For future versions check TYPE_ACCESSIBILITY_OVERLAY
         *
         */
        feedbackParams.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;

        /*
         * Type of window. Whole screen is covered (including status bar)
         *
         * FLAG_NOT_FOCUSABLE: this window won't ever get key input focus, so the user can not
         *      send key or other button events to it. It can use the full screen for its content
         *      and cover the input method if needed
         *
         * FLAG_LAYOUT_IN_SCREEN: place the window within the entire screen, ignoring decorations
         *      around the border (such as the status bar)
         *
         * Additinal flags needed to render above status and navigation bars
         */


        int sh = getScreenSize(this.getContext(), false);
        int sw = getScreenSize(this.getContext(), true);

        feedbackParams.flags =
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                        WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;


        //feedbackParams.width = WindowManager.LayoutParams.MATCH_PARENT;
        //MATCH_PARENT can't be used, we need the whole screen size (inculding for example navbar) positioned to top


        WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);

        int orientation = getContext().getResources().getConfiguration().orientation;

        Log.d(EVIACAM.TAG, "OverlayView: or:" + String.valueOf(orientation));
        Log.d(EVIACAM.TAG, "OverlayView: iew:" + String.valueOf(sw));
        Log.d(EVIACAM.TAG, "OverlayView: ieh:" + String.valueOf(sh));


        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            feedbackParams.height = sw;
            feedbackParams.width = LayoutParams.MATCH_PARENT;
            feedbackParams.gravity = Gravity.TOP;

        } else {
            feedbackParams.height = LayoutParams.MATCH_PARENT;
            feedbackParams.width = sw;
            feedbackParams.gravity = Gravity.LEFT;
        }

        wm.addView(this, feedbackParams);

        LocalBroadcastManager.getInstance(getContext()).registerReceiver(
                mMessageReceiver, new IntentFilter("orientation"));



    }



    @Override
    public void onOrientationChanged(int i) {


        ViewGroup.LayoutParams lp = getLayoutParams();
        int gw = getScreenSize(getContext(),true);
        int gh = getScreenSize(getContext(),false);

        Log.d(EVIACAM.TAG, "OverlayView: or:"+String.valueOf(i));
        Log.d(EVIACAM.TAG, "OverlayView: dew:"+String.valueOf(gw));
        Log.d(EVIACAM.TAG, "OverlayView: deh:"+String.valueOf(gh));

        if (i==0) {
            Log.d(EVIACAM.TAG, "OverlayView: orientation: portrait");
            lp.height = gw;
            lp.width = gh;

            ((WindowManager.LayoutParams)lp).gravity = Gravity.TOP;
        }else{
            Log.d(EVIACAM.TAG, "OverlayView: orientation: landscpae");
            lp.height = gh;
            lp.width = gw;
            ((WindowManager.LayoutParams)lp).gravity = Gravity.LEFT;
        }

        Log.d(EVIACAM.TAG, "OverlayView: setheight"+String.valueOf(lp.height));
        Log.d(EVIACAM.TAG, "OverlayView: setwidth"+String.valueOf(lp.width));

        setLayoutParams(lp);
        WindowManager wm= (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        try {
            wm.updateViewLayout(this, lp);
        }catch (Exception e){
            Log.e(EVIACAM.TAG, "OverlayView: updateViewLayout error:"+e.toString());
        }

    }

    private int getScreenSize(Context context, Boolean worh) {
        int x, y;
        WindowManager wm = ((WindowManager)
                context.getSystemService(Context.WINDOW_SERVICE));
        Display display = wm.getDefaultDisplay();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR2) {
            Point screenSize = new Point();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                display.getRealSize(screenSize);
                x = screenSize.x;
                y = screenSize.y;
            } else {
                display.getSize(screenSize);
                x = screenSize.x;
                y = screenSize.y;
            }
        } else {
            x = display.getWidth();
            y = display.getHeight();
        }

        //int width = (orientation == Configuration.ORIENTATION_PORTRAIT ? x : y);
        //int height = (orientation == Configuration.ORIENTATION_PORTRAIT ? y : x);
        int width = x>y?x:y;
        int height = x>y?y:x;

        if (worh) {
            return width;
        }else{
            return height;
        }
    }

    void cleanup() {
        WindowManager wm= (WindowManager) this.getContext().getSystemService(Context.WINDOW_SERVICE);
        wm.removeViewImmediate(this);

        Log.d(EVIACAM.TAG, "OverlayView: finish destroyOverlay");
    }
    
    public void addFullScreenLayer (View v) {

        RelativeLayout.LayoutParams lp= new RelativeLayout.LayoutParams(this.getWidth(), this.getHeight());
        lp.width= RelativeLayout.LayoutParams.MATCH_PARENT;
        lp.height= RelativeLayout.LayoutParams.MATCH_PARENT;

        v.setLayoutParams(lp);
        this.addView(v);
    }

}





