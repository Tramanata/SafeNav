package com.example.safenav;

import android.Manifest;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.app.ActivityCompat;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

/**
 * CameraActivity - Record MP4 (CameraX) + IMU (accel + gyro) CSV for ORB-SLAM3.
 *
 * IMU CSV format written here (EuRoC / ORB-SLAM3 style):
 *    timestamp_ns, gx, gy, gz, ax, ay, az
 *
 * timestamp_ns = sensor timestamp converted to SAMe elapsedRealtime clock (nanoseconds).
 * Gyro units: rad/s (Android gyroscope gives rad/s). Accel units: m/s^2.
 *
 * NOTE: You will still need to align video frame timestamps to the same clock when feeding to ORB-SLAM3.
 */
public class CameraActivity extends AppCompatActivity implements SensorEventListener {
    private static final String TAG = "CameraActivity";

    // UI & Camera
    private PreviewView previewView;
    private Button startBtn;
    private Button stopBtn;
    private VideoCapture<Recorder> videoCapture;
    private Recording activeRecording;

    // Sensors & IMU logging
    private SensorManager sensorManager;
    private Sensor accelSensor;
    private Sensor gyroSensor;
    private BufferedWriter imuWriter;
    private File imuFile;

    // Time sync helpers
    // sensorBootOffsetNanos = elapsedRealtimeNanos_now - sensorEvent.timestamp (ns)
    private Long sensorBootOffsetNanos = null;
    // When video actually starts, we capture recordingStartRealtimeNanos (elapsedRealtimeNanos)
    private long recordingStartRealtimeNanos = 0L;

    // latest samples
    private final float[] latestAccel = new float[3];
    private final float[] latestGyro = new float[3];

    // Permissions - dynamic based on Android version
    private static final String[] REQUIRED_PERMISSIONS;

    static {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ doesn't need WRITE_EXTERNAL_STORAGE
            REQUIRED_PERMISSIONS = new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
            };
        } else {
            // Android 12 and below
            REQUIRED_PERMISSIONS = new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            };
        }
    }

    private final ActivityResultLauncher<String[]> requestPermissionsLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), results -> {
                boolean ok = true;
                for (String p : REQUIRED_PERMISSIONS) {
                    Boolean granted = results.get(p);
                    if (granted == null || !granted) {
                        ok = false;
                        Log.w(TAG, "Permission NOT granted: " + p);
                        break;
                    } else {
                        Log.i(TAG, "Permission granted: " + p);
                    }
                }
                if (ok) {
                    // Permissions granted: enable buttons and start camera
                    enableButtonsAfterPermission();
                    startCameraSafe();
                } else {
                    Toast.makeText(this, "Camera & audio permissions required", Toast.LENGTH_LONG).show();
                    finish();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);
        previewView = findViewById(R.id.previewView);

        // Build dynamic Start / Stop buttons so your layout file does not need editing
        addRecordingButtonsToLayout();

        // Back button already in your layout
        if (findViewById(R.id.backBtn) != null) {
            findViewById(R.id.backBtn).setOnClickListener(v -> finish());
        }

        // Sensor manager
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            gyroSensor  = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        }

        // Initially disable start button until permissions are granted and camera ready
        startBtn.setEnabled(false);
        stopBtn.setEnabled(false);

        // Request runtime permissions (if not already)
        if (allPermissionsGranted()) {
            Log.i(TAG, "All permissions already granted");
            enableButtonsAfterPermission();
            startCameraSafe();
        } else {
            Log.w(TAG, "Requesting permissions...");
            // Show explanation before requesting
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Welcome to SafeNAV")
                    .setMessage("This app needs Camera and Microphone access to record navigation data. Your privacy is important - recordings stay on your device.")
                    .setPositiveButton("Continue", (dialog, which) -> {
                        requestPermissionsLauncher.launch(REQUIRED_PERMISSIONS);
                    })
                    .setCancelable(false)
                    .show();
        }
    }

    private void addRecordingButtonsToLayout() {
        FrameLayout root = (FrameLayout) findViewById(android.R.id.content);

        startBtn = new Button(this);
        startBtn.setText("Start Recording");
        FrameLayout.LayoutParams sLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        sLp.topMargin = dpToPx(600);
        root.addView(startBtn, sLp);

        stopBtn = new Button(this);
        stopBtn.setText("Stop Recording");
        FrameLayout.LayoutParams tLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tLp.topMargin = dpToPx(650);
        root.addView(stopBtn, tLp);

        startBtn.setOnClickListener(v -> startRecording());
        stopBtn.setOnClickListener(v -> stopRecording());
    }

    private void enableButtonsAfterPermission() {
        // enable start button once we are sure permissions are OK
        runOnUiThread(() -> {
            startBtn.setEnabled(true);
            stopBtn.setEnabled(false);
        });
    }

    private int dpToPx(int dp) {
        float scale = getResources().getDisplayMetrics().density;
        return Math.round(dp * scale);
    }

    private boolean allPermissionsGranted() {
        for (String p : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Permission not granted: " + p);
                return false;
            }
        }
        return true;
    }

    // ---------------- Camera start ----------------
    private void startCameraSafe() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                bindCameraUseCases(provider);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera provider error", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases(ProcessCameraProvider provider) {
        try {
            provider.unbindAll();
            Preview preview = new Preview.Builder()
                    .setTargetResolution(new android.util.Size(1280, 720))
                    .build();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());

            Recorder recorder = new Recorder.Builder().build();
            videoCapture = VideoCapture.withOutput(recorder);

            provider.bindToLifecycle((LifecycleOwner) this, CameraSelector.DEFAULT_BACK_CAMERA, preview, videoCapture);
            Log.i(TAG, "Camera bound successfully");
        } catch (Exception e) {
            Log.e(TAG, "bindCameraUseCases error", e);
            Toast.makeText(this, "Failed to start camera", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------- Recording control ----------------
    private void startRecording() {
        // Debug logging for permission status
        Log.d(TAG, "=== startRecording called ===");
        Log.d(TAG, "CAMERA permission: " +
                (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED));
        Log.d(TAG, "RECORD_AUDIO permission: " +
                (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED));

        // Guard: permissions must be granted before preparing recording
        if (!allPermissionsGranted()) {
            Log.w(TAG, "Not all permissions granted, requesting...");

            // Check if we should show rationale
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA) ||
                    ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
                // Show explanation dialog
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Permissions Required")
                        .setMessage("SafeNAV needs Camera and Microphone permissions to record video with audio and IMU data for navigation purposes.")
                        .setPositiveButton("Grant Permissions", (dialog, which) -> {
                            requestPermissionsLauncher.launch(REQUIRED_PERMISSIONS);
                        })
                        .setNegativeButton("Cancel", (dialog, which) -> {
                            Toast.makeText(this, "Cannot record without permissions", Toast.LENGTH_LONG).show();
                        })
                        .show();
            } else {
                // User has permanently denied - direct them to settings
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Permissions Denied")
                        .setMessage("Please enable Camera and Microphone permissions in app settings.")
                        .setPositiveButton("Open Settings", (dialog, which) -> {
                            android.content.Intent intent = new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            android.net.Uri uri = android.net.Uri.fromParts("package", getPackageName(), null);
                            intent.setData(uri);
                            startActivity(intent);
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
            return;
        }

        // Double-check RECORD_AUDIO specifically (common issue)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO permission not granted");
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show();
            requestPermissionsLauncher.launch(REQUIRED_PERMISSIONS);
            return;
        }

        if (videoCapture == null) {
            Log.w(TAG, "videoCapture is null - camera not ready");
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show();
            return;
        }
        if (activeRecording != null) {
            Log.w(TAG, "Already recording");
            Toast.makeText(this, "Already recording", Toast.LENGTH_SHORT).show();
            return;
        }

        // Prepare files
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File moviesDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (moviesDir == null) moviesDir = getFilesDir();
        File outVideo = new File(moviesDir, "safenav_video_" + stamp + ".mp4");
        Log.i(TAG, "Video will be saved to: " + outVideo.getAbsolutePath());

        File docsDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (docsDir == null) docsDir = getFilesDir();
        imuFile = new File(docsDir, "safenav_imu_" + stamp + ".csv");
        Log.i(TAG, "IMU will be saved to: " + imuFile.getAbsolutePath());

        // Open writer and write header
        try {
            imuWriter = new BufferedWriter(new FileWriter(imuFile));
            imuWriter.write("timestamp_ns,gx,gy,gz,ax,ay,az\n");
        } catch (IOException e) {
            Log.e(TAG, "Failed to open IMU file", e);
            imuWriter = null;
            Toast.makeText(this, "Cannot open IMU file", Toast.LENGTH_SHORT).show();
            return;
        }

        // Prepare FileOutputOptions to save to chosen file
        FileOutputOptions outputOptions = new FileOutputOptions.Builder(outVideo).build();

        try {
            Log.i(TAG, "Preparing recording...");

            // Log permission status one more time right before the call
            Log.d(TAG, "Final permission check before prepareRecording:");
            Log.d(TAG, "  CAMERA: " + (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED));
            Log.d(TAG, "  RECORD_AUDIO: " + (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED));

            // Check if we can actually prepare recording
            if (videoCapture.getOutput() == null) {
                Log.e(TAG, "videoCapture.getOutput() is null!");
                Toast.makeText(this, "Camera recorder not available", Toast.LENGTH_SHORT).show();
                return;
            }

            // TEMPORARY TEST: Try without audio first to diagnose
            Log.i(TAG, "Calling prepareRecording WITHOUT audio for testing...");
            PendingRecording pending = videoCapture.getOutput().prepareRecording(this, outputOptions);
            // TODO: Re-enable audio after testing: .withAudioEnabled();

            Log.i(TAG, "prepareRecording succeeded, pending recording created");

            // Reset sync variables
            sensorBootOffsetNanos = null;
            recordingStartRealtimeNanos = 0L;

            // Register sensors
            if (sensorManager != null) {
                if (accelSensor != null) {
                    // Use SENSOR_DELAY_GAME instead of FASTEST to avoid needing HIGH_SAMPLING_RATE_SENSORS permission
                    // GAME = ~20ms (50Hz), FASTEST = as fast as possible but needs permission
                    sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME);
                    Log.i(TAG, "Accelerometer registered");
                }
                if (gyroSensor != null) {
                    sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME);
                    Log.i(TAG, "Gyroscope registered");
                }
            }

            Log.i(TAG, "Starting recording...");
            activeRecording = pending.start(ContextCompat.getMainExecutor(this), this::onVideoEvent);

            // UI
            startBtn.setEnabled(false);
            stopBtn.setEnabled(true);
            Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show();
            Log.i(TAG, "Recording started successfully");

        } catch (SecurityException se) {
            Log.e(TAG, "SecurityException in startRecording", se);
            Log.e(TAG, "SecurityException message: " + se.getMessage());
            Log.e(TAG, "SecurityException cause: " + se.getCause());

            // Log current permission state again
            Log.e(TAG, "Permission state at exception time:");
            Log.e(TAG, "  CAMERA: " + (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED));
            Log.e(TAG, "  RECORD_AUDIO: " + (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED));

            // Check if permissions are in manifest
            try {
                PackageManager pm = getPackageManager();
                String[] requestedPermissions = pm.getPackageInfo(getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions;
                Log.e(TAG, "Permissions in manifest:");
                if (requestedPermissions != null) {
                    for (String perm : requestedPermissions) {
                        Log.e(TAG, "  - " + perm);
                    }
                } else {
                    Log.e(TAG, "  No permissions found in manifest!");
                }
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "Could not read package info", e);
            }

            Toast.makeText(this, "Security error - check logcat for details", Toast.LENGTH_LONG).show();
            // Clean up
            try {
                if (imuWriter != null) {
                    imuWriter.close();
                    imuWriter = null;
                }
            } catch (IOException ignored) {}
        } catch (Exception e) {
            Log.e(TAG, "Exception in startRecording", e);
            Log.e(TAG, "Exception type: " + e.getClass().getName());
            Log.e(TAG, "Exception message: " + e.getMessage());
            Toast.makeText(this, "Failed to start recording: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            try {
                if (imuWriter != null) {
                    imuWriter.close();
                    imuWriter = null;
                }
            } catch (IOException ignored) {}
        }
    }

    private void onVideoEvent(VideoRecordEvent event) {
        if (event instanceof VideoRecordEvent.Start) {
            // At Start event, capture the elapsedRealtime baseline for aligning timestamps
            recordingStartRealtimeNanos = SystemClock.elapsedRealtimeNanos();
            Log.i(TAG, "Video start event. recordingStartRealtimeNanos=" + recordingStartRealtimeNanos);
        } else if (event instanceof VideoRecordEvent.Finalize) {
            VideoRecordEvent.Finalize f = (VideoRecordEvent.Finalize) event;
            if (f.hasError()) {
                Log.e(TAG, "Recording finalize error: " + f.getError());
                Toast.makeText(this, "Recording error: " + f.getError(), Toast.LENGTH_LONG).show();
            } else {
                Log.i(TAG, "Recording finalized successfully");
                Toast.makeText(this, "Recording saved", Toast.LENGTH_SHORT).show();
            }
            // Stop IMU logging and close writer
            stopIMULogging();

            // Reset UI and activeRecording
            activeRecording = null;
            runOnUiThread(() -> {
                startBtn.setEnabled(true);
                stopBtn.setEnabled(false);
            });
        }
    }

    private void stopRecording() {
        Log.i(TAG, "stopRecording called");
        if (activeRecording != null) {
            activeRecording.stop(); // triggers Finalize event -> onVideoEvent -> stopIMULogging()
            Log.i(TAG, "Active recording stopped");
        } else {
            // Not recording but make sure we stop sensors & writer
            Log.w(TAG, "No active recording to stop");
            stopIMULogging();
            startBtn.setEnabled(true);
            stopBtn.setEnabled(false);
        }
    }

    private void stopIMULogging() {
        Log.i(TAG, "stopIMULogging called");
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
            Log.i(TAG, "Sensors unregistered");
        }
        if (imuWriter != null) {
            try {
                imuWriter.flush();
                imuWriter.close();
                Log.i(TAG, "IMU file saved: " + imuFile.getAbsolutePath());
                Toast.makeText(this, "IMU saved: " + imuFile.getName(), Toast.LENGTH_LONG).show();
            } catch (IOException e) {
                Log.e(TAG, "Failed to close IMU writer", e);
            }
            imuWriter = null;
        }
    }

    // ------------- Sensor callbacks -------------
    @Override
    public void onSensorChanged(@NonNull SensorEvent event) {
        // Only log if we have writer
        if (imuWriter == null) return;

        // Compute sensor boot offset once to map sensor.timestamp -> elapsedRealtimeNanos
        if (sensorBootOffsetNanos == null) {
            long nowElapsed = SystemClock.elapsedRealtimeNanos();
            sensorBootOffsetNanos = nowElapsed - event.timestamp;
            Log.i(TAG, "Computed sensorBootOffsetNanos=" + sensorBootOffsetNanos);
        }

        // Convert event.timestamp (device/boot-based ns) to elapsedRealtime base (ns)
        long sensorElapsedRealtimeNanos = event.timestamp + sensorBootOffsetNanos;

        // Update latest values
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            latestAccel[0] = event.values[0];
            latestAccel[1] = event.values[1];
            latestAccel[2] = event.values[2];
        } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            latestGyro[0] = event.values[0];
            latestGyro[1] = event.values[1];
            latestGyro[2] = event.values[2];
        }

        // Write a combined line on every event using the timestamp of this sensor event (ns)
        // Format: timestamp_ns, gx, gy, gz, ax, ay, az
        try {
            long writeTsNs = sensorElapsedRealtimeNanos;
            String line = String.format(Locale.US, "%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f\n",
                    writeTsNs,
                    (double) latestGyro[0], (double) latestGyro[1], (double) latestGyro[2],
                    (double) latestAccel[0], (double) latestAccel[1], (double) latestAccel[2]
            );
            imuWriter.write(line);
        } catch (IOException e) {
            Log.e(TAG, "Failed to write IMU", e);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // ignore
    }

    // ------------- Lifecycle methods -------------
    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "onResume - checking permissions");
        // Recheck permissions when activity resumes
        if (!allPermissionsGranted()) {
            startBtn.setEnabled(false);
            stopBtn.setEnabled(false);
            Log.w(TAG, "Permissions not granted in onResume");
            Toast.makeText(this, "Please grant all required permissions", Toast.LENGTH_SHORT).show();
        } else {
            if (activeRecording == null) {
                startBtn.setEnabled(true);
                stopBtn.setEnabled(false);
            }
            Log.i(TAG, "All permissions granted in onResume");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy called");
        if (activeRecording != null) {
            try {
                activeRecording.stop();
            } catch (Exception ignored) {}
            activeRecording = null;
        }
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (imuWriter != null) {
            try {
                imuWriter.flush();
                imuWriter.close();
            } catch (IOException ignored) {}
            imuWriter = null;
        }
    }
}