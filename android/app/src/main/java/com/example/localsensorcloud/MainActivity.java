package com.example.localsensorcloud;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 41;
    private static final String PREFS = "local_sensor_cloud";

    private final int background = Color.rgb(9, 16, 13);
    private final int surface = Color.rgb(18, 28, 23);
    private final int surfaceLight = Color.rgb(25, 39, 31);
    private final int outline = Color.rgb(51, 70, 58);
    private final int primary = Color.rgb(118, 230, 156);
    private final int textPrimary = Color.rgb(242, 247, 243);
    private final int textSecondary = Color.rgb(153, 170, 159);

    private EditText endpointInput;
    private EditText deviceNameInput;
    private EditText deviceIdInput;
    private EditText telemetryIntervalInput;
    private EditText photoIntervalInput;
    private Button telemetryUnitButton;
    private Button photoUnitButton;
    private TextView statusLabel;
    private TextView packetLabel;
    private TextView noiseValueLabel;
    private TextView pressureValueLabel;
    private TextView batteryValueLabel;
    private TextView sensorCountLabel;
    private TextView sensorEmptyLabel;
    private LinearLayout sensorBlocksContainer;
    private final Map<String, SensorBlock> sensorBlocks = new LinkedHashMap<>();
    private ImageView backCameraPreview;
    private ImageView frontCameraPreview;
    private TextView backCameraPreviewState;
    private TextView frontCameraPreviewState;
    private Bitmap displayedBackPreviewBitmap;
    private Bitmap displayedFrontPreviewBitmap;
    private Button startButton;
    private Button stopButton;
    private Button snapshotButton;
    private boolean pendingStart;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            byte[] previewJpeg = intent.getByteArrayExtra(StreamService.EXTRA_PREVIEW_JPEG);
            if (previewJpeg != null) {
                updateCameraPreview(intent.getStringExtra(StreamService.EXTRA_CAMERA), previewJpeg);
                return;
            }
            String message = intent.getStringExtra(StreamService.EXTRA_MESSAGE);
            String detail = intent.getStringExtra(StreamService.EXTRA_DETAIL);
            boolean running = intent.getBooleanExtra(StreamService.EXTRA_RUNNING, false);
            updateState(running, message, detail);
            String telemetry = intent.getStringExtra(StreamService.EXTRA_TELEMETRY);
            if (telemetry != null) updateTelemetry(telemetry);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(background);
        window.setNavigationBarColor(background);
        setContentView(buildContent());
        loadPreferences();
        updateState(StreamService.isRunning(), StreamService.isRunning() ? "Streaming in background" : "Ready to connect", "");
    }

    @Override
    @SuppressLint("UnspecifiedRegisterReceiverFlag") // The flag overload and constant exist only on Android 13+.
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(StreamService.STATUS_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
        if (StreamService.isRunning()) sendAction(StreamService.ACTION_PREVIEW_ON);
    }

    @Override
    protected void onStop() {
        if (StreamService.isRunning()) sendAction(StreamService.ACTION_PREVIEW_OFF);
        unregisterReceiver(statusReceiver);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (backCameraPreview != null) backCameraPreview.setImageDrawable(null);
        if (frontCameraPreview != null) frontCameraPreview.setImageDrawable(null);
        if (displayedBackPreviewBitmap != null && !displayedBackPreviewBitmap.isRecycled()) displayedBackPreviewBitmap.recycle();
        if (displayedFrontPreviewBitmap != null && !displayedFrontPreviewBitmap.isRecycled()) displayedFrontPreviewBitmap.recycle();
        displayedBackPreviewBitmap = null;
        displayedFrontPreviewBitmap = null;
        super.onDestroy();
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(background);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(28), dp(22), dp(40));
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView kicker = text("TLS + AES-256-GCM · PHONE → LAPTOP", 11, primary);
        kicker.setTypeface(Typeface.DEFAULT_BOLD);
        kicker.setLetterSpacing(.15f);
        content.addView(kicker);

        TextView title = text("Local Sensor\nCloud", 37, textPrimary);
        title.setTypeface(Typeface.create("sans", Typeface.BOLD));
        title.setLineSpacing(0, .92f);
        content.addView(title, margins(-1, 4, -1, 8));

        TextView summary = text("Stream camera frames, sound level, pressure, and every available sensor to a laptop on the same Wi‑Fi.", 14, textSecondary);
        summary.setLineSpacing(dp(3), 1f);
        content.addView(summary, margins(-1, 0, -1, 24));

        LinearLayout statusCard = card();
        TextView statusKicker = text("STREAM STATUS", 10, textSecondary);
        statusKicker.setTypeface(Typeface.DEFAULT_BOLD);
        statusKicker.setLetterSpacing(.12f);
        statusCard.addView(statusKicker);
        statusLabel = text("Ready to connect", 20, textPrimary);
        statusLabel.setTypeface(Typeface.DEFAULT_BOLD);
        statusCard.addView(statusLabel, margins(-1, 8, -1, 3));
        packetLabel = text("", 12, textSecondary);
        statusCard.addView(packetLabel);
        content.addView(statusCard, margins(-1, 0, -1, 18));

        TextView setupHeading = text("Laptop connection", 18, textPrimary);
        setupHeading.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(setupHeading, margins(-1, 7, -1, 4));
        TextView setupHelp = text("Use the laptop's secure Wi‑Fi address — not localhost. Example: https://192.168.10.104:8787", 12, textSecondary);
        setupHelp.setLineSpacing(dp(2), 1f);
        content.addView(setupHelp, margins(-1, 0, -1, 14));

        endpointInput = field("Laptop server URL", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        deviceNameInput = field("Device name", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        deviceIdInput = field("Device ID", InputType.TYPE_CLASS_TEXT);
        content.addView(endpointInput, margins(-1, 0, -1, 10));
        content.addView(deviceNameInput, margins(-1, 0, -1, 10));
        content.addView(deviceIdInput, margins(-1, 0, -1, 16));

        content.addView(buildSchedulePanel(), margins(-1, 0, -1, 18));

        startButton = button("START STREAMING", true);
        startButton.setOnClickListener(view -> requestStart());
        content.addView(startButton, margins(-1, 0, -1, 10));

        LinearLayout secondaryActions = new LinearLayout(this);
        secondaryActions.setOrientation(LinearLayout.HORIZONTAL);
        secondaryActions.setWeightSum(2f);
        snapshotButton = button("CAPTURE PHOTO", false);
        snapshotButton.setOnClickListener(view -> sendAction(StreamService.ACTION_SNAPSHOT));
        stopButton = button("STOP", false);
        stopButton.setTextColor(Color.rgb(255, 139, 130));
        stopButton.setOnClickListener(view -> sendAction(StreamService.ACTION_STOP));
        secondaryActions.addView(snapshotButton, weightedMargins(0, 0, 5, 0));
        secondaryActions.addView(stopButton, weightedMargins(5, 0, 0, 0));
        content.addView(secondaryActions);

        content.addView(buildCameraPanel(), margins(-1, 22, -1, 0));
        content.addView(buildSensorPanel(), margins(-1, 22, -1, 0));

        TextView privacy = text("Telemetry and photos are encrypted with AES-256-GCM, then sent through certificate-pinned TLS. Microphone audio is never uploaded — only its calculated dBFS level.", 11, textSecondary);
        privacy.setGravity(Gravity.CENTER);
        privacy.setLineSpacing(dp(2), 1f);
        content.addView(privacy, margins(-1, 20, -1, 0));
        return scroll;
    }

    private View buildSchedulePanel() {
        LinearLayout panel = card();
        TextView kicker = text("AUTOMATIC SCHEDULE", 9, primary);
        kicker.setTypeface(Typeface.DEFAULT_BOLD);
        kicker.setLetterSpacing(.14f);
        panel.addView(kicker);

        TextView title = text("Choose when to send", 20, textPrimary);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        panel.addView(title, margins(-1, 5, -1, 5));

        TextView help = text("Sensor values stay live on this screen. Only encrypted network uploads follow these intervals.", 12, textSecondary);
        help.setLineSpacing(dp(2), 1f);
        panel.addView(help, margins(-1, 0, -1, 16));

        telemetryIntervalInput = intervalField("1");
        telemetryUnitButton = unitButton("SECONDS");
        panel.addView(intervalRow("SENSOR DATA", "Send one complete sensor packet", telemetryIntervalInput, telemetryUnitButton));

        photoIntervalInput = intervalField("5");
        photoUnitButton = unitButton("SECONDS");
        panel.addView(intervalRow("AUTOMATIC PHOTOS", "Capture and save front + back photos", photoIntervalInput, photoUnitButton),
                margins(-1, 12, -1, 0));
        return panel;
    }

    private View intervalRow(String label, String description, EditText interval, Button unit) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(13), dp(12), dp(13), dp(12));
        row.setBackground(rounded(Color.rgb(14, 24, 18), outline, 12));

        TextView heading = text(label, 10, textSecondary);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setLetterSpacing(.09f);
        row.addView(heading);
        row.addView(text(description, 11, textSecondary), margins(-1, 4, -1, 9));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setWeightSum(2f);
        controls.addView(interval, weightedMargins(0, 0, 5, 0));
        controls.addView(unit, weightedMargins(5, 0, 0, 0));
        row.addView(controls);
        return row;
    }

    private EditText intervalField(String defaultValue) {
        EditText input = field("Interval", InputType.TYPE_CLASS_NUMBER);
        input.setText(defaultValue);
        input.setGravity(Gravity.CENTER);
        input.setSelectAllOnFocus(true);
        return input;
    }

    private Button unitButton(String initialUnit) {
        Button button = button(initialUnit, false);
        button.setTextColor(primary);
        button.setTag(initialUnit.toLowerCase(Locale.US));
        button.setOnClickListener(view -> {
            boolean seconds = "seconds".equals(button.getTag());
            String next = seconds ? "minutes" : "seconds";
            button.setTag(next);
            button.setText(next.toUpperCase(Locale.US));
        });
        return button;
    }

    private View buildCameraPanel() {
        LinearLayout panel = card();
        LinearLayout titleGroup = new LinearLayout(this);
        titleGroup.setOrientation(LinearLayout.VERTICAL);
        TextView kicker = text("FRONT + BACK", 9, primary);
        kicker.setTypeface(Typeface.DEFAULT_BOLD);
        kicker.setLetterSpacing(.14f);
        titleGroup.addView(kicker);
        TextView title = text("Dual camera preview", 20, textPrimary);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleGroup.addView(title, margins(-1, 4, -1, 0));
        panel.addView(titleGroup);
        panel.addView(buildCameraStage("BACK CAMERA", true), margins(-1, 17, -1, 0));
        panel.addView(buildCameraStage("FRONT CAMERA", false), margins(-1, 14, -1, 0));
        return panel;
    }

    private View buildCameraStage(String label, boolean back) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView cameraLabel = text(label, 10, textSecondary);
        cameraLabel.setTypeface(Typeface.DEFAULT_BOLD);
        cameraLabel.setLetterSpacing(.08f);
        heading.addView(cameraLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView state = text("WAITING", 9, textSecondary);
        state.setTypeface(Typeface.DEFAULT_BOLD);
        state.setLetterSpacing(.06f);
        state.setPadding(dp(8), dp(4), dp(8), dp(4));
        state.setBackground(rounded(Color.rgb(24, 38, 29), outline, 14));
        heading.addView(state);
        if (back) backCameraPreviewState = state; else frontCameraPreviewState = state;
        group.addView(heading);

        FrameLayout stage = new FrameLayout(this);
        stage.setBackground(rounded(Color.BLACK, outline, 13));
        stage.setClipToOutline(true);
        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackgroundColor(Color.BLACK);
        stage.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (back) backCameraPreview = preview; else frontCameraPreview = preview;

        TextView empty = text("Waiting for " + label.toLowerCase(Locale.US), 12, Color.rgb(105, 119, 110));
        empty.setGravity(Gravity.CENTER);
        empty.setTag(back ? "back-camera-empty" : "front-camera-empty");
        stage.addView(empty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams stageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(190));
        stageParams.setMargins(0, dp(9), 0, 0);
        group.addView(stage, stageParams);
        return group;
    }

    private void updateCameraPreview(String camera, byte[] jpeg) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        boolean front = "front".equals(camera);
        ImageView preview = front ? frontCameraPreview : backCameraPreview;
        TextView state = front ? frontCameraPreviewState : backCameraPreviewState;
        if (bitmap == null || preview == null) return;
        View empty = ((ViewGroup) preview.getParent()).findViewWithTag(front ? "front-camera-empty" : "back-camera-empty");
        if (empty != null) empty.setVisibility(View.GONE);
        Bitmap previous = front ? displayedFrontPreviewBitmap : displayedBackPreviewBitmap;
        if (front) displayedFrontPreviewBitmap = bitmap; else displayedBackPreviewBitmap = bitmap;
        preview.setImageBitmap(bitmap);
        state.setText("LIVE");
        state.setTextColor(primary);
        if (previous != null && previous != bitmap && !previous.isRecycled()) previous.recycle();
    }

    private View buildSensorPanel() {
        LinearLayout panel = card();

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleGroup = new LinearLayout(this);
        titleGroup.setOrientation(LinearLayout.VERTICAL);
        TextView kicker = text("LIVE HARDWARE", 9, primary);
        kicker.setTypeface(Typeface.DEFAULT_BOLD);
        kicker.setLetterSpacing(.14f);
        titleGroup.addView(kicker);
        TextView title = text("Sensor measurements", 20, textPrimary);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleGroup.addView(title, margins(-1, 4, -1, 0));
        heading.addView(titleGroup, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        sensorCountLabel = text("0 SENSORS", 10, primary);
        sensorCountLabel.setTypeface(Typeface.DEFAULT_BOLD);
        sensorCountLabel.setLetterSpacing(.06f);
        sensorCountLabel.setPadding(dp(10), dp(6), dp(10), dp(6));
        sensorCountLabel.setBackground(rounded(Color.rgb(18, 48, 29), Color.rgb(50, 101, 66), 18));
        heading.addView(sensorCountLabel);
        panel.addView(heading);

        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        metrics.setWeightSum(3f);
        noiseValueLabel = text("—", 17, textPrimary);
        pressureValueLabel = text("—", 17, textPrimary);
        batteryValueLabel = text("—", 17, textPrimary);
        metrics.addView(metricColumn("NOISE", noiseValueLabel), metricParams(0, 4));
        metrics.addView(metricColumn("PRESSURE", pressureValueLabel), metricParams(4, 4));
        metrics.addView(metricColumn("BATTERY", batteryValueLabel), metricParams(4, 0));
        panel.addView(metrics, margins(-1, 18, -1, 18));

        sensorEmptyLabel = text("Waiting for sensor readings…", 13, textSecondary);
        sensorEmptyLabel.setGravity(Gravity.CENTER);
        sensorEmptyLabel.setPadding(dp(14), dp(26), dp(14), dp(26));
        sensorEmptyLabel.setBackground(rounded(Color.rgb(14, 23, 18), outline, 13));
        panel.addView(sensorEmptyLabel);

        sensorBlocksContainer = new LinearLayout(this);
        sensorBlocksContainer.setOrientation(LinearLayout.VERTICAL);
        panel.addView(sensorBlocksContainer);
        return panel;
    }

    private void requestStart() {
        String endpoint = endpointInput.getText().toString().trim();
        if (!endpoint.startsWith("https://")) {
            endpointInput.setError("Secure connections must begin with https://");
            return;
        }
        if (deviceIdInput.getText().toString().trim().isEmpty()) {
            deviceIdInput.setError("Device ID is required");
            return;
        }
        if (intervalMillis(telemetryIntervalInput, telemetryUnitButton) < 0
                || intervalMillis(photoIntervalInput, photoUnitButton) < 0) return;
        savePreferences();
        if (!hasCorePermissions()) {
            pendingStart = true;
            requestPermissions(missingPermissions(), PERMISSION_REQUEST);
            return;
        }
        startStreamingService();
    }

    private boolean hasCorePermissions() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private String[] missingPermissions() {
        List<String> permissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.CAMERA);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        if (checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.BODY_SENSORS);
        if (Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.ACTIVITY_RECOGNITION);
        return permissions.toArray(new String[0]);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST && pendingStart) {
            pendingStart = false;
            if (hasCorePermissions()) {
                startStreamingService();
            } else {
                Toast.makeText(this, "Camera and microphone permissions are needed to stream.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startStreamingService() {
        long telemetryIntervalMs = intervalMillis(telemetryIntervalInput, telemetryUnitButton);
        long photoIntervalMs = intervalMillis(photoIntervalInput, photoUnitButton);
        if (telemetryIntervalMs < 0 || photoIntervalMs < 0) return;
        Intent intent = new Intent(this, StreamService.class)
                .setAction(StreamService.ACTION_START)
                .putExtra(StreamService.EXTRA_ENDPOINT, endpointInput.getText().toString().trim())
                .putExtra(StreamService.EXTRA_DEVICE_NAME, deviceNameInput.getText().toString().trim())
                .putExtra(StreamService.EXTRA_DEVICE_ID, deviceIdInput.getText().toString().trim())
                .putExtra(StreamService.EXTRA_TELEMETRY_INTERVAL_MS, telemetryIntervalMs)
                .putExtra(StreamService.EXTRA_PHOTO_INTERVAL_MS, photoIntervalMs);
        startForegroundService(intent);
        updateState(true, "Starting sensors…", "Data " + formatInterval(telemetryIntervalMs)
                + " · photos " + formatInterval(photoIntervalMs));
    }

    private long intervalMillis(EditText input, Button unitButton) {
        String valueText = input.getText().toString().trim();
        long value;
        try {
            value = Long.parseLong(valueText);
        } catch (NumberFormatException error) {
            input.setError("Enter a whole number");
            return -1;
        }
        boolean minutes = "minutes".equals(unitButton.getTag());
        long maximum = minutes ? 1440 : 86400;
        if (value < 1 || value > maximum) {
            input.setError(minutes ? "Use 1–1440 minutes" : "Use 1–86400 seconds");
            return -1;
        }
        return TimeUnit.MILLISECONDS.convert(value, minutes ? TimeUnit.MINUTES : TimeUnit.SECONDS);
    }

    private String formatInterval(long intervalMs) {
        if (intervalMs >= TimeUnit.MINUTES.toMillis(1) && intervalMs % TimeUnit.MINUTES.toMillis(1) == 0) {
            long minutes = TimeUnit.MILLISECONDS.toMinutes(intervalMs);
            return "every " + minutes + (minutes == 1 ? " minute" : " minutes");
        }
        long seconds = TimeUnit.MILLISECONDS.toSeconds(intervalMs);
        return "every " + seconds + (seconds == 1 ? " second" : " seconds");
    }

    private void sendAction(String action) {
        Intent intent = new Intent(this, StreamService.class).setAction(action);
        startService(intent);
    }

    private void loadPreferences() {
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        String defaultId = preferences.getString("generatedDeviceId", "");
        if (defaultId.isEmpty()) {
            defaultId = "android-" + UUID.randomUUID().toString().substring(0, 12);
            preferences.edit().putString("generatedDeviceId", defaultId).apply();
        }
        String savedEndpoint = preferences.getString("endpoint", "https://192.168.10.104:8787");
        if (savedEndpoint.startsWith("http://")) savedEndpoint = "https://" + savedEndpoint.substring("http://".length());
        endpointInput.setText(savedEndpoint);
        deviceNameInput.setText(preferences.getString("deviceName", Build.MANUFACTURER + " " + Build.MODEL));
        deviceIdInput.setText(preferences.getString("deviceId", defaultId.toLowerCase(Locale.US).replace(' ', '-')));
        telemetryIntervalInput.setText(preferences.getString("telemetryInterval", "1"));
        photoIntervalInput.setText(preferences.getString("photoInterval", "5"));
        setUnit(telemetryUnitButton, preferences.getString("telemetryUnit", "seconds"));
        setUnit(photoUnitButton, preferences.getString("photoUnit", "seconds"));
    }

    private void setUnit(Button button, String unit) {
        String safeUnit = "minutes".equals(unit) ? "minutes" : "seconds";
        button.setTag(safeUnit);
        button.setText(safeUnit.toUpperCase(Locale.US));
    }

    private void savePreferences() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("endpoint", endpointInput.getText().toString().trim())
                .putString("deviceName", deviceNameInput.getText().toString().trim())
                .putString("deviceId", deviceIdInput.getText().toString().trim())
                .putString("telemetryInterval", telemetryIntervalInput.getText().toString().trim())
                .putString("photoInterval", photoIntervalInput.getText().toString().trim())
                .putString("telemetryUnit", String.valueOf(telemetryUnitButton.getTag()))
                .putString("photoUnit", String.valueOf(photoUnitButton.getTag()))
                .apply();
    }

    private void updateState(boolean running, String message, String detail) {
        if (statusLabel == null) return;
        statusLabel.setText(message == null || message.trim().isEmpty() ? (running ? "Streaming" : "Stopped") : message);
        packetLabel.setText(detail == null ? "" : detail);
        startButton.setEnabled(!running);
        startButton.setAlpha(running ? .45f : 1f);
        stopButton.setEnabled(running);
        snapshotButton.setEnabled(running);
        stopButton.setAlpha(running ? 1f : .4f);
        snapshotButton.setAlpha(running ? 1f : .4f);
        telemetryIntervalInput.setEnabled(!running);
        photoIntervalInput.setEnabled(!running);
        telemetryUnitButton.setEnabled(!running);
        photoUnitButton.setEnabled(!running);
        telemetryIntervalInput.setAlpha(running ? .55f : 1f);
        photoIntervalInput.setAlpha(running ? .55f : 1f);
        telemetryUnitButton.setAlpha(running ? .55f : 1f);
        photoUnitButton.setAlpha(running ? .55f : 1f);
        if (!running) {
            if (backCameraPreviewState != null) {
                backCameraPreviewState.setText("STOPPED");
                backCameraPreviewState.setTextColor(textSecondary);
            }
            if (frontCameraPreviewState != null) {
                frontCameraPreviewState.setText("STOPPED");
                frontCameraPreviewState.setTextColor(textSecondary);
            }
        }
    }

    private void updateTelemetry(String telemetryJson) {
        try {
            JSONObject telemetry = new JSONObject(telemetryJson);
            JSONObject noise = telemetry.optJSONObject("noise");
            JSONObject readings = telemetry.optJSONObject("readings");
            JSONObject device = telemetry.optJSONObject("device");
            JSONObject stream = telemetry.optJSONObject("stream");
            JSONArray sensors = telemetry.optJSONArray("sensors");

            noiseValueLabel.setText(formatMeasurement(noise == null ? Double.NaN : noise.optDouble("dbfs", Double.NaN), 1, " dBFS"));
            pressureValueLabel.setText(formatMeasurement(readings == null ? Double.NaN : readings.optDouble("pressureHpa", Double.NaN), 1, " hPa"));
            double battery = device == null ? Double.NaN : device.optDouble("batteryPercent", Double.NaN);
            batteryValueLabel.setText(formatMeasurement(battery < 0 ? Double.NaN : battery, 0, "%"));
            if (stream != null) {
                JSONObject cameras = stream.optJSONObject("cameras");
                if (cameras != null) {
                    updateCameraState(backCameraPreviewState, cameras.optJSONObject("back"));
                    updateCameraState(frontCameraPreviewState, cameras.optJSONObject("front"));
                } else {
                    updateCameraState(backCameraPreviewState, stream);
                }
            }

            int count = sensors == null ? 0 : sensors.length();
            Set<String> activeKeys = new HashSet<>();
            for (int sensorIndex = 0; sensorIndex < count; sensorIndex++) {
                JSONObject sensor = sensors.optJSONObject(sensorIndex);
                if (sensor == null) continue;
                String key = sensor.optInt("type") + "|" + sensor.optString("name") + "|" + sensor.optString("vendor");
                activeKeys.add(key);
                SensorBlock block = sensorBlocks.get(key);
                if (block == null) {
                    block = new SensorBlock(sensor);
                    sensorBlocks.put(key, block);
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    params.setMargins(0, 0, 0, dp(10));
                    sensorBlocksContainer.addView(block.root, params);
                }
                block.update(sensor);
            }

            Iterator<Map.Entry<String, SensorBlock>> iterator = sensorBlocks.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, SensorBlock> entry = iterator.next();
                if (!activeKeys.contains(entry.getKey())) {
                    sensorBlocksContainer.removeView(entry.getValue().root);
                    iterator.remove();
                }
            }

            int displayedCount = sensorBlocks.size();
            sensorCountLabel.setText(displayedCount + (displayedCount == 1 ? " SENSOR" : " SENSORS"));
            sensorEmptyLabel.setVisibility(displayedCount == 0 ? View.VISIBLE : View.GONE);
        } catch (JSONException error) {
            sensorEmptyLabel.setText("Could not display the latest sensor packet.");
            sensorEmptyLabel.setVisibility(View.VISIBLE);
        }
    }

    private String formatMeasurement(double value, int decimals, String unit) {
        if (!Double.isFinite(value) || value < -1000) return "—";
        return String.format(Locale.US, "% ." + decimals + "f%s", value, unit).trim();
    }

    private void updateCameraState(TextView label, JSONObject camera) {
        if (label == null || camera == null) return;
        long captured = camera.optLong("framesCaptured", 0);
        String state = camera.optString("state", camera.optString("cameraState", "waiting"));
        label.setText(captured > 0 ? "LIVE" : state.replace('-', ' ').toUpperCase(Locale.US));
        label.setTextColor(captured > 0 ? primary : textSecondary);
    }

    private String formatNumber(double value) {
        double absolute = Math.abs(value);
        int decimals = absolute >= 100 ? 1 : absolute >= 10 ? 2 : 3;
        return String.format(Locale.US, "% ." + decimals + "f", value).trim();
    }

    private LinearLayout metricColumn(String label, TextView value) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(10), dp(11), dp(10), dp(11));
        column.setBackground(rounded(Color.rgb(14, 24, 18), outline, 11));
        TextView heading = text(label, 9, textSecondary);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setLetterSpacing(.1f);
        column.addView(heading);
        value.setTypeface(Typeface.DEFAULT_BOLD);
        column.addView(value, margins(-1, 5, -1, 0));
        return column;
    }

    private LinearLayout.LayoutParams metricParams(int left, int right) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(left), 0, dp(right), 0);
        return params;
    }

    private int accentForSensor(int type) {
        return switch (type) {
            case 5, 6, 12, 13 -> Color.rgb(118, 230, 156); // environmental
            case 1, 4, 9, 10, 16, 35 -> Color.rgb(105, 179, 255); // motion
            case 2, 3, 11, 14, 15, 20, 27 -> Color.rgb(194, 155, 255); // orientation
            case 8, 17, 18, 19 -> Color.rgb(246, 190, 98); // proximity and activity
            default -> Color.rgb(126, 207, 190);
        };
    }

    private String friendlySensorType(String stringType, int type) {
        String value = stringType == null ? "" : stringType;
        if (value.startsWith("android.sensor.")) value = value.substring("android.sensor.".length());
        if (value.isEmpty()) value = "sensor type " + type;
        return value.replace('_', ' ');
    }

    private String formatSensorValues(JSONArray values) {
        if (values == null || values.length() == 0) return "—";
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < values.length(); index++) {
            if (index > 0) output.append("  ·  ");
            double value = values.optDouble(index, Double.NaN);
            output.append(Double.isFinite(value) ? formatNumber(value) : "—");
        }
        return output.toString();
    }

    private final class SensorBlock {
        final LinearLayout root;
        final View accent;
        final TextView title;
        final TextView subtitle;
        final TextView values;
        final TextView unit;
        final TextView accuracy;

        SensorBlock(JSONObject sensor) {
            int sensorAccent = accentForSensor(sensor.optInt("type"));
            root = new LinearLayout(MainActivity.this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(14), dp(13), dp(14), dp(13));
            root.setBackground(rounded(Color.rgb(14, 24, 18), Color.rgb(42, 61, 49), 13));

            LinearLayout header = new LinearLayout(MainActivity.this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            accent = new View(MainActivity.this);
            accent.setBackground(rounded(sensorAccent, sensorAccent, 8));
            LinearLayout.LayoutParams accentParams = new LinearLayout.LayoutParams(dp(8), dp(34));
            accentParams.setMargins(0, 0, dp(11), 0);
            header.addView(accent, accentParams);

            LinearLayout names = new LinearLayout(MainActivity.this);
            names.setOrientation(LinearLayout.VERTICAL);
            title = text("Sensor", 14, textPrimary);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            names.addView(title);
            subtitle = text("Hardware sensor", 10, textSecondary);
            subtitle.setSingleLine(false);
            names.addView(subtitle, margins(-1, 3, -1, 0));
            header.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            accuracy = text("UNKNOWN", 9, textSecondary);
            accuracy.setTypeface(Typeface.DEFAULT_BOLD);
            accuracy.setLetterSpacing(.05f);
            accuracy.setPadding(dp(8), dp(5), dp(8), dp(5));
            accuracy.setBackground(rounded(Color.rgb(24, 38, 29), outline, 16));
            header.addView(accuracy);
            root.addView(header);

            values = text("—", 17, sensorAccent);
            values.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            values.setTextIsSelectable(true);
            values.setLineSpacing(dp(2), 1f);
            root.addView(values, margins(-1, 14, -1, 0));

            unit = text("raw SI / platform-defined", 10, textSecondary);
            root.addView(unit, margins(-1, 5, -1, 0));
            update(sensor);
        }

        void update(JSONObject sensor) {
            int type = sensor.optInt("type");
            title.setText(sensor.optString("name", "Sensor"));
            String typeName = friendlySensorType(sensor.optString("stringType"), type);
            String vendor = sensor.optString("vendor", "Unknown vendor");
            subtitle.setText(typeName + "  ·  " + vendor);
            values.setText(formatSensorValues(sensor.optJSONArray("values")));
            unit.setText(sensor.optString("unit", "raw SI / platform-defined"));
            String accuracyValue = sensor.optString("accuracyLabel", "unknown").toUpperCase(Locale.US);
            accuracy.setText(accuracyValue);
        }
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(18), dp(17), dp(18), dp(17));
        layout.setBackground(rounded(surface, outline, 17));
        return layout;
    }

    private EditText field(String hint, int inputType) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setHintTextColor(Color.rgb(105, 122, 111));
        field.setTextColor(textPrimary);
        field.setTextSize(14);
        field.setSingleLine(true);
        field.setInputType(inputType);
        field.setPadding(dp(15), 0, dp(15), 0);
        field.setBackground(rounded(surfaceLight, outline, 11));
        field.setSelectAllOnFocus(false);
        field.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        return field;
    }

    private Button button(String label, boolean filled) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setLetterSpacing(.09f);
        button.setTextColor(filled ? Color.rgb(6, 33, 15) : textPrimary);
        button.setBackground(rounded(filled ? primary : surfaceLight, filled ? primary : outline, 11));
        button.setStateListAnimator(null);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        return button;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams margins(int width, int top, int unusedWidth, int bottom) {
        int actualWidth = width < 0 ? ViewGroup.LayoutParams.MATCH_PARENT : dp(width);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(actualWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(top), 0, dp(bottom));
        return params;
    }

    private LinearLayout.LayoutParams weightedMargins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(54), 1f);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
