package com.ubiqconn.mycamera;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.util.Size;
import android.view.WindowManager;

import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.framework.image.MediaImageBuilder;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector.ObjectDetectorOptions;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectionResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CAMERA_PERMISSION = 200;

    private TextureView mTextureView1;
    private TextureView mTextureView2;

    private CameraManager mCameraManager;
    private Map<String, CameraDevice> mCameraDevices = new HashMap<>();
    private Map<String, CameraCaptureSession> mCaptureSessions = new HashMap<>();
    private Map<String, Handler> mBackgroundHandlers = new HashMap<>();
    private Map<String, ImageReader> mImageReaders = new HashMap<>();
    private Map<String, ObjectDetector> mObjectDetectors = new HashMap<>();
    private java.util.concurrent.ConcurrentHashMap<String, Boolean> mIsProcessingFrames = new java.util.concurrent.ConcurrentHashMap<>();

    private void initObjectDetector(String cameraId) {
        try {
            BaseOptions.Builder baseOptionsBuilder = BaseOptions.builder()
                    .setModelAssetPath("efficientdet_lite0.tflite");

            ObjectDetectorOptions options = ObjectDetectorOptions.builder()
                    .setBaseOptions(baseOptionsBuilder.build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setScoreThreshold(0.5f)
                    .build();

            ObjectDetector detector = ObjectDetector.createFromOptions(this, options);
            mObjectDetectors.put(cameraId, detector);
        } catch (Exception e) {
            Log.e("MediaPipe", "Failed to load model", e);
        }
    }
    // private java.util.concurrent.ConcurrentHashMap<String, Long>
    // mLastAnalysisTimes = new java.util.concurrent.ConcurrentHashMap<>();

    private OverlayView mOverlayView1;
    private OverlayView mOverlayView2;

    private String[] mCameraIds;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mTextureView1 = findViewById(R.id.texture_view_1);
        mTextureView2 = findViewById(R.id.texture_view_2);
        mOverlayView1 = findViewById(R.id.overlay_view_1);
        mOverlayView2 = findViewById(R.id.overlay_view_2);

        mCameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[] { Manifest.permission.CAMERA },
                    REQUEST_CAMERA_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setupCameras();
            } else {
                Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private void setupCameras() {
        try {
            mCameraIds = mCameraManager.getCameraIdList();
            Log.d("CAMERA", "getCameraIdList " + mCameraIds.length);
            for (String cameraId : mCameraManager.getCameraIdList()) {
                CameraCharacteristics cc = mCameraManager.getCameraCharacteristics(cameraId);

                Integer lensFacing = cc.get(CameraCharacteristics.LENS_FACING);
                if (lensFacing != null) {
                    switch (lensFacing) {
                        case CameraCharacteristics.LENS_FACING_FRONT:
                            Log.d("CAMERA", "CameraId " + cameraId + " = FRONT camera");
                            break;

                        case CameraCharacteristics.LENS_FACING_BACK:
                            Log.d("CAMERA", "CameraId " + cameraId + " = BACK camera");
                            break;

                        case CameraCharacteristics.LENS_FACING_EXTERNAL:
                            Log.d("CAMERA", "CameraId " + cameraId + " = EXTERNAL camera");
                            break;

                        default:
                            Log.d("CAMERA", "CameraId " + cameraId + " = UNKNOWN facing");
                    }
                }

                int[] caps = cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                Log.i("CAMERA", "=== CameraId: " + cameraId + " Capabilities ===");
                if (caps != null) {
                    for (int cap : caps) {
                        Log.i("CAMERA", "   - " + capabilityToString(cap));
                    }
                }
            }

            if (mCameraIds.length < 2) {
                Toast.makeText(this, "This device has less than two cameras.", Toast.LENGTH_LONG).show();
                // Hide the second texture view if there's only one camera
                mTextureView2.setVisibility(View.GONE);
                if (mCameraIds.length > 0) {
                    if (mTextureView1.isAvailable()) {
                        openCamera(mCameraIds[0], mTextureView1);
                    } else {
                        mTextureView1
                                .setSurfaceTextureListener(createSurfaceTextureListener(mCameraIds[0], mTextureView1));
                    }
                }
                return;
            }

            boolean concurrentSupport = false;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                try {
                    Set<Set<String>> concurrentCameraIdCombinations = mCameraManager.getConcurrentCameraIds();
                    Log.d("CAMERA", "ConcurrentCameraIds " + concurrentCameraIdCombinations.size());
                    for (Set<String> combination : concurrentCameraIdCombinations) {
                        Log.i("MainActivity", "CameraId: " + combination);
                        if (combination.contains(mCameraIds[0]) && combination.contains(mCameraIds[1])) {
                            concurrentSupport = true;
                            break;
                        }
                    }
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }
            }

            if (concurrentSupport) {
                Toast.makeText(this, "Device supports concurrent cameras.", Toast.LENGTH_SHORT).show();
                if (mTextureView1.isAvailable()) {
                    openCamera(mCameraIds[0], mTextureView1);
                } else {
                    mTextureView1.setSurfaceTextureListener(createSurfaceTextureListener(mCameraIds[0], mTextureView1));
                }

                if (mTextureView2.isAvailable()) {
                    openCamera(mCameraIds[1], mTextureView2);
                } else {
                    mTextureView2.setSurfaceTextureListener(createSurfaceTextureListener(mCameraIds[1], mTextureView2));
                }
            } else {
                Toast.makeText(this, "Device does not support concurrent cameras. Opening one camera.",
                        Toast.LENGTH_LONG).show();
                mTextureView2.setVisibility(View.GONE);
                if (mTextureView1.isAvailable()) {
                    openCamera(mCameraIds[0], mTextureView1);
                } else {
                    mTextureView1.setSurfaceTextureListener(createSurfaceTextureListener(mCameraIds[0], mTextureView1));
                }
            }

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private String capabilityToString(int cap) {
        switch (cap) {
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE:
                return "BACKWARD_COMPATIBLE";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR:
                return "MANUAL_SENSOR";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING:
                return "MANUAL_POST_PROCESSING";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW:
                return "RAW";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING:
                return "PRIVATE_REPROCESSING";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS:
                return "READ_SENSOR_SETTINGS";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE:
                return "BURST_CAPTURE";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING:
                return "YUV_REPROCESSING";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT:
                return "DEPTH_OUTPUT";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO:
                return "HIGH_SPEED_VIDEO";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING:
                return "MOTION_TRACKING";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA:
                return "LOGICAL_MULTI_CAMERA";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME:
                return "MONOCHROME";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA:
                return "SECURE_IMAGE_DATA";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR:
                return "ULTRA_HIGH_RESOLUTION_SENSOR";
            case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING:
                return "REMOSAIC_REPROCESSING";
            default:
                return "UNKNOWN(" + cap + ")";
        }
    }

    private TextureView.SurfaceTextureListener createSurfaceTextureListener(String cameraId, TextureView textureView) {
        return new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                openCamera(cameraId, textureView);
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
            }
        };
    }

    private void openCamera(String cameraId, TextureView textureView) {
        try {
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            startBackgroundThread(cameraId);
            mCameraManager.openCamera(cameraId, createCameraStateCallback(cameraId, textureView),
                    mBackgroundHandlers.get(cameraId));
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private CameraDevice.StateCallback createCameraStateCallback(String cameraId, TextureView textureView) {
        return new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice camera) {
                mCameraDevices.put(cameraId, camera);
                createCameraPreviewSession(cameraId, textureView);
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {
                camera.close();
                mCameraDevices.remove(cameraId);
            }

            @Override
            public void onError(@NonNull CameraDevice camera, int error) {
                camera.close();
                mCameraDevices.remove(cameraId);
            }
        };
    }

    /*
     * private ObjectDetector createObjectDetector() {
     * ObjectDetectorOptions options = new ObjectDetectorOptions.Builder()
     * .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
     * .enableClassification()
     * .build();
     * return ObjectDetection.getClient(options);
     * }
     */

    private void createCameraPreviewSession(String cameraId, TextureView textureView) {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;

            CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            assert map != null;

            Size optimalSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), textureView.getWidth(),
                    textureView.getHeight());
            texture.setDefaultBufferSize(optimalSize.getWidth(), optimalSize.getHeight());

            runOnUiThread(() -> configureTransform(textureView.getWidth(), textureView.getHeight(), optimalSize,
                    textureView));

            Surface surface = new Surface(texture);
            List<Surface> targets = new ArrayList<>();
            targets.add(surface);

            CameraDevice cameraDevice = mCameraDevices.get(cameraId);
            if (cameraDevice == null)
                return;

            final CaptureRequest.Builder previewRequestBuilder = cameraDevice
                    .createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);

            // ImageReader removed in favor of TextureView.getBitmap()
            // Conditional ML Kit Setup: Only for the FIRST camera
            // if (mCameraIds != null && mCameraIds.length > 0 &&
            // cameraId.equals(mCameraIds[0])) { ... }

            cameraDevice.createCaptureSession(targets,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (mCameraDevices.get(cameraId) == null) {
                                return;
                            }
                            mCaptureSessions.put(cameraId, session);
                            try {
                                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                session.setRepeatingRequest(previewRequestBuilder.build(), null,
                                        mBackgroundHandlers.get(cameraId));

                                // Use TextureView.getBitmap() loop for ML
                                // Now enabling for ALL cameras
                                startDetectionLoop(cameraId);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Toast.makeText(MainActivity.this,
                                    "Failed to configure camera session for camera " + cameraId,
                                    Toast.LENGTH_SHORT).show();
                        }
                    }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void startDetectionLoop(String cameraId) {
        final Handler handler = new Handler();
        final Runnable detectionRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    final TextureView targetTexture;
                    if (mCameraIds.length > 0 && cameraId.equals(mCameraIds[0]))
                        targetTexture = mTextureView1;
                    else if (mCameraIds.length > 1 && cameraId.equals(mCameraIds[1]))
                        targetTexture = mTextureView2;
                    else
                        targetTexture = null;

                    if (targetTexture != null && targetTexture.isAvailable()) {
                        // This must be called on main thread? No, documentation says:
                        // "This method usually invokes the underlying SurfaceTexture's updateTexImage()
                        // method..."
                        // Actually documentation for getBitmap(): "This method can be invoked from any
                        // thread." !
                        // But TextureView methods are generally main thread bound.
                        // However, getBitmap() creates a copy.
                        // Let's run it on UI thread to be safe, or check if it throws.
                        // It is simpler to just grab it here (assuming we are on main or background).
                        // Since we post runnable, let's post it to UI thread handler or use the
                        // background handler?
                        // getBitmap() is heavy. Background is better.
                        // But TextureView must be touched on UI?
                        // Docs say: "The methods of this class must be invoked from the thread that
                        // created the TextureView."
                        // So we MUST call getBitmap() on UI thread.

                        runOnUiThread(() -> {
                            android.graphics.Bitmap bitmap = targetTexture.getBitmap();
                            if (bitmap != null) {
                                // Process in background to avoid blocking UI
                                Handler bgHandler = mBackgroundHandlers.get(cameraId);
                                if (bgHandler != null) {
                                    bgHandler.post(() -> processImage(bitmap, cameraId));
                                }
                            }
                        });
                    }
                } catch (Exception e) {
                    Log.e("MediaPipe", "Loop error", e);
                } finally {
                    // Schedule next frame
                    if (mCaptureSessions.containsKey(cameraId)) // Stop if camera closed
                        handler.postDelayed(this, 100); // 10 FPS
                }
            }
        };
        handler.post(detectionRunnable);
    }

    private void processImage(android.graphics.Bitmap bitmap, String cameraId) {
        if (mIsProcessingFrames.getOrDefault(cameraId, false)) {
            // Drop frame if busy, but recycle bitmap!
            bitmap.recycle();
            return;
        }
        mIsProcessingFrames.put(cameraId, true);

        try {
            if (!mObjectDetectors.containsKey(cameraId)) {
                initObjectDetector(cameraId);
            }

            ObjectDetector detector = mObjectDetectors.get(cameraId);
            if (detector != null) {
                // Bitmap from TextureView is ARGB_8888 by default.
                MPImage mpImage = new com.google.mediapipe.framework.image.BitmapImageBuilder(bitmap).build();

                com.google.mediapipe.tasks.vision.core.ImageProcessingOptions imageProcessingOptions = com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
                        .builder()
                        .setRotationDegrees(0) // TextureView bitmap is already oriented
                        .build();

                // Synchronous detection
                ObjectDetectionResult result = detector.detect(mpImage, imageProcessingOptions);

                runOnUiThread(() -> {
                    OverlayView targetOverlay = null;
                    if (mCameraIds.length > 0 && cameraId.equals(mCameraIds[0]))
                        targetOverlay = mOverlayView1;
                    else if (mCameraIds.length > 1 && cameraId.equals(mCameraIds[1]))
                        targetOverlay = mOverlayView2;

                    if (targetOverlay != null) {
                        int displayWidth = bitmap.getWidth();
                        int displayHeight = bitmap.getHeight();

                        if (result.detections() != null && !result.detections().isEmpty()) {
                            Log.d("MediaPipe", "Detected: " + result.detections().size());
                        }

                        targetOverlay.setResults(result.detections(), displayHeight,
                                displayWidth);
                    }
                });
            }
        } catch (Exception e) {
            Log.e("MediaPipe", "Error processing image: " + e.toString(), e);
        } finally {
            // Important: Recycle the bitmap we created with getBitmap()
            if (!bitmap.isRecycled()) {
                bitmap.recycle();
            }
            mIsProcessingFrames.put(cameraId, false);
        }
    }

    private int getRotationCompensation(String cameraId, android.app.Activity activity) {
        try {
            CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(cameraId);
            int deviceRotation = activity.getWindowManager().getDefaultDisplay().getRotation();
            int rotationCompensation = 0;
            Integer sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (sensorOrientation == null)
                sensorOrientation = 0;

            Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
            boolean isFrontFacing = lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_FRONT;

            int surfaceRotation = 0;
            switch (deviceRotation) {
                case Surface.ROTATION_0:
                    surfaceRotation = 0;
                    break;
                case Surface.ROTATION_90:
                    surfaceRotation = 90;
                    break;
                case Surface.ROTATION_180:
                    surfaceRotation = 180;
                    break;
                case Surface.ROTATION_270:
                    surfaceRotation = 270;
                    break;
            }

            if (isFrontFacing) {
                rotationCompensation = (sensorOrientation + surfaceRotation) % 360;
            } else { // Back-facing
                rotationCompensation = (sensorOrientation - surfaceRotation + 360) % 360;
            }
            return rotationCompensation;
        } catch (CameraAccessException e) {
            e.printStackTrace();
            return 0;
        }
    }

    private void configureTransform(int viewWidth, int viewHeight, Size previewSize, TextureView textureView) {
        if (null == textureView || null == previewSize || viewWidth == 0 || viewHeight == 0) {
            return;
        }
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / previewSize.getHeight(),
                    (float) viewWidth / previewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        textureView.setTransform(matrix);
    }

    private Size chooseOptimalSize(Size[] choices, int textureViewWidth, int textureViewHeight) {
        List<Size> bigEnough = new ArrayList<>();
        List<Size> notBigEnough = new ArrayList<>();
        int w = choices[0].getWidth();
        int h = choices[0].getHeight();
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * h / w &&
                    option.getWidth() >= textureViewWidth && option.getHeight() >= textureViewHeight) {
                bigEnough.add(option);
            } else {
                notBigEnough.add(option);
            }
        }

        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new Comparator<Size>() {
                @Override
                public int compare(Size lhs, Size rhs) {
                    return Long.compare((long) lhs.getWidth() * lhs.getHeight(),
                            (long) rhs.getWidth() * rhs.getHeight());
                }
            });
        } else if (notBigEnough.size() > 0) {
            return Collections.max(notBigEnough, new Comparator<Size>() {

                @Override
                public int compare(Size lhs, Size rhs) {
                    return Long.compare((long) lhs.getWidth() * lhs.getHeight(),
                            (long) rhs.getWidth() * rhs.getHeight());
                }

            });
        } else {
            return choices[0];
        }
    }

    private void startBackgroundThread(String cameraId) {
        HandlerThread thread = new HandlerThread("CameraBackground_" + cameraId);
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        mBackgroundHandlers.put(cameraId, handler);
    }

    private void stopBackgroundThreads() {
        for (String id : mBackgroundHandlers.keySet()) {
            Handler handler = mBackgroundHandlers.get(id);
            if (handler != null) {
                handler.getLooper().quitSafely();
            }
        }
        mBackgroundHandlers.clear();
    }

    private void closeCameras() {
        for (String id : mCaptureSessions.keySet()) {
            CameraCaptureSession session = mCaptureSessions.get(id);
            if (session != null) {
                session.close();
            }
        }
        mCaptureSessions.clear();

        for (String id : mCameraDevices.keySet()) {
            CameraDevice camera = mCameraDevices.get(id);
            if (camera != null) {
                camera.close();
            }
        }
        mCameraDevices.clear();

        for (ObjectDetector detector : mObjectDetectors.values()) {
            detector.close();
        }
        mObjectDetectors.clear();

        for (ImageReader reader : mImageReaders.values()) {
            reader.close();
        }
        mImageReaders.clear();
    }

    @Override
    protected void onPause() {
        closeCameras();
        stopBackgroundThreads();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // When the screen is turned off and turned back on, the SurfaceTexture is
        // already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case,
        // we can open
        // a camera and start preview from here (otherwise, we wait until the surface is
        // ready in
        // the SurfaceTextureListener).
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            setupCameras();
        }
    }
}
