# Local Sensor Cloud

This project turns an Android phone into a local sensor and camera node, with a laptop acting as the private cloud.

The Android app streams:

- live camera JPEG frames at up to 2 fps, displayed as MJPEG in the dashboard;
- manually captured full frames saved as photos;
- microphone noise level as relative dBFS (raw audio is never uploaded);
- pressure in hPa when the phone has a barometer;
- ambient temperature, light, humidity, acceleration, rotation, magnetic field, proximity, and every other available unprotected Android sensor;
- phone model, Android version, battery, camera resolution, frame count, and upload errors.

The app also shows a live on-phone measurements panel. Noise, pressure, and battery use compact summary tiles. Every active hardware sensor gets its own color-coded card with live values, unit, vendor/type, and accuracy. Cards are added and removed automatically as sensors become active or inactive, and values refresh approximately once per second even while the laptop is temporarily unreachable.

Version 1.2 includes an on-phone camera preview. A continuously drained YUV preview stream drives camera auto-exposure and autofocus, while separate JPEG captures are shown in the app and sent to the laptop; this prevents dark frames on devices that do not meter exposure during JPEG-only capture.

Version 1.3 captures both front and back cameras. Phones advertising concurrent-camera support stream them simultaneously. Other phones automatically alternate cameras approximately every 6.5 seconds, because Android hardware permits only one open camera; the latest image from each side remains visible in the app and dashboard. Each feed is stored independently using `-front` or `-back` filenames.

Version 1.6 adds an automatic upload schedule. Sensor data and front/back photos have independent interval controls: enter a whole number and tap the unit button to switch between seconds and minutes. Sensor cards continue refreshing locally about once per second even when network uploads are much less frequent. Automatic photos are saved in `server/data/photos/`; the manual **Capture photo** button remains available for immediate captures. Stop streaming before changing a schedule, then start again to apply it.

Version 1.7 adds authenticated application-level encryption. Before upload, the Android app encrypts every telemetry packet and JPEG with AES-256-GCM using a fresh random 96-bit nonce. The laptop authenticates and decrypts the payload before processing it, and rejects plaintext, modified ciphertext, or an incorrect key. This sits inside the existing certificate-pinned TLS connection.

The laptop receiver has no npm dependencies. It uses Node.js built-ins, stores telemetry as daily JSONL files, and serves a real-time browser dashboard.

## 1. Start the laptop cloud

Requirements: Node.js 20 or newer. The supplied laptop already has a suitable version.

After cloning the repository for the first time, generate laptop-specific TLS and AES keys, then rebuild the Android APK:

```bash
./setup-security.command
cd android
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew assembleDebug
```

Private keys, captured sensor data, packet captures, build output, and the generated APK are intentionally excluded from Git. Never commit them. The public certificate is safe to commit, but `setup-security.command` replaces it with one matching the new laptop and copies it into the Android project.

In Terminal:

```bash
cd server
npm start
```

Leave the terminal open, then open [https://localhost:8787](https://localhost:8787) on the laptop. The browser may display a warning because this is a private self-signed certificate; verify the SHA-256 fingerprint below before proceeding.

Find the laptop's local Wi-Fi address:

- macOS: System Settings → Wi-Fi → Details → IP Address, or run `ipconfig getifaddr en0`
- Windows: run `ipconfig` and use the Wi-Fi adapter's IPv4 Address
- Linux: run `hostname -I`

For this laptop, enter `https://192.168.10.104:8787` on the phone. The app pins the laptop certificate, so a network attacker cannot substitute another server even if the Wi-Fi address later changes.

## 2. Install the Android app

The ready-to-install APK is `LocalSensorCloud-debug.apk` in this folder. Either copy it to the phone and open it, or enable USB debugging and run:

```bash
adb install -r LocalSensorCloud-debug.apk
```

Android may ask you to allow installation from the file-sharing app or browser used to open the APK. The complete Android Studio project is in `android/`.

## 3. Connect the phone

1. Put the phone and laptop on the same Wi-Fi network.
2. Open **Local Sensor Cloud** on the phone.
3. Replace the example server URL with the laptop URL from step 1.
4. Choose separate sensor-data and automatic-photo intervals. Tap each unit button to select seconds or minutes.
5. Tap **Start streaming** and allow the requested permissions.
6. Return to the laptop dashboard. The phone appears after the first selected data-upload interval.

Use **Capture photo** in the app to preserve the next camera frame. **Stop** closes the camera, microphone, sensors, and background service.

## Storage

Runtime data is created below `server/data/`:

```text
server/data/
├── telemetry/   daily YYYY-MM-DD.jsonl logs
├── frames/      latest JPEG for each device
└── photos/      timestamped, manually captured JPEGs
```

Every telemetry line is standalone JSON and can be imported into Python, pandas, a database, or another analytics tool later.

## Configuration

The server reads these optional environment variables:

| Variable | Default | Purpose |
|---|---|---|
| `SENSOR_CLOUD_HOST` | `0.0.0.0` | Network interface to listen on |
| `SENSOR_CLOUD_PORT` | `8787` | HTTPS port |
| `SENSOR_CLOUD_DATA` | `server/data` | Storage directory |
| `SENSOR_CLOUD_CERT` | `server/tls/server-cert.pem` | TLS certificate path |
| `SENSOR_CLOUD_KEY` | `server/tls/server-key.pem` | TLS private-key path |
| `SENSOR_CLOUD_APP_KEY` | `server/keys/application-aes.key` | 32-byte AES-256-GCM application key path |

The upload API is intentionally simple:

- `POST /api/telemetry` — JSON telemetry
- `POST /api/frame?deviceId=...&camera=front|back` — JPEG frame
- `GET /api/devices`, `/api/latest`, and `/api/history` — dashboard data
- `GET /api/video.mjpeg` — live browser camera stream
- `GET /events` — Server-Sent Events for live telemetry

## Build and test

Laptop receiver:

```bash
cd server
npm test
```

Android app (macOS, with Android Studio installed):

```bash
cd android
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew assembleDebug
```

The generated APK is `android/app/build/outputs/apk/debug/app-debug.apk`.

## Practical notes

- Many phones do not contain a barometer or ambient-temperature sensor. Missing hardware is shown as `—`; it is not an app error.
- dBFS is a relative digital sound level. Calibrated dBA requires calibration against a known sound-level meter and phone-specific microphone compensation.
- The foreground notification is required by Android to keep camera and microphone capture active when the screen is off.
- Some guest Wi-Fi networks isolate devices from each other. Use a normal home/office network or the laptop's hotspot if the phone cannot reach the dashboard.
- Allow incoming Node.js connections if the laptop firewall asks.
- Version 1.5 requires HTTPS. TLS 1.2 or 1.3 encrypts and authenticates every telemetry, camera, dashboard, SSE, and MJPEG connection. The Android app trusts only the bundled laptop certificate (SHA-256 `09:43:0D:CF:CF:96:8E:80:C6:AB:E5:9E:E9:FC:8D:F9:8F:A3:1A:E5:22:4C:65:A9:59:6B:99:30:8A:61:5F:57`). Data is raw only inside the phone and laptop endpoints; network observers see encrypted TLS records.
- Version 1.7 additionally encrypts upload bodies with AES-256-GCM. The binary envelope is `LSC1 || 12-byte nonce || ciphertext || 16-byte authentication tag`; the complete request path is authenticated as additional data. The server refuses unencrypted or tampered uploads.
- The laptop private key is `server/tls/server-key.pem`, protected with owner-only filesystem permissions. Never copy or publish it. Replacing the certificate requires rebuilding the Android APK with the new public certificate so the pin remains valid.
- The application key is `server/keys/application-aes.key`, also protected with owner-only permissions. A matching copy is bundled in this APK. Replacing it requires rebuilding the APK. Because an APK can be reverse-engineered, this shared key adds payload confidentiality and integrity but is not a strong identity for an individual phone; use mutual TLS for per-phone authorization.
- No token is required, so TLS protects confidentiality, integrity, and laptop identity but does not authenticate which LAN device is uploading. Keep the server on a trusted local network and do not expose port 8787 to the public internet.
