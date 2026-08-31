package com.example.localsensorcloud;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.ImageFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

public class StreamService extends Service implements SensorEventListener {
    public static final String ACTION_START = "com.example.localsensorcloud.START";
    public static final String ACTION_STOP = "com.example.localsensorcloud.STOP";
    public static final String ACTION_SNAPSHOT = "com.example.localsensorcloud.SNAPSHOT";
    public static final String ACTION_PREVIEW_ON = "com.example.localsensorcloud.PREVIEW_ON";
    public static final String ACTION_PREVIEW_OFF = "com.example.localsensorcloud.PREVIEW_OFF";
    public static final String STATUS_ACTION = "com.example.localsensorcloud.STATUS";
    public static final String EXTRA_ENDPOINT = "endpoint";
    public static final String EXTRA_DEVICE_ID = "deviceId";
    public static final String EXTRA_DEVICE_NAME = "deviceName";
    public static final String EXTRA_TELEMETRY_INTERVAL_MS = "telemetryIntervalMs";
    public static final String EXTRA_PHOTO_INTERVAL_MS = "photoIntervalMs";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_DETAIL = "detail";
    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_TELEMETRY = "telemetry";
    public static final String EXTRA_PREVIEW_JPEG = "previewJpeg";
    public static final String EXTRA_CAMERA = "camera";

    private static final String TAG = "LocalSensorCloud";
    private static final String CHANNEL_ID = "sensor_stream";
    private static final int NOTIFICATION_ID = 701;
    private static final long FRAME_INTERVAL_MS = 500;
    private static final long DEFAULT_TELEMETRY_INTERVAL_MS = 1000;
    private static final long DEFAULT_PHOTO_INTERVAL_MS = 5000;
    private static final long MAX_INTERVAL_MS = TimeUnit.DAYS.toMillis(1);
    private static final byte[] APPLICATION_ENCRYPTION_MAGIC = new byte[] { 'L', 'S', 'C', '2' };
    private static final String APPLICATION_ENCRYPTION_HEADER = "aes-256-gcm-v2";
    private static final String APPLICATION_AAD_PREFIX = "LocalSensorCloud AES-GCM v2\n";
    private static final int APPLICATION_IV_LENGTH = 12;
    private static volatile boolean running;

    private final Map<String, SensorSnapshot> latestSensors = new ConcurrentHashMap<>();
    private final AtomicBoolean telemetryUploadInFlight = new AtomicBoolean(false);
    private final AtomicLong frameCount = new AtomicLong();
    private final AtomicLong capturedFrameCount = new AtomicLong();
    private final AtomicLong uploadFailures = new AtomicLong();

    private String endpoint;
    private String deviceId;
    private String deviceName;
    private SensorManager sensorManager;
    private HandlerThread sensorThread;
    private HandlerThread cameraThread;
    private Handler sensorHandler;
    private Handler cameraHandler;
    private ScheduledExecutorService networkExecutor;
    private ScheduledFuture<?> telemetryFuture;
    private SSLSocketFactory pinnedSslSocketFactory;
    private byte[] pinnedCertificateBytes;
    private String tlsInitializationError;
    private SecretKeySpec applicationEncryptionKey;
    private String applicationEncryptionError;
    private final SecureRandom secureRandom = new SecureRandom();
    private AudioRecord audioRecord;
    private Thread audioThread;
    private volatile boolean audioActive;
    private volatile double noiseDbfs = -120.0;
    private volatile double noisePeak = 0.0;
    private final List<CameraPipeline> cameraPipelines = new CopyOnWriteArrayList<>();
    private CameraManager cameraManager;
    private volatile String cameraMode = "none";
    private int activeCameraIndex;
    private volatile boolean previewBroadcastEnabled;
    private volatile long telemetryIntervalMs = DEFAULT_TELEMETRY_INTERVAL_MS;
    private volatile long photoIntervalMs = DEFAULT_PHOTO_INTERVAL_MS;
    private volatile boolean uploadAttempted;
    private volatile boolean lastUploadSucceeded;
    private final Runnable switchAlternatingCamera = this::switchAlternatingCamera;
    private final Runnable localTelemetryPublisher = this::publishLocalTelemetry;

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sensorThread = new HandlerThread("sensor-events", Process.THREAD_PRIORITY_MORE_FAVORABLE);
        sensorThread.start();
        sensorHandler = new Handler(sensorThread.getLooper());
        cameraThread = new HandlerThread("camera-stream", Process.THREAD_PRIORITY_DISPLAY);
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        networkExecutor = Executors.newScheduledThreadPool(3);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            sendStatus(false, "Stream stopped", "Data remains stored on the laptop");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_SNAPSHOT.equals(action)) {
            if (running) {
                for (CameraPipeline pipeline : cameraPipelines) pipeline.photoRequested.set(true);
                sendStatus(true, "Dual photo requested", "The next front and back frames will be saved on the laptop");
            }
            return START_NOT_STICKY;
        }
        if (ACTION_PREVIEW_ON.equals(action)) {
            previewBroadcastEnabled = true;
            return START_NOT_STICKY;
        }
        if (ACTION_PREVIEW_OFF.equals(action)) {
            previewBroadcastEnabled = false;
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            endpoint = normalizeEndpoint(intent.getStringExtra(EXTRA_ENDPOINT));
            deviceId = stringOrFallback(intent.getStringExtra(EXTRA_DEVICE_ID), "android-device");
            deviceName = stringOrFallback(intent.getStringExtra(EXTRA_DEVICE_NAME), Build.MODEL);
            telemetryIntervalMs = safeInterval(intent.getLongExtra(EXTRA_TELEMETRY_INTERVAL_MS, DEFAULT_TELEMETRY_INTERVAL_MS),
                    DEFAULT_TELEMETRY_INTERVAL_MS);
            photoIntervalMs = safeInterval(intent.getLongExtra(EXTRA_PHOTO_INTERVAL_MS, DEFAULT_PHOTO_INTERVAL_MS),
                    DEFAULT_PHOTO_INTERVAL_MS);
            previewBroadcastEnabled = true;
            startForegroundCompat();
            if (!initializePairedSecurity()) {
                sendStatus(false, "Laptop is not paired", "Return to the app and ask the laptop to approve this phone");
                stopSelf();
                return START_NOT_STICKY;
            }
            startStreaming();
            return START_NOT_STICKY;
        }
        if (!running) stopSelf();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopStreaming();
        if (networkExecutor != null) networkExecutor.shutdownNow();
        if (sensorThread != null) sensorThread.quitSafely();
        if (cameraThread != null) cameraThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startStreaming() {
        if (running) {
            stopSensors();
            stopAudio();
            closeCamera();
        }
        running = true;
        frameCount.set(0);
        capturedFrameCount.set(0);
        uploadFailures.set(0);
        uploadAttempted = false;
        lastUploadSucceeded = false;
        startSensors();
        startAudio();
        startCamera();
        if (telemetryFuture != null) telemetryFuture.cancel(false);
        telemetryFuture = networkExecutor.scheduleWithFixedDelay(this::uploadTelemetry, 300,
                telemetryIntervalMs, TimeUnit.MILLISECONDS);
        sensorHandler.removeCallbacks(localTelemetryPublisher);
        sensorHandler.post(localTelemetryPublisher);
        sendStatus(true, "Starting sensor stream", "Data " + intervalLabel(telemetryIntervalMs)
                + " · photos " + intervalLabel(photoIntervalMs));
    }

    private void stopStreaming() {
        running = false;
        if (telemetryFuture != null) {
            telemetryFuture.cancel(false);
            telemetryFuture = null;
        }
        if (sensorHandler != null) sensorHandler.removeCallbacks(localTelemetryPublisher);
        stopSensors();
        stopAudio();
        closeCamera();
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private void startForegroundCompat() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stopIntent = new Intent(this, StreamService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(com.example.localsensorcloud.R.drawable.app_icon)
                .setContentTitle("Local Sensor Cloud is streaming")
                .setContentText("Camera, sound level, and sensors → laptop")
                .setContentIntent(pendingIntent)
                .addAction(new Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(this, R.drawable.app_icon),
                        "Stop", stopPendingIntent).build())
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();

        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Sensor streaming", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps the camera and sensors streaming to your laptop");
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    private void startSensors() {
        latestSensors.clear();
        if (sensorManager == null) return;
        List<Sensor> sensors = sensorManager.getSensorList(Sensor.TYPE_ALL);
        int registered = 0;
        for (Sensor sensor : sensors) {
            try {
                if (sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, sensorHandler)) registered++;
            } catch (SecurityException | IllegalArgumentException error) {
                Log.w(TAG, "Skipping protected or unsupported sensor: " + sensor.getName(), error);
            }
        }
        Log.i(TAG, "Registered " + registered + " of " + sensors.size() + " sensors");
    }

    private void stopSensors() {
        if (sensorManager != null) sensorManager.unregisterListener(this);
        latestSensors.clear();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        SensorSnapshot snapshot = new SensorSnapshot(event.sensor, event.values.clone(), event.accuracy, event.timestamp);
        latestSensors.put(sensorKey(event.sensor), snapshot);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        SensorSnapshot previous = latestSensors.get(sensorKey(sensor));
        if (previous != null) previous.accuracy = accuracy;
    }

    private String sensorKey(Sensor sensor) {
        return sensor.getType() + ":" + sensor.getName() + ":" + sensor.getVendor();
    }

    @SuppressLint("MissingPermission")
    private void startAudio() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            sendStatus(true, "Streaming without microphone", "Record-audio permission was not granted");
            return;
        }
        int sampleRate = 16_000;
        int minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = Math.max(minimum, 4096);
        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2);
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release();
                audioRecord = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, sampleRate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2);
            }
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("AudioRecord could not initialize");
            audioActive = true;
            audioRecord.startRecording();
            audioThread = new Thread(() -> measureAudio(bufferSize), "sound-level");
            audioThread.start();
        } catch (RuntimeException error) {
            Log.e(TAG, "Could not start microphone", error);
            stopAudio();
        }
    }

    private void measureAudio(int bufferSize) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        short[] buffer = new short[bufferSize];
        while (audioActive && audioRecord != null) {
            int read;
            try {
                read = audioRecord.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
            } catch (RuntimeException error) {
                break;
            }
            if (read <= 0) continue;
            double sumSquares = 0;
            int peak = 0;
            for (int index = 0; index < read; index++) {
                int sample = Math.abs((int) buffer[index]);
                sumSquares += (double) sample * sample;
                if (sample > peak) peak = sample;
            }
            double rms = Math.sqrt(sumSquares / read);
            noiseDbfs = Math.max(-120.0, 20.0 * Math.log10(Math.max(1.0, rms) / 32768.0));
            noisePeak = Math.min(1.0, peak / 32768.0);
        }
    }

    private void stopAudio() {
        audioActive = false;
        AudioRecord record = audioRecord;
        audioRecord = null;
        if (record != null) {
            try { record.stop(); } catch (RuntimeException ignored) { }
            record.release();
        }
        Thread thread = audioThread;
        audioThread = null;
        if (thread != null) {
            try { thread.join(500); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }
    }

    @SuppressLint("MissingPermission")
    private void startCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            sendStatus(true, "Streaming without camera", "Camera permission was not granted");
            return;
        }
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            String backId = null;
            String frontId = null;
            for (String id : cameraManager.getCameraIdList()) {
                Integer facing = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK && backId == null) backId = id;
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT && frontId == null) frontId = id;
            }
            if (backId != null) cameraPipelines.add(new CameraPipeline(backId, "back", cameraManager.getCameraCharacteristics(backId)));
            if (frontId != null) cameraPipelines.add(new CameraPipeline(frontId, "front", cameraManager.getCameraCharacteristics(frontId)));
            if (cameraPipelines.isEmpty()) {
                cameraMode = "not-available";
                sendStatus(true, "Streaming sensor data", "This device has no compatible camera");
                return;
            }
            if (cameraPipelines.size() == 2 && supportsConcurrentCameras(backId, frontId)) {
                cameraMode = "concurrent";
                for (CameraPipeline pipeline : cameraPipelines) pipeline.open();
            } else if (cameraPipelines.size() == 2) {
                cameraMode = "alternating";
                activeCameraIndex = 0;
                cameraPipelines.get(0).open();
                cameraHandler.postDelayed(switchAlternatingCamera, 6500);
            } else {
                cameraMode = "single";
                cameraPipelines.get(0).open();
            }
        } catch (CameraAccessException | RuntimeException error) {
            cameraMode = "open-exception";
            Log.e(TAG, "Could not enumerate cameras", error);
            sendStatus(true, "Camera unavailable", "Camera discovery failed; other sensors continue streaming");
        }
    }

    private boolean supportsConcurrentCameras(String backId, String frontId) throws CameraAccessException {
        if (Build.VERSION.SDK_INT < 30 || backId == null || frontId == null) return false;
        for (Set<String> ids : cameraManager.getConcurrentCameraIds()) {
            if (ids.contains(backId) && ids.contains(frontId)) return true;
        }
        return false;
    }

    private void switchAlternatingCamera() {
        if (!running || !"alternating".equals(cameraMode) || cameraPipelines.size() < 2) return;
        cameraPipelines.get(activeCameraIndex).close();
        activeCameraIndex = (activeCameraIndex + 1) % cameraPipelines.size();
        CameraPipeline next = cameraPipelines.get(activeCameraIndex);
        cameraHandler.postDelayed(next::open, 350);
        cameraHandler.postDelayed(switchAlternatingCamera, 6500);
    }

    private void fallbackToAlternatingCameras() {
        if (!"concurrent".equals(cameraMode) || cameraPipelines.size() < 2) return;
        for (CameraPipeline pipeline : cameraPipelines) pipeline.close();
        cameraMode = "alternating";
        activeCameraIndex = 0;
        cameraHandler.postDelayed(cameraPipelines.get(0)::open, 400);
        cameraHandler.postDelayed(switchAlternatingCamera, 6900);
    }

    private Size selectCameraSize(CameraCharacteristics characteristics, int format) {
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null || map.getOutputSizes(format) == null) return new Size(640, 480);
        List<Size> candidates = new ArrayList<>(Arrays.asList(map.getOutputSizes(format)));
        candidates.removeIf(size -> size.getWidth() > 640 || size.getHeight() > 480);
        if (candidates.isEmpty()) candidates = Arrays.asList(map.getOutputSizes(format));
        return candidates.stream().max(Comparator.comparingLong(size -> (long) size.getWidth() * size.getHeight())).orElse(new Size(640, 480));
    }

    private void closeCamera() {
        if (cameraHandler != null) cameraHandler.removeCallbacks(switchAlternatingCamera);
        for (CameraPipeline pipeline : cameraPipelines) pipeline.close();
        cameraPipelines.clear();
        cameraMode = "none";
    }

    private String cameraSummary() {
        StringBuilder summary = new StringBuilder(cameraMode);
        for (CameraPipeline pipeline : cameraPipelines) {
            summary.append(' ').append(pipeline.label).append('=').append(pipeline.state);
        }
        return summary.toString();
    }

    private void uploadPhoto(byte[] jpeg, boolean manual, CameraPipeline pipeline) {
        try {
            String route = "/api/frame?deviceId=" + java.net.URLEncoder.encode(deviceId, "UTF-8")
                    + "&camera=" + pipeline.label
                    + "&capture=photo";
            post(route, "image/jpeg", jpeg);
            frameCount.incrementAndGet();
            pipeline.sentFrames.incrementAndGet();
            if (manual) sendStatus(true, pipeline.displayName() + " photo saved", "Automatic schedule continues");
        } catch (IOException error) {
            uploadFailures.incrementAndGet();
            Log.w(TAG, pipeline.label + " photo upload failed", error);
        } finally {
            pipeline.uploadInFlight.set(false);
        }
    }

    private void sendPreview(String camera, byte[] jpeg) {
        Intent preview = new Intent(STATUS_ACTION)
                .setPackage(getPackageName())
                .putExtra(EXTRA_CAMERA, camera)
                .putExtra(EXTRA_PREVIEW_JPEG, jpeg);
        sendBroadcast(preview);
    }

    private final class CameraPipeline {
        final String id;
        final String label;
        final CameraCharacteristics characteristics;
        final AtomicBoolean uploadInFlight = new AtomicBoolean(false);
        final AtomicBoolean photoRequested = new AtomicBoolean(false);
        final AtomicLong capturedFrames = new AtomicLong();
        final AtomicLong sentFrames = new AtomicLong();
        final Runnable captureFrame = this::captureNext;
        volatile String state = "idle";
        volatile boolean active;
        CameraDevice device;
        CameraCaptureSession session;
        ImageReader jpegReader;
        ImageReader exposureReader;
        CaptureRequest stillRequest;
        Size captureSize;
        Size exposureSize;
        long lastAutomaticPhotoAt;

        CameraPipeline(String id, String label, CameraCharacteristics characteristics) {
            this.id = id;
            this.label = label;
            this.characteristics = characteristics;
        }

        String displayName() {
            return label.substring(0, 1).toUpperCase(Locale.US) + label.substring(1) + " camera";
        }

        @SuppressLint("MissingPermission")
        void open() {
            if (!running || active) return;
            active = true;
            state = "opening";
            captureSize = selectCameraSize(characteristics, ImageFormat.JPEG);
            exposureSize = selectCameraSize(characteristics, ImageFormat.YUV_420_888);
            jpegReader = ImageReader.newInstance(captureSize.getWidth(), captureSize.getHeight(), ImageFormat.JPEG, 3);
            jpegReader.setOnImageAvailableListener(this::onJpegAvailable, cameraHandler);
            exposureReader = ImageReader.newInstance(exposureSize.getWidth(), exposureSize.getHeight(), ImageFormat.YUV_420_888, 3);
            exposureReader.setOnImageAvailableListener(reader -> {
                try (Image ignored = reader.acquireLatestImage()) { }
            }, cameraHandler);
            try {
                cameraManager.openCamera(id, new CameraDevice.StateCallback() {
                    @Override public void onOpened(CameraDevice camera) {
                        if (!active) { camera.close(); return; }
                        device = camera;
                        state = "opened";
                        configure();
                    }
                    @Override public void onDisconnected(CameraDevice camera) {
                        state = "disconnected";
                        camera.close();
                        if (device == camera) device = null;
                    }
                    @Override public void onError(CameraDevice camera, int error) {
                        state = "error-" + error;
                        camera.close();
                        if (device == camera) device = null;
                        if (error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE) fallbackToAlternatingCameras();
                    }
                }, cameraHandler);
            } catch (CameraAccessException | RuntimeException error) {
                state = "open-exception";
                Log.e(TAG, "Could not open " + label + " camera", error);
            }
        }

        void configure() {
            if (device == null || jpegReader == null || exposureReader == null || !active) return;
            try {
                CaptureRequest.Builder still = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                still.addTarget(jpegReader.getSurface());
                still.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                still.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                still.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                still.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE);
                still.set(CaptureRequest.JPEG_QUALITY, (byte) 70);
                Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                if (orientation != null) still.set(CaptureRequest.JPEG_ORIENTATION, orientation);
                stillRequest = still.build();

                CaptureRequest.Builder exposure = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                exposure.addTarget(exposureReader.getSurface());
                exposure.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                exposure.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                exposure.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                CaptureRequest exposureRequest = exposure.build();
                List<Surface> outputs = Arrays.asList(exposureReader.getSurface(), jpegReader.getSurface());

                device.createCaptureSession(outputs, new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession configured) {
                        if (!active) { configured.close(); return; }
                        session = configured;
                        state = "warming-up";
                        try {
                            configured.setRepeatingRequest(exposureRequest, null, cameraHandler);
                            cameraHandler.postDelayed(captureFrame, 1200);
                        } catch (CameraAccessException error) {
                            state = "preview-failed";
                        }
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession failed) {
                        state = "configure-failed";
                    }
                }, cameraHandler);
            } catch (CameraAccessException error) {
                state = "session-exception";
            }
        }

        void captureNext() {
            CameraCaptureSession currentSession = session;
            CaptureRequest request = stillRequest;
            if (!running || !active || currentSession == null || request == null) return;
            try {
                state = "capturing";
                currentSession.capture(request, new CameraCaptureSession.CaptureCallback() {
                    @Override public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                        state = "active";
                        if (running && active) cameraHandler.postDelayed(captureFrame, FRAME_INTERVAL_MS);
                    }
                    @Override public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
                        state = "capture-failed-" + failure.getReason();
                        if (running && active) cameraHandler.postDelayed(captureFrame, 1000);
                    }
                }, cameraHandler);
            } catch (CameraAccessException | IllegalStateException error) {
                state = "capture-exception";
                if (running && active) cameraHandler.postDelayed(captureFrame, 1000);
            }
        }

        void onJpegAvailable(ImageReader reader) {
            try (Image image = reader.acquireLatestImage()) {
                if (image == null || !running || !active) return;
                capturedFrameCount.incrementAndGet();
                capturedFrames.incrementAndGet();
                state = "frame-ready";
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] jpeg = new byte[buffer.remaining()];
                buffer.get(jpeg);
                if (previewBroadcastEnabled) sendPreview(label, jpeg);

                boolean manual = photoRequested.getAndSet(false);
                long now = SystemClock.elapsedRealtime();
                boolean automatic = lastAutomaticPhotoAt == 0 || now - lastAutomaticPhotoAt >= photoIntervalMs;
                if (!manual && !automatic) return;
                if (!uploadInFlight.compareAndSet(false, true)) {
                    if (manual) photoRequested.set(true);
                    return;
                }
                if (automatic) lastAutomaticPhotoAt = now;
                networkExecutor.execute(() -> uploadPhoto(jpeg, manual, this));
            } catch (RuntimeException error) {
                state = "image-exception";
            }
        }

        void close() {
            active = false;
            cameraHandler.removeCallbacks(captureFrame);
            stillRequest = null;
            CameraCaptureSession closingSession = session;
            session = null;
            if (closingSession != null) {
                try { closingSession.stopRepeating(); } catch (CameraAccessException | IllegalStateException ignored) { }
                closingSession.close();
            }
            CameraDevice closingDevice = device;
            device = null;
            if (closingDevice != null) closingDevice.close();
            ImageReader closingJpeg = jpegReader;
            jpegReader = null;
            if (closingJpeg != null) closingJpeg.close();
            ImageReader closingExposure = exposureReader;
            exposureReader = null;
            if (closingExposure != null) closingExposure.close();
            uploadInFlight.set(false);
            state = "stopped";
        }

        JSONObject toJson() throws JSONException {
            JSONObject value = new JSONObject();
            value.put("state", state);
            value.put("active", active);
            value.put("framesCaptured", capturedFrames.get());
            value.put("framesSent", sentFrames.get());
            if (captureSize != null) {
                value.put("width", captureSize.getWidth());
                value.put("height", captureSize.getHeight());
            }
            return value;
        }
    }

    private void uploadTelemetry() {
        if (!running || !telemetryUploadInFlight.compareAndSet(false, true)) return;
        JSONObject telemetry = null;
        try {
            telemetry = buildTelemetry();
            post("/api/telemetry", "application/json; charset=utf-8", telemetry.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            uploadAttempted = true;
            lastUploadSucceeded = true;
            sendCurrentStatus(telemetry);
        } catch (IOException | JSONException error) {
            long failures = uploadFailures.incrementAndGet();
            uploadAttempted = true;
            lastUploadSucceeded = false;
            Log.w(TAG, "Telemetry upload failed", error);
            sendStatus(true, "Cannot reach laptop", "Upload failed " + failures + "× · values stay live · retry "
                    + intervalLabel(telemetryIntervalMs), telemetry);
        } finally {
            telemetryUploadInFlight.set(false);
        }
    }

    private void publishLocalTelemetry() {
        if (!running) return;
        try {
            sendCurrentStatus(buildTelemetry());
        } catch (JSONException error) {
            Log.w(TAG, "Could not build local telemetry display", error);
        } finally {
            if (running) sensorHandler.postDelayed(localTelemetryPublisher, 1000);
        }
    }

    private void sendCurrentStatus(JSONObject telemetry) {
        String schedule = "data " + intervalLabel(telemetryIntervalMs) + " · photos " + intervalLabel(photoIntervalMs);
        if (!uploadAttempted) {
            sendStatus(true, "Starting sensor stream", "Waiting for first upload · " + schedule, telemetry);
            return;
        }
        if (!lastUploadSucceeded) {
            sendStatus(true, "Cannot reach laptop", "Upload failed " + uploadFailures.get()
                    + "× · values stay live · " + schedule, telemetry);
            return;
        }
        String sound = String.format(Locale.US, "%.1f dBFS", noiseDbfs);
        String detail = latestSensors.size() + " active sensors · " + sound + " · " + frameCount.get()
                + " photos sent · " + schedule;
        sendStatus(true, "Streaming on schedule", detail, telemetry);
    }

    private long safeInterval(long requested, long fallback) {
        return requested >= 1000 && requested <= MAX_INTERVAL_MS ? requested : fallback;
    }

    private String intervalLabel(long intervalMs) {
        if (intervalMs >= TimeUnit.MINUTES.toMillis(1) && intervalMs % TimeUnit.MINUTES.toMillis(1) == 0) {
            long minutes = TimeUnit.MILLISECONDS.toMinutes(intervalMs);
            return "every " + minutes + (minutes == 1 ? " minute" : " minutes");
        }
        long seconds = TimeUnit.MILLISECONDS.toSeconds(intervalMs);
        return "every " + seconds + (seconds == 1 ? " second" : " seconds");
    }

    private JSONObject buildTelemetry() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", 1);
        root.put("deviceId", deviceId);
        root.put("deviceName", deviceName);
        root.put("sentAt", Instant.now().toString());

        JSONObject noise = new JSONObject();
        noise.put("dbfs", round(noiseDbfs, 2));
        noise.put("peakNormalized", round(noisePeak, 4));
        noise.put("calibration", "relative-dBFS");
        root.put("noise", noise);

        JSONArray sensors = new JSONArray();
        Double pressure = null;
        Double temperature = null;
        Double light = null;
        List<SensorSnapshot> snapshots = new ArrayList<>(latestSensors.values());
        snapshots.sort(Comparator.comparingInt(snapshot -> snapshot.sensor.getType()));
        for (SensorSnapshot snapshot : snapshots) {
            sensors.put(snapshot.toJson());
            if (snapshot.values.length > 0) {
                if (snapshot.sensor.getType() == Sensor.TYPE_PRESSURE) pressure = (double) snapshot.values[0];
                if (snapshot.sensor.getType() == Sensor.TYPE_AMBIENT_TEMPERATURE) temperature = (double) snapshot.values[0];
                if (snapshot.sensor.getType() == Sensor.TYPE_LIGHT) light = (double) snapshot.values[0];
            }
        }
        root.put("sensors", sensors);

        JSONObject readings = new JSONObject();
        readings.put("pressureHpa", pressure == null ? JSONObject.NULL : round(pressure, 3));
        readings.put("ambientTemperatureC", temperature == null ? JSONObject.NULL : round(temperature, 3));
        readings.put("lightLux", light == null ? JSONObject.NULL : round(light, 3));
        root.put("readings", readings);

        JSONObject device = new JSONObject();
        device.put("manufacturer", Build.MANUFACTURER);
        device.put("model", Build.MODEL);
        device.put("androidVersion", Build.VERSION.RELEASE);
        device.put("sdk", Build.VERSION.SDK_INT);
        device.put("batteryPercent", batteryPercent());
        root.put("device", device);

        JSONObject stream = new JSONObject();
        stream.put("framesSent", frameCount.get());
        stream.put("framesCaptured", capturedFrameCount.get());
        stream.put("uploadFailures", uploadFailures.get());
        stream.put("cameraMode", cameraMode);
        stream.put("telemetryIntervalSeconds", telemetryIntervalMs / 1000.0);
        stream.put("photoIntervalSeconds", photoIntervalMs / 1000.0);
        stream.put("automaticPhotos", true);
        JSONObject cameras = new JSONObject();
        cameras.put("back", new JSONObject().put("state", "not-available").put("framesCaptured", 0).put("framesSent", 0));
        cameras.put("front", new JSONObject().put("state", "not-available").put("framesCaptured", 0).put("framesSent", 0));
        for (CameraPipeline pipeline : cameraPipelines) cameras.put(pipeline.label, pipeline.toJson());
        stream.put("cameras", cameras);
        root.put("stream", stream);
        return root;
    }

    private int batteryPercent() {
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return -1;
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        return level >= 0 && scale > 0 ? Math.round(level * 100f / scale) : -1;
    }

    private boolean initializePairedSecurity() {
        pinnedSslSocketFactory = null;
        pinnedCertificateBytes = null;
        applicationEncryptionKey = null;
        tlsInitializationError = null;
        applicationEncryptionError = null;
        PairingStore.PairingMaterial pairing = PairingStore.load(this, endpoint, deviceId);
        if (pairing == null) {
            tlsInitializationError = "No approved pairing exists for this laptop and phone ID";
            applicationEncryptionError = tlsInitializationError;
            return false;
        }
        try (InputStream certificateInput = new ByteArrayInputStream(pairing.certificateDer)) {
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            Certificate pinnedCertificate = certificateFactory.generateCertificate(certificateInput);
            pinnedCertificateBytes = pinnedCertificate.getEncoded();
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            trustStore.setCertificateEntry("local-sensor-cloud-laptop", pinnedCertificate);
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
            pinnedSslSocketFactory = sslContext.getSocketFactory();
            if (pairing.applicationKey.length != 32) throw new GeneralSecurityException("Invalid paired encryption key");
            applicationEncryptionKey = new SecretKeySpec(pairing.applicationKey, "AES");
            return true;
        } catch (GeneralSecurityException | IOException error) {
            tlsInitializationError = error.getMessage();
            applicationEncryptionError = error.getMessage();
            Log.e(TAG, "Could not initialize pinned TLS", error);
            return false;
        }
    }

    private byte[] encryptApplicationPayload(String route, byte[] plaintext) throws IOException {
        if (applicationEncryptionKey == null) {
            throw new IOException("AES-GCM application encryption is unavailable: " + applicationEncryptionError);
        }
        try {
            byte[] iv = new byte[APPLICATION_IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, applicationEncryptionKey, new GCMParameterSpec(128, iv));
            cipher.updateAAD((APPLICATION_AAD_PREFIX + deviceId + "\n" + route)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] ciphertextAndTag = cipher.doFinal(plaintext);
            byte[] envelope = new byte[APPLICATION_ENCRYPTION_MAGIC.length + iv.length + ciphertextAndTag.length];
            System.arraycopy(APPLICATION_ENCRYPTION_MAGIC, 0, envelope, 0, APPLICATION_ENCRYPTION_MAGIC.length);
            System.arraycopy(iv, 0, envelope, APPLICATION_ENCRYPTION_MAGIC.length, iv.length);
            System.arraycopy(ciphertextAndTag, 0, envelope, APPLICATION_ENCRYPTION_MAGIC.length + iv.length,
                    ciphertextAndTag.length);
            return envelope;
        } catch (GeneralSecurityException error) {
            throw new IOException("Could not encrypt application payload", error);
        }
    }

    private void post(String route, String contentType, byte[] body) throws IOException {
        URL target = new URL(endpoint + route);
        if (!"https".equalsIgnoreCase(target.getProtocol())) throw new IOException("Unencrypted HTTP connections are disabled");
        if (pinnedSslSocketFactory == null) throw new IOException("Pinned TLS is unavailable: " + tlsInitializationError);
        byte[] encryptedBody = encryptApplicationPayload(route, body);
        HttpsURLConnection connection = (HttpsURLConnection) target.openConnection();
        try {
            connection.setSSLSocketFactory(pinnedSslSocketFactory);
            connection.setHostnameVerifier((hostname, session) -> {
                try {
                    Certificate[] peerCertificates = session.getPeerCertificates();
                    return peerCertificates.length > 0
                            && MessageDigest.isEqual(pinnedCertificateBytes, peerCertificates[0].getEncoded());
                } catch (SSLPeerUnverifiedException | GeneralSecurityException error) {
                    return false;
                }
            });
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(7000);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(encryptedBody.length);
            connection.setRequestProperty("Content-Type", "application/octet-stream");
            connection.setRequestProperty("X-Sensor-Encryption", APPLICATION_ENCRYPTION_HEADER);
            connection.setRequestProperty("X-Sensor-Device-Id", deviceId);
            connection.setRequestProperty("X-Sensor-Plaintext-Type", contentType);
            connection.setRequestProperty("User-Agent", "LocalSensorCloud-Android");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(encryptedBody);
            }
            int status = connection.getResponseCode();
            InputStream responseStream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String responseBody = readSmallResponse(responseStream);
            if (status < 200 || status >= 300) {
                throw new IOException("Server returned " + status + (responseBody.trim().isEmpty() ? "" : ": " + responseBody));
            }
        } finally {
            connection.disconnect();
        }
    }

    private String readSmallResponse(InputStream input) throws IOException {
        if (input == null) return "";
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) >= 0 && output.size() < 4096) output.write(buffer, 0, read);
            return new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private void sendStatus(boolean isRunning, String message, String detail) {
        sendStatus(isRunning, message, detail, null);
    }

    private void sendStatus(boolean isRunning, String message, String detail, JSONObject telemetry) {
        Intent intent = new Intent(STATUS_ACTION)
                .setPackage(getPackageName())
                .putExtra(EXTRA_RUNNING, isRunning)
                .putExtra(EXTRA_MESSAGE, message)
                .putExtra(EXTRA_DETAIL, detail);
        if (telemetry != null) intent.putExtra(EXTRA_TELEMETRY, telemetry.toString());
        sendBroadcast(intent);
    }

    private String normalizeEndpoint(String value) {
        String normalized = stringOrEmpty(value).trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    private String stringOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private String stringOrFallback(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static double round(double value, int places) {
        double factor = Math.pow(10, places);
        return Math.round(value * factor) / factor;
    }

    private static String unitFor(int type) {
        return switch (type) {
            case Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GRAVITY, Sensor.TYPE_LINEAR_ACCELERATION -> "m/s²";
            case Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> "m/s²; bias m/s²";
            case Sensor.TYPE_GYROSCOPE -> "rad/s";
            case Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> "rad/s; drift rad/s";
            case Sensor.TYPE_MAGNETIC_FIELD -> "μT";
            case Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> "μT; bias μT";
            case Sensor.TYPE_PRESSURE -> "hPa";
            case Sensor.TYPE_AMBIENT_TEMPERATURE -> "°C";
            case Sensor.TYPE_RELATIVE_HUMIDITY -> "%";
            case Sensor.TYPE_LIGHT -> "lux";
            case Sensor.TYPE_PROXIMITY, Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR -> "platform-defined";
            case Sensor.TYPE_HEART_RATE -> "beats/min";
            case Sensor.TYPE_HINGE_ANGLE -> "degrees";
            default -> "raw SI / platform-defined";
        };
    }

    private static String accuracyLabel(int accuracy) {
        return switch (accuracy) {
            case SensorManager.SENSOR_STATUS_UNRELIABLE -> "unreliable";
            case SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "low";
            case SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "medium";
            case SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "high";
            default -> "unknown";
        };
    }

    private static final class SensorSnapshot {
        final Sensor sensor;
        final float[] values;
        final long capturedAtNanos;
        volatile int accuracy;

        SensorSnapshot(Sensor sensor, float[] values, int accuracy, long capturedAtNanos) {
            this.sensor = sensor;
            this.values = values;
            this.accuracy = accuracy;
            this.capturedAtNanos = capturedAtNanos;
        }

        JSONObject toJson() throws JSONException {
            JSONObject value = new JSONObject();
            value.put("type", sensor.getType());
            value.put("stringType", sensor.getStringType());
            value.put("name", sensor.getName());
            value.put("vendor", sensor.getVendor());
            value.put("version", sensor.getVersion());
            value.put("resolution", sensor.getResolution());
            value.put("powerMa", sensor.getPower());
            value.put("unit", unitFor(sensor.getType()));
            value.put("accuracy", accuracy);
            value.put("accuracyLabel", accuracyLabel(accuracy));
            value.put("capturedAtNanos", capturedAtNanos);
            JSONArray numbers = new JSONArray();
            for (float number : values) numbers.put(round(number, 6));
            value.put("values", numbers);
            return value;
        }
    }
}
