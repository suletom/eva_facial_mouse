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

package com.crea_si.eviacam.camera;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.acra.ACRA;
import org.opencv.android.CameraException;
import org.opencv.android.MyCameraBridgeViewBase;
import org.opencv.android.MyCameraBridgeViewBase.CvCameraViewFrame;
import org.opencv.android.MyJavaCameraView;
import org.opencv.android.MyCameraBridgeViewBase.CvCameraViewListener2;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Mat;

import android.content.Context;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.SurfaceView;

import com.crea_si.eviacam.common.EVIACAM;
import com.crea_si.eviacam.R;
import com.crea_si.eviacam.common.VisionPipeline;
import com.crea_si.eviacam.util.FlipDirection;

/**
 * Provides a simple camera interface for initializing, starting and stopping it.
 */
public class CameraListener implements CvCameraViewListener2 {
    private final Context mContext;
    
    // callback to process frames
    private final FrameProcessor mFrameProcessor;
    
    // OpenCV capture&view facility
    private final MyCameraBridgeViewBase mCameraView;
    public SurfaceView getCameraSurface(){
        return mCameraView;
    }

    /*
       Physical mounting rotation of the camera (i.e. whether the frame needs a flip
       operation). For instance, this is needed for those devices with rotating
       camera such as the Lenovo YT3-X50L)
     */
    private final FlipDirection mCameraFlip;
    public FlipDirection getCameraFlip() { return mCameraFlip; }

    // physical orientation of the camera (0, 90, 180, 270)
    private final int mCameraOrientation;
    public int getCameraOrientation() { return mCameraOrientation; }

    // captured frames count
    private int mCapuredFrames;

    /** 
     * Load a resource into a temporary file
     * 
     * @param c - context
     * @param rid - resource id
     * @param suffix - extension of the temporary file
     * @return a File object representing the temporary file
     * @throws IOException when failed
     */
    private static File resourceToTempFile (Context c, int rid, String suffix) 
            throws IOException {
        InputStream is;
        OutputStream os= null;
        File outFile = null;
        
        is= c.getResources().openRawResource(rid);
        try {
            outFile = File.createTempFile("tmp", suffix, c.getCacheDir());
            os= new FileOutputStream(outFile);
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }
        } catch (IOException e) {
            if (outFile != null) {
                //noinspection ResultOfMethodCallIgnored
                outFile.delete();
                outFile= null;
            }
            throw e;
        }
        finally {
            try {
                if (is != null) is.close();
            }
            catch (IOException e) {
                // Do nothing
            }
            try {
                if (os != null) os.close();
            }
            catch (IOException e) {
                // Do nothing
            }
        }

        return outFile;
    }

    /**
     * Constructor
     * @param c context
     * @param fp object that will receive the camera callbacks
     */
    public CameraListener(@NonNull Context c, @NonNull FrameProcessor fp) throws CameraException {
        mContext= c;
        mFrameProcessor= fp;

        /*
         * For some devices, notably the Lenovo YT3-X50L, have only one camera that can
         * be rotated to frame the user's face. In this case the camera is reported as
         * facing back. Therefore, we try to detect all cameras of the device and pick
         * the facing front one, if any. Otherwise, we pick the first facing back camera
         * and report that the image needs a vertical flip before fixing the orientation.
         *
         * The orientation of the camera is the angle that the camera image needs
         * to be rotated clockwise so it shows correctly on the display in its natural orientation.
         * It should be 0, 90, 180, or 270.
         *
         * For example, suppose a device has a naturally tall screen. The back-facing camera sensor
         * is mounted in landscape. You are looking at the screen. If the top side of the camera
         * sensor is aligned with the right edge of the screen in natural orientation, the value
         * should be 90. If the top side of a front-facing camera sensor is aligned with the right
         * of the screen, the value should be 270.
         */
        final int numCameras= Camera.getNumberOfCameras();
        if (numCameras< 1) {
            Log.e(EVIACAM.TAG, "No cameras available");
            throw new CameraException(CameraException.NO_CAMERAS_AVAILABLE,
                    c.getResources().getString(R.string.no_cameras_available));
        }

        // Pick the best available camera
        int bestCamera= 0;  // pick the first one if no facing front camera available
        Camera.CameraInfo cameraInfo= new CameraInfo();
        for (int i= 0; i< numCameras; i++) {
            Camera.getCameraInfo (i, cameraInfo);
            if (cameraInfo.facing== CameraInfo.CAMERA_FACING_FRONT) {
                bestCamera= i;
                break;
            }
        }

        // Get camera features
        Camera.getCameraInfo (bestCamera, cameraInfo);
        FlipDirection flip= FlipDirection.NONE;                // no flip needed by default
        int cameraId = MyCameraBridgeViewBase.CAMERA_ID_FRONT; // front camera by default

        if (cameraInfo.facing== CameraInfo.CAMERA_FACING_BACK) {
            // When the best available camera faces back
            flip= FlipDirection.VERTICAL;
            cameraId= MyCameraBridgeViewBase.CAMERA_ID_BACK;

            Log.i(EVIACAM.TAG, "Back camera detected. Orientation: " + cameraInfo.orientation);
        }
        else {
            Log.i(EVIACAM.TAG, "Front camera detected. Orientation: " + cameraInfo.orientation);
        }

        mCameraOrientation= cameraInfo.orientation;
        mCameraFlip= flip;

        /*
         * Create a capture view which carries the responsibilities of
         * capturing and displaying frames.
         */
        mCameraView= new MyJavaCameraView(c, cameraId);

        mCameraView.setCvCameraViewListener(this);

        // We first attempted to work at 320x240, but for some devices such as the
        // Galaxy Nexus crashes with a "Callback buffer was too small!" error.
        // However, at 352x288 works for all devices tried so far.
        mCameraView.setMaxFrameSize(352, 288);

        //mCameraView.enableFpsMeter();  // remove comment for testing

        mCameraView.setVisibility(SurfaceView.VISIBLE);
    }
    
    public void startCamera() {
        /*
          In previous versions we used the OpenCV async helper, but we found
          problems with devices running Android arm64 (e.g. Huawei P8) due
          to missing OpenCV libraries. To avoid such problems we included the
          OpenCV binaries in the App apk
         */
        if (!OpenCVLoader.initDebug()) {
            throw new RuntimeException("Cannot initialize OpenCV");
        }

        Log.i(EVIACAM.TAG, "OpenCV loaded successfully");

        // initialize JNI part
        System.loadLibrary("visionpipeline");

        /* Load haarcascade from resources */
        try {
            File f= resourceToTempFile (mContext, R.raw.haarcascade, "xml");
            VisionPipeline.init(f.getAbsolutePath());
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        catch (IOException e) {
            Log.e(EVIACAM.TAG, "Cannot write haarcascade temp file. Continuing anyway");
        }

        // start camera capture
        try {
            mCameraView.enableView();
        }
        catch(Exception error) {
            mFrameProcessor.onCameraError(error);
        }
    }
    
    public void stopCamera() {
        try {
            mCameraView.disableView();
        }
        catch (Exception error) {
            // Ignore errors when stopping camera
            Log.e(EVIACAM.TAG, error.getLocalizedMessage());
            ACRA.getErrorReporter().handleSilentException(error);
        }
    }

    /**
     * Sets the flip operation to perform to the frame before is applied a rotation
     *
     * @param flip FlipDirection.NONE, FlipDirection.VERTICAL or FlipDirection.HORIZONTAL
     */
    public void setPreviewFlip(FlipDirection flip) {
        switch (flip) {
            case NONE:
                mCameraView.setPreviewFlip(MyCameraBridgeViewBase.FlipDirection.NONE);
                break;
            case VERTICAL:
                mCameraView.setPreviewFlip(MyCameraBridgeViewBase.FlipDirection.VERTICAL);
                break;
            case HORIZONTAL:
                mCameraView.setPreviewFlip(MyCameraBridgeViewBase.FlipDirection.HORIZONTAL);
                break;
        }
    }

    /**
     * Sets the rotation to perform to the camera image before is displayed
     * in the preview surface
     *
     * @param rotation rotation to perform (clockwise) in degrees
     *                 legal values: 0, 90, 180, or 270
     */
    public void setPreviewRotation (int rotation) {
        mCameraView.setPreviewRotation(rotation);
    }

    @Override
    public void onCameraViewStarted(int width, int height) {
        Log.i(EVIACAM.TAG, "onCameraViewStarted");
        mFrameProcessor.onCameraStarted();
    }

    @Override
    public void onCameraViewStopped() {
        Log.i(EVIACAM.TAG, "onCameraViewStopped. Frame count:" + mCapuredFrames);
        
        // finish JNI part
        VisionPipeline.cleanup();

        mFrameProcessor.onCameraStopped();
    }

    @Override
    public void onCameraViewError(@NonNull Throwable error) {
        mFrameProcessor.onCameraError(error);
    }

    /**
     * Called each time new frame is captured
     */
    @Override
    public Mat onCameraFrame(CvCameraViewFrame inputFrame) {
        /* Informative log for debug purposes */
        mCapuredFrames++;
        if (mCapuredFrames< 100) {
            if ((mCapuredFrames % 10) == 0) {
                Log.i(EVIACAM.TAG, "onCameraFrame. Frame count:" + mCapuredFrames);
            }
        }

        Mat rgba = inputFrame.rgba();
        
        mFrameProcessor.processFrame(rgba);
        
        return rgba;
    }

    /**
     * Enable or disable camera viewer refresh to save CPU cycles
     * @param v true to enable update, false to disable
     */
    @SuppressWarnings("unused")
    public void setUpdateViewer(boolean v) {
        if (mCameraView!= null) mCameraView.setUpdateViewer(v);
    }
}
