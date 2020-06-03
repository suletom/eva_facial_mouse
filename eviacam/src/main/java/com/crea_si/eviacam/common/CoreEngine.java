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

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PointF;
import android.os.Handler;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.crea_si.eviacam.R;
import com.crea_si.eviacam.camera.Camera;
import com.crea_si.eviacam.camera.CameraLayerView;
import com.crea_si.eviacam.camera.FrameProcessor;

import org.acra.ACRA;
import org.opencv.android.CameraException;
import org.opencv.core.Mat;

import java.util.ArrayDeque;

/**
 * Provides an abstract implementation for the Engine interface. The class is in charge of:
 *
 * - engine initialization and state management
 * - camera and image processing to detect face and track motion
 * - UI: main overlay and camera viewer
 *
 */
public abstract class CoreEngine implements Engine, FrameProcessor,
        PowerManagement.OnScreenStateChangeListener {

    private static final String TAG = "CoreEngine";
    // stores when the last detection of a face occurred
    private final FaceDetectionCountdown mFaceDetectionCountdown = new FaceDetectionCountdown();

    // handler to run things on the main thread
    private final Handler mHandler= new Handler();

    // power management stuff
    private PowerManagement mPowerManagement;

    /* current engine state */
    private volatile int mCurrentState = STATE_DISABLED;
    @Override
    public int getState() {
        return mCurrentState;
    }

    // state before switching screen off
    private int mSaveState= -1;

    /* splash screen has been displayed? */
    private boolean mSplashDisplayed = false;

    /* listener to notify when the initialization is done */
    private OnInitListener mOnInitListener;

    /* reference to the service which started the engine */
    private Service mService;

    /* root overlay view */
    private OverlayView mOverlayView;
    private AlertDialog mAlertView;
    protected OverlayView getOverlayView() { return mOverlayView; }

    /* the camera viewer */
    private CameraLayerView mCameraLayerView;

    /* object in charge of capturing & processing frames */
    private Camera mCamera;

    /* object which encapsulates rotation and orientation logic */
    private OrientationManager mOrientationManager;
    protected OrientationManager getOrientationManager() { return mOrientationManager; }

    /* Last time a face has been detected */
    private volatile long mLastFaceDetectionTimeStamp;

    /* When the engine is wating for the completion of some operation */
    private boolean mWaitState = false;

    /* Store requests when the engine is waiting for the completion of a previous one.
       These stored requests will be executed eventually in the order as arrived.
       This is needed because some operations (for instance, start or stop). */
    private ArrayDeque<RunnableNamed> mPendingRequests= new ArrayDeque<>();

    /* Abstract methods to be implemented by derived classes */

    /**
     * Called just before the initialization is finished
     *
     * @param service service which started the engine
     */
    protected abstract void onInit(Service service);

    /**
     * Called at the beginning of the cleanup sequence
     */
    protected abstract void onCleanup();

    /**
     * Called at the beginning of the start sequence
     *
     * @return should return false when something went wrong to abort start sequence
     */
    protected abstract boolean onStart();

    /**
     * Called at the end of the stop sequence
     */
    protected abstract void onStop();

    /**
     * Called at the end of the pause sequence
     */
    protected abstract void onPause();

    /**
     * Called at the end of the standby sequence
     */
    protected abstract void onStandby();

    /**
     * Called at the beginning of the resume sequence
     */
    protected abstract void onResume();

    /**
     * Called each time a frame is processed and the engine is in one of these states:
     *     STATE_RUNNING, STATE_PAUSED or STATE_STANDBY
     *
     * @param motion motion vector, could be (0, 0) if motion not detected or the engine is
     *               paused or in standby mode
     * @param faceDetected whether or not a face was detected for the last frame, note
     *                     not all frames are checked for the face detection algorithm
     * @param state current state of the engine
     */
    protected abstract void onFrame(@NonNull PointF motion, boolean faceDetected, int state);


    @Override
    public boolean init(@NonNull Service s, @Nullable OnInitListener l) {
        if (mCurrentState != STATE_DISABLED) {
            // Already started, something went wrong
            throw new IllegalStateException();
        }

        /* Register receiver for camera restart */
        LocalBroadcastManager.getInstance(s).registerReceiver(onFullScreenActivityEnd,new IntentFilter("restartcamera"));

        mService= s;
        mOnInitListener= l;

        /* Show splash screen if not already shown. The splash screen is also used to
           request the user the required permissions to run this software.
           In the past, it was also used for OpenCV detection and installation.
           The engine initialization waits until the splash finishes. */
        if (mSplashDisplayed) return init2();
        else {
            /* Register receiver for splash finished */
            LocalBroadcastManager.getInstance(s).registerReceiver(
                    onSplashReady,
                    new IntentFilter(SplashActivity.FINISHED_INTENT_FILTER));

            /* Start splash activity */
            Intent dialogIntent = new Intent(mService, SplashActivity.class);
            dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mService.startActivity(dialogIntent);

            return true;
        }
    }

    /* Receiver which is called when the splash activity finishes */
    private BroadcastReceiver onSplashReady= new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean status= intent.getBooleanExtra(SplashActivity.KEY_STATUS, false);
            Log.d(EVIACAM.TAG+"->"+TAG, "onSplashReady: onReceive: called");

            /* Unregister receiver */
            LocalBroadcastManager.getInstance(mService).unregisterReceiver(onSplashReady);

            if (status) {
                /* Resume initialization */
                mSplashDisplayed = true;
                init2();
            }
            else {
                /* Notify failed initialization */
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mOnInitListener!= null) {
                            mOnInitListener.onInit(OnInitListener.INIT_ERROR);
                        }
                    }


                });
            }
        }
    };

    /**
     * Init phase 2: actual initialization
     */
    // TODO: remove return value
    private boolean init2() {
        mPowerManagement = new PowerManagement(mService, this);
        /*
         * Create UI stuff: root overlay and camera view
         */

        mAlertView = new AlertDialog(mService);

        mOverlayView= new OverlayView(mService);
        mOverlayView.setVisibility(View.INVISIBLE);
        
        mCameraLayerView= new CameraLayerView(mService);
        mOverlayView.addFullScreenLayer(mCameraLayerView);



        /*
         * camera and machine vision stuff
         */
        try {
            mCamera = new Camera(mService, this);
        }
        catch(CameraException e) {
            manageCameraError(e);

            /* Exception during initialization. Non recoverable. */
            cleanup();

            /* Notify whoever requested the initialization */
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mOnInitListener!= null) {
                        mOnInitListener.onInit(OnInitListener.INIT_ERROR);
                    }
                }
            });

            return false;  // abort initialization
        }
        Log.d(EVIACAM.TAG+"->"+TAG, "mCameraLayerView.addCameraSurface(mCamera.getCameraSurface())");
        mCameraLayerView.addCameraSurface(mCamera.getCameraSurface());

        // orientation manager
        mOrientationManager= new OrientationManager(
                mService,
                mCamera.getCameraFlip(),
                mCamera.getCameraOrientation());

        // initialize specific motion processor(s)
        onInit(mService);

        mCurrentState= STATE_STOPPED;
        Log.d(EVIACAM.TAG+"->"+TAG, "mCurrentState: "+getStateName());

        mSaveState= mCurrentState;

        /* Notify successful initialization */
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mOnInitListener!= null) {
                    mOnInitListener.onInit(OnInitListener.INIT_SUCCESS);
                }
            }
        });
        // TODO: remove return value
        return true;
    }

    private boolean isInWaitState() {
        return mWaitState;
    }

    /**
     * Process requests in two steps. First, the request is added to
     * a queue. After that, if the engine is not waiting for the
     * completion of a previous requests, the queue is processed
     * @param request runnable with the request
     */
    protected void processRequest (RunnableNamed request) {

        //if already in queue, remove, and add to the end: this prevents to land in inconsistent state by pressing screen off/on fast more times
        if (mPendingRequests.contains(request)) {
            Log.d(EVIACAM.TAG+"->"+TAG, "Request already in queue, deleting:"+request.getName());
            mPendingRequests.remove(request);
        }

        // Queue request
        mPendingRequests.add(request);
        dispatchRequests();
    }

    /**
     * Dispatch previously queued requests
     */
    private void dispatchRequests() {
        while (mPendingRequests.size()> 0 && !isInWaitState()) {
            RunnableNamed request = mPendingRequests.remove();
            request.run();
        }
    }

    // TODO: remove return value
    @Override
    public boolean start() {
        Log.d(EVIACAM.TAG+"->"+TAG, "CoreEngine.start");
        final RunnableNamed request= new RunnableNamedImpl() {
            @Override
            public void run() {
                doStart();
            }

            @Override
            public String getName() {
                return "start";
            }
        };
        processRequest(request);
        return true;
    }

    private void doStart() {
        Log.d(EVIACAM.TAG+"->"+TAG, "CoreEngine.doStart");
        // If not initialized just fail
        if (mCurrentState == STATE_DISABLED) {
            Log.e(EVIACAM.TAG+"->"+TAG, "Attempt to start DISABLED engine");
            return;
        }

        // If already running just return startup correct
        if (mCurrentState==STATE_RUNNING) {
            Log.d(EVIACAM.TAG, "Attempt to start already running engine");
            return;
        }

        // If paused or in standby, just resume
        if (mCurrentState == STATE_PAUSED || mCurrentState== STATE_STANDBY) {
            resume();
            return;
        }

        /* At this point means that (mCurrentState== STATE_STOPPED) */

        if (!onStart()) {
            Log.e(EVIACAM.TAG+"->"+TAG, "start.onStart failed");
            return;
        }

        mFaceDetectionCountdown.start();

        mPowerManagement.lockFullPower();         // Screen always on
        mPowerManagement.setSleepEnabled(true);   // Enable sleep call

        /* show GUI elements */
        mOverlayView.requestLayout();
        mOverlayView.setVisibility(View.VISIBLE);

        mCameraLayerView.enableDetectionFeedback();
        
        // start processing frames
        mCamera.startCamera();

        // set wait state until camera actually starts or error
        mWaitState= true;
        Log.d(EVIACAM.TAG+"->"+TAG, "mWaitState:"+String.valueOf(mWaitState));
    }

    @Override
    public void onCameraStarted() {
        Log.d(EVIACAM.TAG+"->"+TAG, "CoreEngine.onCameraStarted");
        if (mWaitState && mCurrentState == STATE_STOPPED) {
            mWaitState= false;
            Log.d(EVIACAM.TAG+"->"+TAG, "mWaitState:"+String.valueOf(mWaitState));
            mCurrentState = STATE_RUNNING;
            Log.d(EVIACAM.TAG+"->"+TAG, "mCurrentState: "+getStateName());
            dispatchRequests();
        }
        else {
            Log.e(EVIACAM.TAG+"->"+TAG, "onCameraStarted: inconsistent state (ignoring): " + getStateName());
        }
    }

    @Override
    public void pause() {
        Log.d(EVIACAM.TAG+"->"+TAG, "CoreEngine.pause");
        final RunnableNamed request= new RunnableNamedImpl() {
            @Override
            public void run() {
                doPause();
            }

            @Override
            public String getName() {
                return "pause";
            }
        };
        processRequest(request);
    }

    private String getStateName(){
        String s = new String();

        switch (mCurrentState){
            case STATE_DISABLED:
                s = "STATE_DISABLED";
                break;
            case STATE_STOPPED:
                s = "STATE_STOPPED";
                break;
            case STATE_RUNNING:
                s = "STATE_RUNNING";
                break;
            case STATE_STANDBY:
                s = "STATE_STANDBY";
                break;
            case STATE_PAUSED:
                s = "STATE_PAUSED";
                break;

        }

        return s;
    }

    private void doPause() {
        Log.d(EVIACAM.TAG+"->"+TAG, "CoreEngine.doPause");
        // If not initialized, stopped or already paused, just stop here
        if (mCurrentState == STATE_DISABLED ||
            mCurrentState == STATE_PAUSED   ||
            mCurrentState == STATE_STOPPED) return;

        /*
         * If STATE_RUNNING or STATE_STANDBY
         */
        mCameraLayerView.disableDetectionFeedback();

        mPowerManagement.unlockFullPower();

        onPause();

        mCurrentState= STATE_PAUSED;

    }

    @Override
    public void standby() {
        Log.d(EVIACAM.TAG+"->"+TAG, "CoreEngine.standby");
        final RunnableNamed request= new RunnableNamedImpl() {
            @Override
            public void run() {
                doStandby();
            }

            @Override
            public String getName() {
                return "standby";
            }
        };
        processRequest(request);
    }

    private void doStandby() {
        Log.d(EVIACAM.TAG+"->"+TAG, "CoreEngine.doStandby");
        // If not initialized, stopped or already standby, just stop here
        if (mCurrentState == STATE_DISABLED ||
            mCurrentState == STATE_STANDBY   ||
            mCurrentState == STATE_STOPPED) return;

        /*
         * If STATE_RUNNING or STATE_PAUSED
         */
        mCameraLayerView.disableDetectionFeedback();

        mPowerManagement.unlockFullPower();
        mPowerManagement.setSleepEnabled(true);   // Enable sleep call

        String t = mService.getResources().getString(R.string.service_toast_pointer_stopped_toast);

        EVIACAM.LongToast(mService, t);

        onStandby();

        mCurrentState= STATE_STANDBY;
        Log.d(EVIACAM.TAG+"->"+TAG, "mCurrentState: "+getStateName());


    }

    @Override
    public void resume() {
        Log.d(EVIACAM.TAG+"->"+TAG, "CoreEngine.resume");
        final RunnableNamed request= new RunnableNamedImpl() {
            @Override
            public void run() {
                doResume();
            }

            @Override
            public String getName() {
                return "resume";
            }
        };
        processRequest(request);
    }

    private void doResume() {
        Log.d(EVIACAM.TAG+"->"+TAG, "CoreEngine.doResume");
        if (mCurrentState != STATE_PAUSED && mCurrentState!= STATE_STANDBY) return;

        onResume();

        //mCamera.setUpdateViewer(true);
        mCameraLayerView.enableDetectionFeedback();

        mPowerManagement.lockFullPower();         // Screen always on
        mPowerManagement.setSleepEnabled(true);   // Enable sleep call

        mFaceDetectionCountdown.start();

        // make sure that UI changes during pause (e.g. docking panel edge) are applied
        mOverlayView.requestLayout();

        mCurrentState= STATE_RUNNING;
        Log.d(EVIACAM.TAG+"->"+TAG, "mCurrentState: "+getStateName());
    }    

    @Override
    public void stop() {
        Log.d(EVIACAM.TAG+"->"+TAG, "CoreEngine.stop");
        final RunnableNamed request= new RunnableNamedImpl() {
            @Override
            public void run() {
                doStop();
            }
            @Override
            public String getName() {
                return "stop";
            }
        };
        processRequest(request);
    }

    private void doStop() {
        Log.d(EVIACAM.TAG+"->"+TAG, "CoreEngine.doStop");
        if (mCurrentState == STATE_DISABLED || mCurrentState == STATE_STOPPED) {
            Log.d(EVIACAM.TAG+"->"+TAG, "Can't stop IN: "+getStateName());
            return;
        }
        mWaitState=true;
        Log.d(EVIACAM.TAG+"->"+TAG, "mWaitState:"+String.valueOf(mWaitState));

        mCamera.stopCamera(); //async -> callback onCameraStopped
        mOverlayView.setVisibility(View.INVISIBLE);

        mPowerManagement.unlockFullPower();
        mPowerManagement.setSleepEnabled(false);



    }

    /** called every time when stop finished */
    @Override
    public void onCameraStopped() {
        Log.d(EVIACAM.TAG+"->"+TAG, "CoreEngine.onCameraStopped");

        mCurrentState= STATE_STOPPED;
        Log.d(EVIACAM.TAG+"->"+TAG, "mCurrentState: "+getStateName());

        onStop();

        mWaitState=false;
        Log.d(EVIACAM.TAG+"->"+TAG, "mWaitState:"+String.valueOf(mWaitState));

        dispatchRequests();
    }


    public void onCameraErrorStopped() {
        Log.d(EVIACAM.TAG+"->"+TAG, "CoreEngine.onCameraErrorStopped");


        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mOnInitListener!= null) {
                    mOnInitListener.stop();
                }
            }

        });

        startFullScreenActivity();

    }


    /* Called during camera startup if something goes wrong */
    @Override
    public void onCameraError(@NonNull Throwable error) {
        Log.d(EVIACAM.TAG+"->"+TAG, "CoreEngine.onCameraError");
        manageCameraError(error);
        //mHandler.post(new Runnable() {
        //    @Override
        //    public void run() {
        //        if (mOnInitListener!= null) {
        //            mOnInitListener.stop();
        //        }
        //    }
        //});

    }

    @Override
    public void cleanup() {
        Log.d(EVIACAM.TAG+"->"+TAG, "CoreEngine.cleanup");
        if (mCurrentState == STATE_DISABLED) return;

        /* Stop engine immediately and purge pending requests queue */
        doStop();
        mWaitState= false;
        Log.d(EVIACAM.TAG+"->"+TAG, "mWaitState:"+String.valueOf(mWaitState));
        mPendingRequests.clear();

        // Call derived
        onCleanup();

        mCamera.cleanup();
        mCamera = null;

        mOrientationManager.cleanup();
        mOrientationManager= null;

        mCameraLayerView= null;

        mOverlayView.cleanup();
        mOverlayView= null;

        mAlertView.cleanup();
        mAlertView=null;

        mPowerManagement.cleanup();
        mPowerManagement = null;

        mCurrentState= STATE_DISABLED;
        Log.d(EVIACAM.TAG+"->"+TAG, "mCurrentState: "+getStateName());

        mFaceDetectionCountdown.cleanup();
    }



    @Override
    public boolean isReady() {
        return (mCurrentState != STATE_DISABLED);
    }

    @Override
    public long getFaceDetectionElapsedTime() {
        if (mCurrentState == STATE_DISABLED || mCurrentState == STATE_STOPPED) return 0;
        return System.currentTimeMillis() - mLastFaceDetectionTimeStamp;
    }

    @Override
    public void updateFaceDetectorStatus(FaceDetectionCountdown fdc) {
        mCameraLayerView.updateFaceDetectorStatus(fdc);
    }

    /**
     * Called when screen goes ON or OFF
     */
    @Override
    public void onOnScreenStateChange() {
        Log.d(EVIACAM.TAG+"->"+TAG, "CoreEngine.onOnScreenStateChanged");

        final RunnableNamed request= new RunnableNamedImpl() {
            @Override
            public void run() {
                doOnScreenStateChange();
            }
            @Override
            public String getName() {
                return "onOnScreenStateChange";
            }
        };
        processRequest(request);
    }



    private void doOnScreenStateChange() {
        Log.d(EVIACAM.TAG+"->"+TAG, "CoreEngine.doOnScreenStateChange");

        if (mPowerManagement.getScreenOn()) {
            // Screen switched on
            if (mSaveState == Engine.STATE_RUNNING ||  mSaveState == Engine.STATE_STANDBY) {

                start();

            }else if (mSaveState == Engine.STATE_PAUSED) {
                start();
                pause();
            }
        }
        else {
            // Screen switched off
            if (mCurrentState!=STATE_STOPPED) {
                mSaveState = mCurrentState;
            }
            Log.d(EVIACAM.TAG+"->"+TAG, "mSaveState: "+getStateName());
            if (mSaveState!= Engine.STATE_STANDBY) stop();
        }
    }

    /**
     * Handle camera errors
     * @param error the error
     */
    private void manageCameraError(@NonNull Throwable error) {
        /* Cast into CameraException */
        CameraException cameraException;
        if (error.getClass().isAssignableFrom(CameraException.class)) {
            cameraException = (CameraException) error;
        }
        else {
            cameraException = new CameraException(CameraException.CAMERA_ERROR,
                    error.getLocalizedMessage(), error);
        }

        boolean allowRetry = false;

        if (mCurrentState == STATE_DISABLED) {
            /* Exception during initialization. Non recoverable. */
            cleanup();

            ACRA.getErrorReporter().handleSilentException(cameraException);

            /* Notify whoever requested the initialization */
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mOnInitListener!= null) {
                        mOnInitListener.onInit(OnInitListener.INIT_ERROR);
                    }
                }
            });
            Log.d(EVIACAM.TAG + "->" + TAG, "manageCameraError -> notify handler: INIT_ERROR");
        }
        else if (mCurrentState == STATE_STOPPED && mWaitState &&
                cameraException.getProblem() == CameraException.CAMERA_IN_USE) {
            /* Exception during camera startup because is in use, allow to retry */
            Log.d(EVIACAM.TAG + "->" + TAG, "manageCameraError -> Exception during camera startup because is in use, allow to retry");
            allowRetry = true;
        }
        else {
            /* Other camera exceptions */

            Log.d(EVIACAM.TAG + "->" + TAG, "manageCameraError -> Other exception, retry?");

            if (cameraException.getProblem() != CameraException.CAMERA_DISABLED) {
                ACRA.getErrorReporter().handleSilentException(cameraException);
            }

            allowRetry=true;
        }

        if (allowRetry) {

            stop();
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mOnInitListener!= null) {
                        mOnInitListener.stop();
                    }
                }

            });
            Log.d(EVIACAM.TAG + "->" + TAG, "manageCameraError -> trying to restart camera");
            startFullScreenActivity();
        }

    }

    /* desperate try to get back camera */
    public void startFullScreenActivity(){

        if (mService!=null) {
            Intent intent = new Intent();
            intent.setAction(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY);
            intent.setComponent(new ComponentName(mService.getApplicationContext().getPackageName(), FullScreenActivity.class.getName()));
            mService.startActivity(intent);
        }

    }


    private BroadcastReceiver onFullScreenActivityEnd= new BroadcastReceiver(){
        @Override
        public void onReceive(Context context, Intent intent) {

            if (intent.getAction().equals("restartcamera")) {
                Log.d(EVIACAM.TAG + "->" + TAG, "onFullScreenActivityEnd -> trying to restart camera");
                restartservice();
            }
        }
    };

    void restartservice(){
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mOnInitListener!= null) {
                    mOnInitListener.restart();
                }
            }
        });
    }


    // avoid creating a new PointF for each frame
    private PointF mMotion= new PointF(0, 0);

    /**
     * Process incoming camera frames (called from a secondary thread)
     *
     * @param rgba opencv matrix with the captured image
     */
    @Override
    public void processFrame(@NonNull Mat rgba) {

        //Log.d(EVIACAM.TAG+"->"+TAG, "processFrame");

        // For these states do nothing
        if (mCurrentState== STATE_DISABLED || mCurrentState== STATE_STOPPED ||
                isInWaitState()) return;

        /*
         * In STATE_RUNNING, STATE_PAUSED or STATE_STANDBY state.
         * Need to check if face detected
         */
        int pictRotation = mOrientationManager.getPictureRotation();

        // set preview rotation
        mCamera.setPreviewRotation(pictRotation);

        // call jni part to detect and track face
        mMotion.x= mMotion.y= 0.0f;
        boolean faceDetected=
                VisionPipeline.processFrame(
                        rgba.getNativeObjAddr(),
                        mOrientationManager.getPictureFlip().getValue(),
                        pictRotation,
                        mMotion);

        if (faceDetected) mLastFaceDetectionTimeStamp= System.currentTimeMillis();

        // compensate mirror effect
        mMotion.x = -mMotion.x;

        onFrame(mMotion, faceDetected, mCurrentState);

        // States to be managed below: RUNNING, PAUSED, STANDBY

        if (faceDetected) mFaceDetectionCountdown.start();

        if (mCurrentState == STATE_STANDBY) {
            if (faceDetected) {
                // "Awake" from standby state
                mHandler.post(new Runnable() {
                    @Override
                    public void run() { resume(); } }
                );
                /* Yield CPU to the main thread so that it has the opportunity
                 * to run and change the engine state before this thread continue
                 * running.
                 * Remarks: tried Thread.yield() without success
                 */
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) { /* do nothing */ }
            }
            else {
                // In standby reduce CPU cycles by sleeping but only if screen went off
                if (!mPowerManagement.getScreenOn()) mPowerManagement.sleep();
            }
        }
        else if (mCurrentState == STATE_RUNNING) {
            if (mFaceDetectionCountdown.hasFinished() && !mFaceDetectionCountdown.isDisabled()) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        standby();
                    }
                });
            }
        }

        // Nothing more to do (state == Engine.STATE_PAUSED)
        updateFaceDetectorStatus(mFaceDetectionCountdown);
    }


}
