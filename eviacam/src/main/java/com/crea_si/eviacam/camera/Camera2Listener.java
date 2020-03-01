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

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceView;

import androidx.annotation.NonNull;

import com.crea_si.eviacam.BuildConfig;
import com.crea_si.eviacam.R;
import com.crea_si.eviacam.common.EVIACAM;
import com.crea_si.eviacam.util.FlipDirection;

import org.opencv.android.CameraException;
import org.opencv.android.FpsMeter;
import org.opencv.android.MyCameraBridgeViewBase;
import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Simple camera2 based interface for capturing camera image in real time for CV
 */

class Camera2Listener {
    private static final String TAG= "Camera2Listener";

    private final Context mContext;

    // callback to process frames
    private final FrameProcessor mFrameProcessor;

    // handler to run things on the main thread
    private final Handler mHandler= new Handler();

    // surface on which the image from the camera will be drawn
    private SurfaceView mCamera2View;

    SurfaceView getCameraSurface() {
        return mCamera2View;
    }

    // stores whether is supposed that the surface is ready to draw on it
    // note that this information is not authoritative, it just reflects
    // when is supposed that the camera is processing frames
    private boolean mSurfaceReady= false;

    /*
       Physical mounting rotation of the camera (i.e. whether the frame needs a flip
       operation). For instance, this is needed for those devices with rotating
       camera such as the Lenovo YT3-X50L)
     */
    private FlipDirection mCameraFlip= FlipDirection.NONE;
    FlipDirection getCameraFlip() { return mCameraFlip; }

    // physical orientation of the camera (0, 90, 180, 270)
    private int mCameraOrientation;
    int getCameraOrientation() { return mCameraOrientation; }

    // store the rotation needed to draw the picture in upwards position
    private int mPreviewRotation= 0;

    // capture size
    private Size mCaptureSize;

    // selected capture FPS range
    private Range<Integer> mTargetFPSRange;

    // captured frames count for debugging purposes
    private int mCapturedFrames;

    // camera device, null when the camera is closed
    private CameraDevice mCameraDevice;

    // facilities to run things on a secondary thread
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;

    Thread.UncaughtExceptionHandler mThreadExceptionHandler = new Thread.UncaughtExceptionHandler() {
        public void uncaughtException(Thread th, Throwable ex) {
            Log.e(EVIACAM.TAG+"->"+TAG, "Error on camerathread:"+ex.toString());
        }

    };

    // capture session
    private CameraCaptureSession mCaptureSession;
    private int mSessId = 0;

    // reader to extract camera frames
    private ImageReader mImageReader;

    /* Cached images */
    private Mat mCacheImage;
    private Bitmap mCacheBitmap;

    private FpsMeter mFpsMeter = null;

    private String cameraId = "";

    private Semaphore mCameraOpenCloseLock = new Semaphore(1);
    private Semaphore mCameraStopLock = new Semaphore(1);

    private CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            Log.d(EVIACAM.TAG+"->"+TAG, "Cameradevice.onOpened");
            mCameraDevice = cameraDevice;

            mCameraOpenCloseLock.release();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            Log.d(EVIACAM.TAG+"->"+TAG, "Cameradevice.onDisconnected");
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mFrameProcessor.onCameraErrorStopped();
                }
            });
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice,final int error) {
            Log.d(EVIACAM.TAG+"->"+TAG, "Cameradevice.onError:"+String.valueOf(error));
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mFrameProcessor.onCameraError(CameraAccessException2CameraException(mContext,new CameraAccessException(error)));
                }
            });
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

    };

    /**
     * Constructor
     * @param c context
     * @param fp object that will receive the camera callbacks
     */
    Camera2Listener(@NonNull Context c, @NonNull FrameProcessor fp) throws CameraException {
        mContext= c;
        mFrameProcessor= fp;

        // Pick best camera and get capture parameters
        cameraId = setUpCameraParameters();

        // Start background thread
        startCameraThread();

        // View for drawing camera output
        mCamera2View= new SurfaceView(mContext);

        mCacheBitmap = Bitmap.createBitmap(mCaptureSize.getWidth(), mCaptureSize.getHeight(),
                Bitmap.Config.ARGB_8888);

        mCacheImage= new Mat(mCaptureSize.getWidth(), mCaptureSize.getHeight(), CvType.CV_8UC4);

        /* Uncomment to enable the FPS meter for debugging */
        if (BuildConfig.DEBUG) {
            //mFpsMeter = new FpsMeter();
            //mFpsMeter.setResolution(mCaptureSize.getWidth(), mCaptureSize.getHeight());
        }

        openCamera(cameraId);
    }

    /**
     * Pick the best camera available for face tracking and set its flip, rotation and best
     * capture size
     *
     * @return ID if the camera
     * @throws CameraException when error
     */
    private String setUpCameraParameters()
            throws CameraException {
        /* Get camera manager */
        CameraManager manager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            Log.e(EVIACAM.TAG+"->"+TAG, "Cannot obtain camera manager");
            throw new CameraException(CameraException.NO_CAMERAS_AVAILABLE,
                    mContext.getResources().getString(R.string.service_camera_no_available));
        }

        /* Get available cameras */
        String[] cameraIdList;
        try {
            cameraIdList= manager.getCameraIdList();
        } catch (CameraAccessException e) {
            Log.e(EVIACAM.TAG+"->"+TAG, "Cannot query camera id list");
            throw CameraAccessException2CameraException(mContext, e);
        }

        if (cameraIdList.length< 1) {
            Log.e(EVIACAM.TAG+"->"+TAG, "No cameras available");
            throw new CameraException(CameraException.NO_CAMERAS_AVAILABLE,
                    mContext.getResources().getString(R.string.service_camera_no_available));
        }

        /* Detect and classify available cameras according to its lens facing */
        int frontCameraIdx= -1, backCameraIdx= -1, externalCameraIdx= -1;
        for (int i= 0; i< cameraIdList.length; i++) {
            CameraCharacteristics cameraCharacteristics;
            try {
                cameraCharacteristics= manager.getCameraCharacteristics(cameraIdList[i]);
            } catch (CameraAccessException e) {
                Log.e(EVIACAM.TAG+"->"+TAG, "Cannot get camera characteristics: " + cameraIdList[i]);
                continue;
            }

            Integer lensFacing= cameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
            if (lensFacing== null) {
                /* If fails to retrieve lens facing assume external camera */
                Log.d(EVIACAM.TAG+"->"+TAG, "Cannot retrieve lens facing for camera: " + cameraIdList[i]);
                externalCameraIdx= i;
            }
            else {
                switch (lensFacing) {
                    case CameraMetadata.LENS_FACING_FRONT:
                        frontCameraIdx = i;
                        break;
                    case CameraMetadata.LENS_FACING_BACK:
                        backCameraIdx = i;
                        break;
                    case CameraMetadata.LENS_FACING_EXTERNAL:
                        externalCameraIdx = i;
                        break;
                }
            }
        }

        /*
         * Pick the best available camera according to its lens facing
         *
         * For some devices, notably the Lenovo YT3-X50L, have only one camera that can
         * be rotated to point to the user's face. In this case the camera is reported as
         * facing back (TODO: confirm that this is still true with the camera2 API).
         * Therefore, we try to detect all cameras of the device and pick
         * the facing front one, if any. Otherwise, we pick an external camera and finally
         * pick a facing back camera. In the latter case report that the image needs a
         * vertical flip before fixing the orientation.
         */
        Log.d(EVIACAM.TAG+"->"+TAG, "Try front camera");
        int bestCamera= frontCameraIdx;
        if (bestCamera== -1) {
            Log.d(EVIACAM.TAG+"->"+TAG, "Try external camera");
            bestCamera= externalCameraIdx;
        }
        if (bestCamera== -1) {
            Log.d(EVIACAM.TAG+"->"+TAG, "Try back camera");
            bestCamera= backCameraIdx;
            mCameraFlip= FlipDirection.VERTICAL;
        }
        if (bestCamera== -1) {
            Log.e(EVIACAM.TAG+"->"+TAG, "None of the cameras is suitable for the job. Aborting.");
            throw new CameraException(CameraException.CAMERA_ERROR,
                    mContext.getResources().getString(R.string.service_camera_error));
        }

        String cameraId= cameraIdList[bestCamera];

        CameraCharacteristics cameraCharacteristics;
        try {
            cameraCharacteristics= manager.getCameraCharacteristics(cameraId);
        } catch (CameraAccessException e) {
            Log.e(EVIACAM.TAG+"->"+TAG, "Cannot get camera characteristics: " + cameraId);
            throw CameraAccessException2CameraException(mContext, e);
        }

        Log.d(EVIACAM.TAG+"->"+TAG, "Supported hardware level: " +
                cameraCharacteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL));

        /*
         * The orientation of the camera is the angle that the camera image needs
         * to be rotated clockwise so it shows correctly on the display in its natural orientation.
         * It should be 0, 90, 180, or 270.
         */
        Integer cameraOrientation=
                cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        if (cameraOrientation== null) {
            Log.e(EVIACAM.TAG+"->"+TAG, "Cannot get camera orientation");
            throw new CameraException(CameraException.CAMERA_ERROR,
                    mContext.getResources().getString(R.string.service_camera_error));
        }
        else {
            mCameraOrientation = cameraOrientation;
            Log.d(EVIACAM.TAG+"->"+TAG, "Camera orientation: " + mCameraOrientation);
        }

        /*
         * Select the best camera preview size
         */
        StreamConfigurationMap map = cameraCharacteristics.
                get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map== null) {
            Log.e(EVIACAM.TAG+"->"+TAG, "Cannot get camera map");
            throw new CameraException(CameraException.CAMERA_ERROR,
                    mContext.getResources().getString(R.string.service_camera_error));
        }

        Log.d(EVIACAM.TAG+"->"+TAG, "StreamConfigurationMap" + map.toString());
        Log.d(EVIACAM.TAG+"->"+TAG, "Preview sizes");
        Size[] outputSizes = map.getOutputSizes(ImageFormat.YUV_420_888);
        if (outputSizes== null) {
            Log.e(EVIACAM.TAG+"->"+TAG, "Cannot get output sizes");
            throw new CameraException(CameraException.CAMERA_ERROR,
                    mContext.getResources().getString(R.string.service_camera_error));
        }

        for (Size size : outputSizes) {
            Log.d(EVIACAM.TAG+"->"+TAG, "(" + size.getWidth() + ", " + size.getHeight() + ")");
        }

        /* Helper class */
        class SizeAccessor implements MyCameraBridgeViewBase.ListItemAccessor {
            @Override
            public int getWidth(Object obj) {
                return ((Size) obj).getWidth();
            }

            @Override
            public int getHeight(Object obj) {
                return ((Size) obj).getHeight();
            }
        }

        org.opencv.core.Size size= MyCameraBridgeViewBase.calculateBestCameraFrameSize(
                Arrays.asList(outputSizes), new SizeAccessor(),
                Camera.DESIRED_CAPTURE_WIDTH, Camera.DESIRED_CAPTURE_HEIGHT);
        if (size.width<= 0 || size.height<= 0) {
            throw new CameraException(CameraException.CAMERA_ERROR,
                    mContext.getResources().getString(R.string.service_camera_error));
        }
        mCaptureSize= new Size((int) size.width, (int) size.height);

        /*
         * Tries to pick a frame rate higher or equal than 15 fps.
         *
         * CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES always returns a list of
         * supported preview fps ranges with at least one element.
         * Every element is an FPS range
         * TODO (still true?): The list is sorted from small to large (first by maximum fps and then
         * minimum fps).
         *
         * With the old API:
         * Nexus 7: the list has only one element (4000,60000)
         * Samsung Galaxy Nexus: (15000,15000),(15000,30000),(24000,30000)
         */
        Range<Integer>[] cameraTargetFPSRanges=
                cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (cameraTargetFPSRanges== null) {
            Log.w(EVIACAM.TAG+"->"+TAG, "Cannot get camera target FPS ranges. Ignoring.");
        }
        else {
            int winner= cameraTargetFPSRanges.length-1;
            int maxLimit= cameraTargetFPSRanges[winner].getUpper();

            Log.d(EVIACAM.TAG+"->"+TAG, "Camera FPS ranges");
            for (Range<Integer> r : cameraTargetFPSRanges) {
                Log.d(EVIACAM.TAG+"->"+TAG, r.toString());
            }

            for (int i= winner-1; i>= 0; i--) {
                if (cameraTargetFPSRanges[i].getUpper()!= maxLimit ||
                        cameraTargetFPSRanges[i].getLower()< 15000) {
                    break;
                }
                winner= i;
            }
            mTargetFPSRange= cameraTargetFPSRanges[winner];
        }

        return cameraId;
    }

    /**
     * Open the camera device
     *
     * @param cameraId ID of the camera
     * @throws CameraException when error
     */
    private void openCamera(String cameraId) throws CameraException {

        Log.d(EVIACAM.TAG+"->"+TAG, "OpenCamera called");

        /* Open camera */
        CameraManager manager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
        try {
            Log.d(TAG, "tryAcquire");
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }

            manager.openCamera(cameraId, mStateCallback, null);

        }catch (SecurityException e) {
            Log.e(EVIACAM.TAG + "->" + TAG, "Error: Cannot open camera permission denied!");
        }catch (Exception e){
            Log.e(EVIACAM.TAG+"->"+TAG,"Error: Cannot open camera!");
        }

    }

    /**
     * Close camera device
     *
     * Block until the camera is fully closed.
     */
    private void closeCamera() {

        Log.d(EVIACAM.TAG+"->"+TAG, "closeCamera");
        try {
            mCameraOpenCloseLock.acquire();
            closeCaptureSession();
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }

        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.");
        } finally {
            mCameraOpenCloseLock.release();
        }

    }




    /**
     * Engine -> Camera: Start camera capture
     *
     * Once started, the client is notified using the onCameraStarted callback. When
     * error, the onCameraError is called.
     */
    void startCamera() {

        Log.d(EVIACAM.TAG+"->"+TAG, "startCamera");

        if (mCameraDevice == null) {
            Log.e(EVIACAM.TAG+"->"+TAG, "Trying to start unopened camera!");
        }

        openCaptureSession();

    }

    /**
     * Engine -> Camera: Stop the camera capture.
     *
     */
    void stopCamera() {

        Log.d(EVIACAM.TAG+"->"+TAG, "stopCamera");


        stopCapture();

    }

    /**
     * Create capture preview session
     *
     * TODO: set fixed shutter speed
     */
    private void openCaptureSession () {

        Log.d(EVIACAM.TAG + "->" + TAG, "openCaptureSession!");

        closeCaptureSession();

        /* Create image reader. Need to be a member to avoid being garbage collected */
        mImageReader = ImageReader.newInstance(mCaptureSize.getWidth(), mCaptureSize.getHeight(), ImageFormat.YUV_420_888, 2);
        mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundHandler);


        class Csc extends CameraCaptureSession.StateCallback {
            //uniq session id
            private int sessid = 0;

            @Override
            public void onConfigured(@NonNull CameraCaptureSession session) {
                Log.d(EVIACAM.TAG+"->"+TAG, "CameraCaptureSession.StateCallback: onConfigured");
                mCaptureSession = session;
                int t = new Random().nextInt(100)+1;
                while (sessid==t) {
                    t = new Random().nextInt(100)+1;
                }
                sessid=t;
                mSessId=t;

                Log.d(EVIACAM.TAG+"->"+TAG, "generated id:"+String.valueOf(sessid));

                startCapture();
            }

            @Override
            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                Log.e(EVIACAM.TAG+"->"+TAG, "CameraCaptureSession.StateCallback: onConfigureFailed");

                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        Log.e(EVIACAM.TAG+"->"+TAG, "Notify: onCameraError");
                        mFrameProcessor.onCameraError(new CameraException(CameraException.CAMERA_ERROR));
                    }
                });

            }

            @Override
            public void onActive(@NonNull CameraCaptureSession session) {
                Log.d(EVIACAM.TAG+"->"+TAG, "CameraCaptureSession.StateCallback: onActive");

                if (mSurfaceReady==false) {

                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            Log.d(EVIACAM.TAG+"->"+TAG, "Notify: onCameraStarted");
                            mFrameProcessor.onCameraStarted();
                        }
                    });

                    mSurfaceReady= true;
                }

            }

            @Override
            public void onClosed(@NonNull CameraCaptureSession session) {
                Log.d(EVIACAM.TAG+"->"+TAG, "CameraCaptureSession.StateCallback: onClosed id:"+String.valueOf(sessid));
                //this callback is not useful, it gets called sometimes random (perhaps previous sessions close?), and on error doesn't get called


                if (sessid==mSessId){

                    mCameraStopLock.release();

                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {

                            Log.e(EVIACAM.TAG+"->"+TAG, "Closed current session: Notify: oncamerastopped");
                            mFrameProcessor.onCameraStopped();

                        }
                    });
                }else {

                    Log.d(EVIACAM.TAG+"->"+TAG, "Closed preavios session, nothing to do");

                }

            }

            @Override
            public void onReady (@NonNull CameraCaptureSession session) {
                Log.d(EVIACAM.TAG+"->"+TAG, "CameraCaptureSession.StateCallback: onReady");
                //this gets called on start, stop when images ready, this is not useful for as
            }


        };

        /* Now create the capture session */
        try {


            Csc cc = new Csc();
            mCameraDevice.createCaptureSession(Collections.singletonList(mImageReader.getSurface()),
                    cc, mBackgroundHandler);

        } catch (CameraAccessException | IllegalStateException | NullPointerException e) {
            Log.e(EVIACAM.TAG + "->" + TAG, "createCaptureSession failed: " + e.toString());

        }

    }

    private void closeCaptureSession(){
        if (mCaptureSession!=null){
            mCaptureSession.close();
            mCaptureSession=null;
        }
    }

    private void startCapture(){

        if (mCameraDevice==null || mCaptureSession==null || mImageReader==null){
            Log.e(EVIACAM.TAG+"->"+TAG, "startCapture failed: no camera or no session!");
            return;
        }

        try {
            CaptureRequest.Builder previewRequestBuilder;

            previewRequestBuilder =
                    mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(mImageReader.getSurface());

            mCaptureSession.setRepeatingRequest(previewRequestBuilder.build(),
                    mCaptureCallback, mBackgroundHandler);

        }catch(Exception e){
            Log.e(EVIACAM.TAG+"->"+TAG, "startCapture failed: setRepeatingRequest exception:"+e.toString());
        }
    }

    private void stopCapture(){

        if (mCaptureSession==null) {
            Log.e(EVIACAM.TAG+"->"+TAG, "stopCapture failed: Session has been closed!");
            return;
        }

        mSurfaceReady = false;

        Log.d(EVIACAM.TAG+"->"+TAG, "stopCapture....");
        try {
            mCameraStopLock.tryAcquire(2500, TimeUnit.MILLISECONDS);
            Log.d(EVIACAM.TAG+"->"+TAG, "stopCapture in progress!");

            mCaptureSession.close();

        }catch (Exception e){
            Log.e(EVIACAM.TAG+"->"+TAG, "stopCapture failed: "+e.toString());
        }finally {
            mCameraStopLock.release();
        }
    }

    /* Listener that gets called when a new image is available */
    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener=
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    //Log.d(EVIACAM.TAG+"->"+TAG,"onImageAvailable");
                    Image image;

                    try {
                        image = reader.acquireLatestImage();
                        if(image == null) {
                            Log.d(EVIACAM.TAG+"->"+TAG,"onImageAvailable: null image");
                            return;
                        }

                        /* Informative log for debugging purposes */
                        /*
                        mCapturedFrames++;
                        if (mCapturedFrames < 100) {
                            if ((mCapturedFrames % 10) == 0) {
                                Log.d(EVIACAM.TAG+"->"+TAG, "onCameraFrame. Frame count:" + mCapturedFrames);
                            }
                        }

                         */

                        Mat yuv= imageToMat(image);
                        Imgproc.cvtColor(yuv, mCacheImage, Imgproc.COLOR_YUV2BGRA_YV12);

                        mFrameProcessor.processFrame(mCacheImage);

                        Utils.matToBitmap(mCacheImage, mCacheBitmap);

                        synchronized (this) {
                            if (mSurfaceReady) {
                                Canvas canvas = mCamera2View.getHolder().lockCanvas();
                                if (canvas != null) {
                                    drawBitmap(canvas);
                                    mCamera2View.getHolder().unlockCanvasAndPost(canvas);
                                }
                            }
                        }

                        if (BuildConfig.DEBUG) {
                            //Log.d(EVIACAM.TAG+"->"+TAG, "Image fmt:" + image.getFormat());
                            //Log.d(EVIACAM.TAG+"->"+TAG, "Size: " + image.getWidth() + "x" + image.getHeight());
                            //Log.d(EVIACAM.TAG+"->"+TAG, "Planes: " + image.getPlanes().length);
                            /*
                            for (Image.Plane plane : image.getPlanes()) {
                                Log.d(EVIACAM.TAG+"->"+TAG, plane.toString());
                            }

                             */
                            //Log.d(EVIACAM.TAG+"->"+TAG, "Crop rectangle: " + image.getCropRect().toString());
                            //Log.d(EVIACAM.TAG+"->"+TAG, "Mat type: " + yuv);
                            //Log.d(EVIACAM.TAG+"->"+TAG, "Bitmap type: " + mCacheBitmap.getWidth() + "*" +
                            //        mCacheBitmap.getHeight());
                        }
                    } catch (IllegalStateException e) {
                        Log.w(EVIACAM.TAG+"->"+TAG, "Too many images queued, dropping image");
                        return;
                    }
                    image.close();
                }
            };


    // Cached bitmap to avoid the allocation cost for each frame
    private Matrix mMatrixCached = new Matrix();

    /**
     * Draw image stored in mCachedBitmap to the canvas
     * @param canvas a canvas reference
     */
    private void drawBitmap(Canvas canvas) {
        /* Canvas size */
        final int canvasWidth= canvas.getWidth();
        final int canvasHeight= canvas.getHeight();
        if (0>= canvasWidth || 0>= canvasHeight) return;

        /* Bitmap (captured image) size */
        final int bitmapWidth= mCacheBitmap.getWidth();
        final int bitmapHeight= mCacheBitmap.getHeight();

        /*
         * Set rotation matrix
         */
        mMatrixCached.reset();

        if (mCameraFlip== FlipDirection.HORIZONTAL) {
            mMatrixCached.postScale(-1.0f, 1.0f);
            mMatrixCached.postTranslate(canvasWidth, 0.0f);
        }
        else if (mCameraFlip== FlipDirection.VERTICAL) {
            mMatrixCached.postScale(1.0f, -1.0f);
            mMatrixCached.postTranslate(0.0f, canvasHeight);
        }
        mMatrixCached.postRotate((float) mPreviewRotation, canvasWidth / 2, canvasHeight / 2);
        canvas.setMatrix(mMatrixCached);

        canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);

        if (canvasWidth!= bitmapWidth || canvasHeight != bitmapHeight) {
            // Need to scale captured image to draw
            float scale = Math.min((float) canvasHeight / (float) bitmapHeight,
                    (float) canvasWidth / (float) bitmapWidth);

            canvas.drawBitmap(mCacheBitmap,
                    new Rect(0, 0, bitmapWidth, bitmapHeight),
                    new Rect((int)((canvasWidth - scale * bitmapWidth) / 2),
                            (int)((canvasHeight - scale * bitmapHeight) / 2),
                            (int)((canvasWidth - scale * bitmapWidth) / 2 + scale * bitmapWidth),
                            (int)((canvasHeight - scale * bitmapHeight) / 2 + scale * bitmapHeight)),
                    null);
        } else {
            canvas.drawBitmap(mCacheBitmap,
                    new Rect(0, 0, bitmapWidth, bitmapHeight),
                    new Rect((canvasWidth - bitmapWidth) / 2,
                            (canvasHeight - bitmapHeight) / 2,
                            (canvasWidth - bitmapWidth) / 2 + bitmapWidth,
                            (canvasHeight - bitmapHeight) / 2 + bitmapHeight), null);
        }

        if (mFpsMeter != null) {
            mFpsMeter.measure();
            mFpsMeter.draw(canvas, 20, 30);
        }
    }




    /**
     * Sets the rotation to perform to the camera image before is displayed
     * in the preview surface
     *
     * @param rotation rotation to perform (clockwise) in degrees
     *                 legal values: 0, 90, 180, or 270
     */
    void setPreviewRotation (int rotation) {
        mPreviewRotation= rotation;
    }

    /**
     * Free resources
     */
    public void cleanup () {
        stopCamera();

        closeCamera();

        stopCameraThread();

        if (mCacheBitmap != null) {
            mCacheBitmap.recycle();
            mCacheBitmap= null;
        }

        if (mCacheImage != null) {
            mCacheImage.release();
            mCacheImage= null;
        }
    }

    /* Callback block for capture session capture management */
    private final CameraCaptureSession.CaptureCallback mCaptureCallback=
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureStarted(@NonNull CameraCaptureSession session,
                                             @NonNull CaptureRequest request,
                                             long timestamp, long frameNumber) {

                    //Log.d(EVIACAM.TAG+"->"+TAG, "CameraCaptureSession.CaptureCallback: onCaptureStarted");
                }

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {

                    //if (BuildConfig.DEBUG)
                    //Log.d(EVIACAM.TAG+"->"+TAG, "CameraCaptureSession.CaptureCallback: onCaptureCompleted");
                }

                @Override
                public void onCaptureFailed(@NonNull CameraCaptureSession session,
                                            @NonNull CaptureRequest request,
                                            @NonNull CaptureFailure failure) {
                    //if (BuildConfig.DEBUG)
                    Log.d(EVIACAM.TAG+"->"+TAG, "CameraCaptureSession.CaptureCallback: onCaptureFailed:"+failure.toString());
                    mFrameProcessor.onCameraErrorStopped();

                }

                @Override
                public void onCaptureBufferLost(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull Surface target, long frameNumber) {
                    Log.d(EVIACAM.TAG+"->"+TAG, "CameraCaptureSession.CaptureCallback: onCaptureBufferLost");
                }

                @Override
                public void onCaptureSequenceAborted(@NonNull CameraCaptureSession session, int sequenceId) {
                    Log.d(EVIACAM.TAG+"->"+TAG, "CameraCaptureSession.CaptureCallback: onCaptureSequenceAborted");
                }
            };

    // TODO: provide a better implementation
    static private Mat imageToMat(Image image) {
        ByteBuffer buffer;
        int rowStride;
        int pixelStride;
        int width = image.getWidth();
        int height = image.getHeight();
        int offset = 0;

        Image.Plane[] planes = image.getPlanes();
        byte[] data = new byte[image.getWidth() * image.getHeight() * 3 /2];// ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888) / 8];
        byte[] rowData = new byte[planes[0].getRowStride()];

        for (int i = 0; i < planes.length; i++) {
            buffer = planes[i].getBuffer();
            rowStride = planes[i].getRowStride();
            pixelStride = planes[i].getPixelStride();
            int w = (i == 0) ? width : width / 2;
            int h = (i == 0) ? height : height / 2;
            for (int row = 0; row < h; row++) {
                int bytesPerPixel = ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888) / 8;
                if (pixelStride == bytesPerPixel) {
                    int length = w * bytesPerPixel;
                    buffer.get(data, offset, length);

                    // Advance buffer the remainder of the row stride, unless on the last row.
                    // Otherwise, this will throw an IllegalArgumentException because the buffer
                    // doesn't include the last padding.
                    if (h - row != 1) {
                        buffer.position(buffer.position() + rowStride - length);
                    }
                    offset += length;
                } else {

                    // On the last row only read the width of the image minus the pixel stride
                    // plus one. Otherwise, this will throw a BufferUnderflowException because the
                    // buffer doesn't include the last padding.
                    if (h - row == 1) {
                        buffer.get(rowData, 0, width - pixelStride + 1);
                    } else {
                        buffer.get(rowData, 0, rowStride);
                    }

                    for (int col = 0; col < w; col++) {
                        data[offset++] = rowData[col * pixelStride];
                    }
                }
            }
        }

        // Finally, create the Mat.
        Mat mat = new Mat(height + height / 2, width, CvType.CV_8UC1);
        mat.put(0, 0, data);

        return mat;
    }

    /**
     * Translate a CameraAccessException into a CameraException and throw it
     * @param e exception
     */
    static
    private CameraException CameraAccessException2CameraException (Context c,
                                                                   CameraAccessException e) {
        switch (e.getReason()) {
            case CameraAccessException.CAMERA_DISABLED:
                Log.e(EVIACAM.TAG+"->"+TAG, "The device's cameras have been disabled for this user");
                return new CameraException(CameraException.CAMERA_DISABLED,
                        c.getResources().getString(R.string.service_camera_disabled_error));
            case CameraAccessException.CAMERA_DISCONNECTED:
                Log.e(EVIACAM.TAG+"->"+TAG, "The camera device is no longer available");
                return new CameraException(CameraException.CAMERA_ERROR,
                        c.getString(R.string.camera_no_longer_available));
            case CameraAccessException.CAMERA_ERROR:
                Log.e(EVIACAM.TAG+"->"+TAG, "The camera device is currently in the error state");
                return new CameraException(CameraException.CAMERA_ERROR,
                        c.getResources().getString(R.string.service_camera_error), e);
            case CameraAccessException.CAMERA_IN_USE:
                return new CameraException(CameraException.CAMERA_IN_USE,
                        c.getResources().getString(R.string.service_camera_no_access));
            case CameraAccessException.MAX_CAMERAS_IN_USE:
                return new CameraException(CameraException.CAMERA_IN_USE,
                        c.getString(R.string.max_cameras_in_use));
        }
        return new CameraException(CameraException.CAMERA_ERROR,
                c.getResources().getString(R.string.service_camera_error), e);
    }

    /**
     * Start background thread used for async notifications, including capturing frames
     */
    private void startCameraThread() {
        Log.d(EVIACAM.TAG+"->"+TAG, "start CameraThread");
        stopCameraThread();
        mBackgroundThread = new HandlerThread("CameraThread");
        mBackgroundThread.setUncaughtExceptionHandler(mThreadExceptionHandler);
        mBackgroundThread.start();

        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    /**
     * Stop background thread
     */
    private void stopCameraThread() {
        if(mBackgroundThread == null) return;

        Log.d(EVIACAM.TAG+"->"+TAG, "stop CameraThread");
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            Log.e(EVIACAM.TAG+"->"+TAG, "stop CameraThread");
        }
    }
}