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
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.graphics.PointF;
import android.os.CountDownTimer;
import android.view.KeyEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class MouseEmulation implements MotionProcessor, OnSharedPreferenceChangeListener {
    /*
     * states of the engine
     */
    private static final int STATE_STOPPED= 0;
    private static final int STATE_RUNNING= 1;

    public static final int EMULATE_MOUSE_HWCLICK=1;
    public static final int EMULATE_MOUSE_SWIPE=2;

    // click manager
    private final MouseEmulationCallbacks mMouseEmulationCallbacks;

    // current state
    private volatile int mState= STATE_STOPPED;

    // layer for drawing the pointer and the dwell click feedback
    private PointerLayerView mPointerLayer;

    // pointer is enabled?
    private volatile boolean mPointerEnabled= true;
    
    // object which provides the logic for the pointer motion and actions
    private PointerControl mPointerControl;

    private CountDownTimer mTimer;

    // dwell clicking function
    private DwellClick mDwellClick;

    // click enabled?
    private volatile boolean mClickEnabled= true;

    //last keycode to provide click
    private volatile int mKeyCode = 0;

    //keypress time
    private volatile Long mKeyPressTime;

    //initiate click by key
    private volatile boolean forceClick = false;

    //pass extra event info: like "press and hold"
    int forceExtraEvent = 0;

    // resting mode
    private volatile boolean mRestingModeEnabled= false;
    public void setRestMode(boolean enabled) { mRestingModeEnabled= enabled; }

    /**
     * Constructor
     * @param c context
     * @param ov layout to which add other (transparent) views
     * @param om reference to the orientation manager instance
     * @param cm listener which will be called back
     */
    public MouseEmulation(Context c, OverlayView ov, OrientationManager om,
                          MouseEmulationCallbacks cm) {
        mMouseEmulationCallbacks = cm;

        // pointer layer (should be the last one)
        mPointerLayer= new PointerLayerView(c);
        ov.addFullScreenLayer(mPointerLayer);

        LocalBroadcastManager.getInstance(mPointerLayer.getContext()).registerReceiver(
                mMessageReceiver, new IntentFilter("KeyEvent"));

        /*
         * control stuff
         */
        mPointerControl= new PointerControl(mPointerLayer, om);
        mDwellClick= new DwellClick(c);

        // register preference change listener
        Preferences.get().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);

        //SharedPreferences sp=  Preferences.get().getSharedPreferences();
        // get values from shared resources
        //Boolean enableclick= sp.getBoolean(Preferences.KEY_ENABLE_DWELL,false);

        //if (enableclick)  { enableClick(); }
        //else disableClick();

    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
                                          String key) {
        //if (key.equals(Preferences.KEY_ENABLE_DWELL) ) {
          //  SharedPreferences sp=  Preferences.get().getSharedPreferences();
            // get values from shared resources
            //Boolean enableclick= sp.getBoolean(Preferences.KEY_ENABLE_DWELL,false);

            //if (enableclick)  { enableClick(); }
            //else disableClick();
        //}
    }

    /**
     * Enable the pointer function
     */
    public void enablePointer() {
        if (!mPointerEnabled) {
            mPointerControl.reset();
            mPointerLayer.setVisibility(View.VISIBLE);

            mPointerEnabled= true;
        }
    }

    /**
     * Disable the pointer function
     */
    public void disablePointer() {
        if (mPointerEnabled) {
            mPointerLayer.setVisibility(View.INVISIBLE);

            mPointerEnabled= false;
        }
    }

    /**
     * Enable the pointer function
     */
    public void enableClick() {
        if (!mClickEnabled) {
            if (mDwellClick!=null){
                mDwellClick.reset();
            }
                //SharedPreferences sp=  Preferences.get().getSharedPreferences();
                // get values from shared resources
                //Boolean enableclick= sp.getBoolean(Preferences.KEY_ENABLE_DWELL,false);

                //if (enableclick) {
                    mClickEnabled = true;
                //}

        }
    }


    /**
     * Disable the pointer function
     */
    public void disableClick() {
        mClickEnabled= false;
    }

    @Override
    public void start() {
        if (mState == STATE_RUNNING) return;

        /* Pointer layer */
        mPointerControl.reset();
        mPointerLayer.setVisibility(mPointerEnabled? View.VISIBLE : View.INVISIBLE);

        /* Click */
        mDwellClick.reset();

        mState = STATE_RUNNING;
    }

    @Override
    public void stop() {
        if (mState != STATE_RUNNING) return;

        mPointerLayer.setVisibility(View.INVISIBLE);

        mState = STATE_STOPPED;
    }

    @Override
    public void cleanup() {
        stop();

        if (mDwellClick!= null) {
            mDwellClick.cleanup();
            mDwellClick = null;
        }

        if (mPointerControl!= null) {
            mPointerControl.cleanup();
            mPointerControl = null;
        }

        if (mPointerLayer!= null) {
            mPointerLayer.cleanup();
            mPointerLayer = null;
        }
    }


    private BroadcastReceiver mMessageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int kc = intent.getIntExtra("keycode",0);
            int ka = intent.getIntExtra("keyaction",0);

            SharedPreferences sp =  Preferences.get().getSharedPreferences();
            // get values from shared resources
            int storedKey = sp.getInt(Preferences.KEY_KEY_CLICK,0);

            if (kc==storedKey) {

                if (ka == KeyEvent.ACTION_DOWN) {
                    if (mKeyCode == 0) {
                        mKeyCode = kc;
                        mKeyPressTime = System.currentTimeMillis();

                        mTimer = new CountDownTimer(Preferences.get().getKeypressTime()*100*3,Preferences.get().getKeypressTime()*100) {
                            @Override
                            public void onTick(long millisUntilFinished) {
                                //if already up, no nothing
                                if (mKeyCode == 0) {
                                    return;
                                }

                                Long currTime = System.currentTimeMillis();
                                Long elapsedTime = currTime - mKeyPressTime;

                                //if elapsed time > keypresstime -> set swipe action automatic
                                if (elapsedTime>=(Preferences.get().getKeypressTime()*100)){

                                    forceClick=true;
                                    forceExtraEvent=EMULATE_MOUSE_SWIPE;
                                    mKeyCode = 0;


                                }
                            }

                            @Override
                            public void onFinish() {
                                mKeyCode = 0;
                            }

                        }.start();

                    }
                }
                if (ka == KeyEvent.ACTION_UP) {
                    if (mKeyCode == kc) {

                        Long currTime = System.currentTimeMillis();
                        Long elapsedTime = currTime - mKeyPressTime;

                        //Log.d("elapse:","el:"+String.valueOf(elapsedTime));
                        if (elapsedTime<(Preferences.get().getKeypressTime()*100)){
                            forceClick=true;
                            forceExtraEvent=EMULATE_MOUSE_HWCLICK;

                        }else{
                            forceClick=true;
                            forceExtraEvent=EMULATE_MOUSE_SWIPE;
                        }
                        mTimer.cancel();

                        mKeyCode = 0;



                    }
                }
            }
        }
    };

    /**
     * Process incoming motion
     *
     * @param motion motion vector
     *
     * NOTE: this method can be called from a secondary thread
     */
    @Override
    public void processMotion(@NonNull PointF motion) {
        if (mState != STATE_RUNNING) return;

        // update pointer location given motion
        mPointerControl.updateMotion(motion);
        
        // get new pointer location
        PointF pointerLocation= mPointerControl.getPointerLocation();



        /* check if click generated */
        boolean clickGenerated= false;
        if (mClickEnabled && mMouseEmulationCallbacks.isClickable(pointerLocation)) {
            clickGenerated= mDwellClick.updatePointerLocation(pointerLocation);
        }
        else {
            mDwellClick.reset();
        }

        if (!clickGenerated){
            //check if click was initiated caused by a key event
            if (forceClick) {
                forceClick=false;
                clickGenerated=true;
            }
        }
        
        // update pointer position and click progress
        mPointerLayer.updatePosition(pointerLocation);

        // update resting mode appearance if needed
        mPointerLayer.setRestModeAppearance(mRestingModeEnabled);

        /* update dwell click progress */
        if (mClickEnabled) {
            mPointerLayer.updateClickProgress(mDwellClick.getClickProgressPercent());
        }
        else {
            mPointerLayer.updateClickProgress(0);
        }



        // make sure visible changes are updated
        mPointerLayer.postInvalidate();

        int type = mMouseEmulationCallbacks.onMouseEvent(pointerLocation, clickGenerated, forceExtraEvent);
        forceExtraEvent=0;
        //if hw key is pressed, indicate it
        if (mKeyCode!=0) {
            mPointerLayer.updateIndicator(PointerLayerView.INDICATOR_CLICK);
        } else {
            mPointerLayer.updateIndicator(type);
        }
    }
}
