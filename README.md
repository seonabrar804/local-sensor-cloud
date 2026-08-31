# Local Sensor Cloud

Local Sensor Cloud turns an Android phone into a wireless sensing and camera device. The phone collects its available sensor measurements, noise level, battery and device information, and front/back camera images. It displays the live values on the phone and sends encrypted uploads over the local Wi-Fi network to a laptop.

The laptop acts as the cloud server. It decrypts and stores the uploads, then provides a browser dashboard with live sensor readings and camera feeds. No external cloud service or upload token is required.

## What the app does

- Discovers the sensors available on the phone and creates a live card for each one automatically.
- Displays noise level, pressure, battery information, camera status, upload counts, and errors on the phone.
- Captures the front and back cameras. Supported phones can use both concurrently; other phones alternate between them automatically.
- Automatically uploads sensor data and photos at separate user-selected intervals in seconds or minutes.
- Provides a manual photo-capture button for an immediate saved image.
- Keeps sensor values updating locally even when the laptop cannot be reached.
- Shows connected devices, measurements, and the latest camera images on the laptop dashboard.
- Saves telemetry as JSONL and camera images as JPEG files on the laptop.

## Security

The app uses three security layers, and each one protects a different part of the data path:

- **Wi-Fi layer — implemented by Android and the router or hotspot:** WPA protects radio traffic between the phone and the Wi-Fi access point. The app does not create this encryption; the Wi-Fi system provides it when the network is secured.
- **Connection layer — implemented by TLS in the Android app and laptop server:** TLS encrypts everything travelling between the app and laptop. Certificate pinning also lets the app confirm that it connected to the correct laptop.
- **Data layer — implemented by AES-GCM inside the Android app and laptop server:** The app encrypts each sensor upload and camera image before sending it. The laptop checks and decrypts the data after receiving it, so modified data is rejected.

In simple terms, AES-GCM protects the actual sensor data and images, TLS protects their complete phone-to-laptop journey, and WPA protects the wireless part of that journey.

The generated TLS private key and AES key stay outside Git. The matching public certificate and AES key are copied into the Android app during local setup, so the APK must be rebuilt whenever the keys or laptop certificate change.

## Start from scratch

### 1. Install the required tools

Install these on the laptop:

- Git
- Node.js and npm
- Android Studio with the Android SDK
- ADB, which is included with the Android SDK Platform Tools
- OpenSSL

On macOS, Android Studio provides a suitable Java runtime for the Android build. If `java` is not available in Terminal, the build command below points Gradle to Android Studio's bundled runtime.

### 2. Download the project

Open Terminal and run:

```bash
git clone https://github.com/seonabrar804/local-sensor-cloud.git
cd local-sensor-cloud
chmod +x setup-security.command start-server.command android/gradlew
```

### 3. Find the laptop's local address

The phone and laptop must be on the same Wi-Fi network.

On macOS:

```bash
ipconfig getifaddr en0
```

If that prints nothing, open **System Settings → Wi-Fi → Details** and copy the IP address. On Windows, run `ipconfig` and find the Wi-Fi adapter's IPv4 address. On Linux, run `hostname -I`.

The address normally looks similar to `192.168.1.20`. It can change when the laptop joins another network.

### 4. Generate security keys for this laptop

On macOS or Linux, run:

```bash
./setup-security.command
```

The script detects the laptop address, creates the TLS certificate and private key, creates the AES key, and copies the required certificate and key material into the Android project.

If automatic address detection fails, supply the address explicitly:

```bash
SENSOR_CLOUD_IP=192.168.1.20 ./setup-security.command
```

Replace `192.168.1.20` with the laptop's actual address. Do not share or commit files from `server/keys/`, the TLS private key, or the generated Android AES-key resource.

### 5. Build the Android app

Run:

```bash
cd android
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew assembleDebug
cd ..
```

The generated APK is:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

If Java is already configured, `./gradlew assembleDebug` is sufficient. You can also open the `android` directory in Android Studio and build the app there.

### 6. Install the app on the phone

Enable **Developer options** and **USB debugging** on the Android phone, connect it by USB, accept the authorization message, and run:

```bash
adb devices
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Alternatively, copy the APK to the phone, open it, and allow installation from that file source when Android asks.

### 7. Start the laptop server

From the project directory, run:

```bash
./start-server.command
```

Leave that Terminal window open. On the laptop, visit:

```text
https://localhost:8787
```

The browser may warn about a self-signed certificate because it was created locally rather than by a public certificate authority. Proceed only when you are using the certificate generated by this project.

If the firewall asks whether Node.js may accept incoming connections, allow it on the local network.

### 8. Connect the Android app

1. Confirm that the phone and laptop are connected to the same Wi-Fi network.
2. Open **Local Sensor Cloud** on the phone and grant the requested camera, microphone, and notification permissions.
3. Enter the laptop URL using the address from step 3, for example `https://192.168.1.20:8787`.
4. Select sensor-data and automatic-photo intervals. Each interval can use seconds or minutes.
5. Tap **Start streaming**.
6. Open the laptop dashboard and wait for the first scheduled upload.

The app obtains the phone model, Android information, battery status, sensors, and camera capabilities directly from the phone. Nothing needs to be entered manually except the laptop address and upload intervals.

## Using the app

- **Start streaming** opens the available sensors, microphone, and cameras and starts scheduled encrypted uploads.
- **Capture photo** saves the next camera image immediately on the laptop.
- **Stop** closes the active sensors, microphone, cameras, and background service.
- Sensor cards appear automatically according to the hardware available on the phone.
- A missing pressure, temperature, humidity, or other reading usually means the phone does not contain that physical sensor.

The noise value is a relative digital microphone level. It is not a calibrated sound-pressure measurement unless the phone is calibrated against a known meter.

## Stored data

The laptop creates the following directories while the server runs:

```text
server/data/
├── telemetry/   daily JSONL sensor logs
├── frames/      latest image from each camera
└── photos/      timestamped automatic and manual photos
```

Each telemetry line is standalone JSON and can later be loaded into Python, a spreadsheet, a database, or another analysis tool.

## Confirming encryption with Wireshark

Start a Wireshark capture on the laptop's active Wi-Fi interface and use this display filter:

```text
tcp.port == 8787
```

Wireshark should identify the connection as TLS and show the uploads as encrypted application data. It can still display addresses, ports, timing, and packet sizes, but it should not display readable sensor values or JPEG contents. WPA encryption is normally removed by the Wi-Fi hardware before packets reach a normal laptop capture, and the inner AES-GCM payload remains hidden inside TLS.

## Troubleshooting

### The app cannot connect

- Confirm that the server is still running and the laptop URL is correct.
- Confirm that both devices are on the same network.
- Avoid guest Wi-Fi networks that isolate connected devices.
- Allow incoming Node.js connections through the laptop firewall.
- Open the dashboard locally to confirm the server started successfully.

### Uploads fail after the laptop address changes

The address is included in the pinned TLS certificate. Generate new security material and rebuild/reinstall the app:

```bash
SENSOR_CLOUD_IP=NEW_LAPTOP_IP ./setup-security.command --force
cd android
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew assembleDebug
```

Then reinstall the new APK and use the new address in the app.

### Camera images are dark or unavailable

- Grant camera permission and stop/start streaming again.
- Keep the lenses uncovered and allow a moment for exposure adjustment.
- Some phones cannot open both cameras simultaneously; the app alternates between them automatically.

### A sensor card is missing

Android can only report hardware physically present in the phone. A missing sensor is expected and does not indicate an upload failure.

## Test commands

Test the laptop receiver:

```bash
cd server
npm test
```

Build the Android app again:

```bash
cd android
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew assembleDebug
```

## Important security rules

- Do not commit the TLS private key, AES key, generated APK, sensor recordings, photos, or packet captures.
- Do not expose the laptop server port directly to the public internet.
- Regenerating the certificate or AES key requires rebuilding and reinstalling the Android app.
- The app does not use an upload token. Keep the server on a trusted local network.
- A shared AES key embedded in an APK can be recovered by someone who obtains the APK. For deployment to multiple untrusted phones, use unique per-device keys or mutual TLS authentication.
