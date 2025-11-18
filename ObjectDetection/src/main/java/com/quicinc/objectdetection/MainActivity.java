// ---------------------------------------------------------------------
// Copyright (c) 2025 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.quicinc.objectdetection;

import android.Manifest;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * MainActivity for ObjectDetection with Recording Capabilities
 * - Displays object detection using CameraFragment
 * - Records raw video (NO AUDIO)
 * - Records detection video with overlays (NO AUDIO)
 * - Logs IMU sensor data to CSV
 */
public class MainActivity extends AppCompatActivity implements SensorEventListener {
    private static final String TAG = "ObjectDetection";
    private static final String DATASET_ROOT_DIR = "SafeNAV_Datasets";

    // UI Components
    private Button startRecordBtn;
    private Button stopRecordBtn;

    // Recording components
    private MediaRecorder rawVideoRecorder;
    private MediaRecorder detectionVideoRecorder;
    private boolean isRecording = false;

    // IMU components
    private SensorManager sensorManager;
    private Sensor accelSensor;
    private Sensor gyroSensor;
    private BufferedWriter imuWriter;
    private File currentDatasetDir;
    private File imuFile;
    private File rawVideoFile;
    private File detectionVideoFile;

    // Time sync
    private Long sensorBootOffsetNanos = null;
    private long recordingStartRealtimeNanos = 0L;
    private final float[] latestAccel = new float[3];
    private final float[] latestGyro = new float[3];

    // Object detection
    private ObjectDetection objectDetection;
    private CameraFragment cameraFragment;

    // Permissions - REMOVED RECORD_AUDIO
    private static final String[] REQUIRED_PERMISSIONS;
    static {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            REQUIRED_PERMISSIONS = new String[]{
                    Manifest.permission.CAMERA
            };
        } else {
            REQUIRED_PERMISSIONS = new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            };
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity);

        // Hide the loading circle
        View progressBar = findViewById(R.id.indeterminateBar);
        if (progressBar != null) {
            progressBar.setVisibility(View.GONE);
        }

        // Initialize ObjectDetection model
        try {
            objectDetection = new ObjectDetection(
                    this,
                    getString(R.string.tfLiteModelAsset),
                    getString(R.string.tfLiteLabelsAsset),
                    com.quicinc.tflite.AIHubDefaults.delegatePriorityOrder
            );
            Log.d(TAG, "ObjectDetection model initialized");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize ObjectDetection", e);
            Toast.makeText(this, "Failed to load detection model", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Initialize sensors
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        }

        // Add recording buttons
        addRecordingButtons();

        // Check permissions and setup camera
        if (allPermissionsGranted()) {
            setupCameraFragment();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 100);
        }
    }

    private void setupCameraFragment() {
        // Create and add CameraFragment
        cameraFragment = CameraFragment.create(objectDetection);
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.main_content, cameraFragment)
                .commit();
    }

    private void addRecordingButtons() {
        FrameLayout root = findViewById(android.R.id.content);

        startRecordBtn = new Button(this);
        startRecordBtn.setText("Start Recording");
        startRecordBtn.setBackgroundColor(0xFF4CAF50);
        startRecordBtn.setTextColor(0xFFFFFFFF);
        FrameLayout.LayoutParams sLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sLp.bottomMargin = dpToPx(100);
        root.addView(startRecordBtn, sLp);

        stopRecordBtn = new Button(this);
        stopRecordBtn.setText("Stop Recording");
        stopRecordBtn.setBackgroundColor(0xFFF44336);
        stopRecordBtn.setTextColor(0xFFFFFFFF);
        stopRecordBtn.setEnabled(false);
        FrameLayout.LayoutParams tLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        tLp.bottomMargin = dpToPx(50);
        root.addView(stopRecordBtn, tLp);

        startRecordBtn.setOnClickListener(v -> startRecording());
        stopRecordBtn.setOnClickListener(v -> stopRecording());
    }

    // ==================== RECORDING METHODS ====================

    private void startRecording() {
        if (isRecording) {
            Toast.makeText(this, "Already recording", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!allPermissionsGranted()) {
            Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show();
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 100);
            return;
        }

        // Create dataset directory
        currentDatasetDir = createDatasetDirectory();
        if (currentDatasetDir == null) {
            Toast.makeText(this, "Failed to create dataset directory", Toast.LENGTH_SHORT).show();
            return;
        }

        rawVideoFile = new File(currentDatasetDir, "video_raw.mp4");
        detectionVideoFile = new File(currentDatasetDir, "video_detection.mp4");
        imuFile = new File(currentDatasetDir, "imu.csv");

        // Setup IMU logging
        try {
            imuWriter = new BufferedWriter(new FileWriter(imuFile));
            imuWriter.write("timestamp_ns,gx,gy,gz,ax,ay,az\n");
        } catch (IOException e) {
            Log.e(TAG, "Failed to open IMU file", e);
            Toast.makeText(this, "Failed to create IMU file", Toast.LENGTH_SHORT).show();
            return;
        }

        // Register IMU sensors
        sensorBootOffsetNanos = null;
        recordingStartRealtimeNanos = SystemClock.elapsedRealtimeNanos();
        if (sensorManager != null) {
            if (accelSensor != null) {
                sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME);
            }
            if (gyroSensor != null) {
                sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME);
            }
        }

        // Setup raw video recorder
        try {
            setupRawVideoRecorder();
            rawVideoRecorder.start();
            Log.i(TAG, "Raw video recording started");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start raw video recording", e);
            Toast.makeText(this, "Failed to start recording: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            stopIMULogging();
            return;
        }

        // Setup detection video recorder
        try {
            setupDetectionVideoRecorder();
            detectionVideoRecorder.start();

            // Tell FragmentRender to start recording
            if (cameraFragment != null && cameraFragment.getFragmentRender() != null) {
                cameraFragment.getFragmentRender().setRecordingSurface(
                        detectionVideoRecorder.getSurface()
                );
            }

            Log.i(TAG, "Detection video recording started");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start detection video recording", e);
            // Continue anyway with just raw video
        }

        isRecording = true;
        startRecordBtn.setEnabled(false);
        stopRecordBtn.setEnabled(true);
        Toast.makeText(this, "Recording started\n" + currentDatasetDir.getName(),
                Toast.LENGTH_SHORT).show();
    }

    private void setupRawVideoRecorder() throws IOException {
        rawVideoRecorder = new MediaRecorder();

        // VIDEO ONLY - NO AUDIO SOURCE
        rawVideoRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        rawVideoRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        rawVideoRecorder.setOutputFile(rawVideoFile.getAbsolutePath());

        // Video settings
        rawVideoRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        rawVideoRecorder.setVideoSize(1280, 720);
        rawVideoRecorder.setVideoFrameRate(30);
        rawVideoRecorder.setVideoEncodingBitRate(10_000_000);

        rawVideoRecorder.prepare();
    }

    private void setupDetectionVideoRecorder() throws IOException {
        detectionVideoRecorder = new MediaRecorder();

        // VIDEO ONLY - NO AUDIO SOURCE
        detectionVideoRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        detectionVideoRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        detectionVideoRecorder.setOutputFile(detectionVideoFile.getAbsolutePath());
        detectionVideoRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        detectionVideoRecorder.setVideoSize(1280, 720);
        detectionVideoRecorder.setVideoFrameRate(30);
        detectionVideoRecorder.setVideoEncodingBitRate(10_000_000);

        detectionVideoRecorder.prepare();
    }

    private void stopRecording() {
        if (!isRecording) return;

        try {
            // Stop raw video recorder
            if (rawVideoRecorder != null) {
                try {
                    rawVideoRecorder.stop();
                } catch (RuntimeException e) {
                    Log.e(TAG, "Error stopping raw recorder", e);
                }
                rawVideoRecorder.reset();
                rawVideoRecorder.release();
                rawVideoRecorder = null;
            }

            // Stop detection video recorder
            if (detectionVideoRecorder != null) {
                try {
                    // Stop recording on FragmentRender first
                    if (cameraFragment != null && cameraFragment.getFragmentRender() != null) {
                        cameraFragment.getFragmentRender().setRecordingSurface(null);
                    }

                    detectionVideoRecorder.stop();
                } catch (RuntimeException e) {
                    Log.e(TAG, "Error stopping detection recorder", e);
                }
                detectionVideoRecorder.reset();
                detectionVideoRecorder.release();
                detectionVideoRecorder = null;
            }

            // Stop IMU logging
            stopIMULogging();

            // Create metadata
            if (currentDatasetDir != null) {
                createMetadataFile(currentDatasetDir);
            }

            isRecording = false;
            startRecordBtn.setEnabled(true);
            stopRecordBtn.setEnabled(false);

            Toast.makeText(this, "Recording saved!\n" + currentDatasetDir.getAbsolutePath(),
                    Toast.LENGTH_LONG).show();
            Log.i(TAG, "Recording stopped and saved");

        } catch (Exception e) {
            Log.e(TAG, "Error stopping recording", e);
            Toast.makeText(this, "Error stopping recording", Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== IMU METHODS ====================

    @Override
    public void onSensorChanged(@NonNull SensorEvent event) {
        if (imuWriter == null || !isRecording) return;

        if (sensorBootOffsetNanos == null) {
            long nowElapsed = SystemClock.elapsedRealtimeNanos();
            sensorBootOffsetNanos = nowElapsed - event.timestamp;
        }

        long sensorElapsedRealtimeNanos = event.timestamp + sensorBootOffsetNanos;

        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, latestAccel, 0, 3);
        } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            System.arraycopy(event.values, 0, latestGyro, 0, 3);
        }

        try {
            String line = String.format(Locale.US, "%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f\n",
                    sensorElapsedRealtimeNanos,
                    (double) latestGyro[0], (double) latestGyro[1], (double) latestGyro[2],
                    (double) latestAccel[0], (double) latestAccel[1], (double) latestAccel[2]
            );
            imuWriter.write(line);
        } catch (IOException e) {
            Log.e(TAG, "Failed to write IMU data", e);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not needed
    }

    private void stopIMULogging() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if (imuWriter != null) {
            try {
                imuWriter.flush();
                imuWriter.close();
                Log.i(TAG, "IMU file saved: " + imuFile.getAbsolutePath());
            } catch (IOException e) {
                Log.e(TAG, "Failed to close IMU writer", e);
            }
            imuWriter = null;
        }
    }

    // ==================== UTILITY METHODS ====================

    private File createDatasetDirectory() {
        File rootDir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                DATASET_ROOT_DIR
        );

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File datasetDir = new File(rootDir, "dataset_" + timestamp);

        if (!datasetDir.exists() && !datasetDir.mkdirs()) {
            Log.e(TAG, "Failed to create dataset directory");
            return null;
        }

        Log.i(TAG, "Created dataset directory: " + datasetDir.getAbsolutePath());
        return datasetDir;
    }

    private void createMetadataFile(File datasetDir) {
        File metadataFile = new File(datasetDir, "metadata.yaml");
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(metadataFile))) {
            writer.write("# SafeNAV Dataset with Object Detection\n");
            writer.write("# Generated by Qualcomm AI Hub ObjectDetection App\n");
            writer.write("dataset_name: " + datasetDir.getName() + "\n");
            writer.write("creation_date: " +
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + "\n");
            writer.write("\n");
            writer.write("# Files\n");
            writer.write("video_raw: video_raw.mp4\n");
            writer.write("video_detection: video_detection.mp4\n");
            writer.write("imu_file: imu.csv\n");
            writer.write("\n");
            writer.write("# Video Information\n");
            writer.write("resolution: 1280x720\n");
            writer.write("fps: 30\n");
            writer.write("audio: false\n");
            writer.write("\n");
            writer.write("# IMU Information\n");
            writer.write("imu_format: timestamp_ns,gx,gy,gz,ax,ay,az\n");
            writer.write("gyro_units: rad/s\n");
            writer.write("accel_units: m/s^2\n");
            writer.write("timestamp_base: elapsedRealtimeNanos\n");
            writer.write("\n");
            writer.write("# Object Detection Model\n");
            writer.write("model: YOLOv8 (Qualcomm AI Hub Quantized)\n");
            writer.write("classes: COCO 80 classes\n");

            Log.i(TAG, "Metadata file created: " + metadataFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to create metadata file", e);
        }
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100) {
            if (allPermissionsGranted()) {
                setupCameraFragment();
            } else {
                Toast.makeText(this, "Permissions not granted", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isRecording) {
            stopRecording();
        }
        if (objectDetection != null) {
            objectDetection.close();
        }
    }
}