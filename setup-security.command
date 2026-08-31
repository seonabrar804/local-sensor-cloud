#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIRECTORY="$(cd -- "$(dirname -- "$0")" && pwd)"
FORCE="${1:-}"
TLS_KEY="$PROJECT_DIRECTORY/server/tls/server-key.pem"
TLS_CERT="$PROJECT_DIRECTORY/server/tls/server-cert.pem"
APP_KEY="$PROJECT_DIRECTORY/server/keys/application-aes.key"
ANDROID_TLS_CERT="$PROJECT_DIRECTORY/android/app/src/main/res/raw/server_certificate.pem"
ANDROID_APP_KEY="$PROJECT_DIRECTORY/android/app/src/main/res/raw/application_aes_key.bin"

if [[ "$FORCE" != "--force" ]] && { [[ -e "$TLS_KEY" ]] || [[ -e "$APP_KEY" ]]; }; then
  echo "Security keys already exist. Nothing was changed."
  echo "Use ./setup-security.command --force only when intentionally rotating all keys and rebuilding the APK."
  exit 1
fi

LAPTOP_IP="${SENSOR_CLOUD_IP:-}"
if [[ -z "$LAPTOP_IP" ]] && command -v ipconfig >/dev/null 2>&1; then
  LAPTOP_IP="$(ipconfig getifaddr en0 2>/dev/null || true)"
fi
if [[ -z "$LAPTOP_IP" ]] && command -v hostname >/dev/null 2>&1; then
  LAPTOP_IP="$(hostname -I 2>/dev/null | awk '{print $1}' || true)"
fi
if [[ -z "$LAPTOP_IP" ]]; then
  echo "Could not detect the laptop IP. Run again with SENSOR_CLOUD_IP set, for example:"
  echo "SENSOR_CLOUD_IP=192.168.1.20 ./setup-security.command"
  exit 1
fi

mkdir -p "$PROJECT_DIRECTORY/server/tls" "$PROJECT_DIRECTORY/server/keys" \
  "$PROJECT_DIRECTORY/android/app/src/main/res/raw"

openssl req -x509 -newkey rsa:3072 -sha256 -days 825 -nodes \
  -subj "/CN=Local Sensor Cloud" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1,IP:$LAPTOP_IP" \
  -keyout "$TLS_KEY" -out "$TLS_CERT"
chmod 600 "$TLS_KEY"

openssl rand -out "$APP_KEY" 32
chmod 600 "$APP_KEY"

cp "$TLS_CERT" "$ANDROID_TLS_CERT"
cp "$APP_KEY" "$ANDROID_APP_KEY"

echo
echo "Security material generated for laptop address $LAPTOP_IP."
echo "Rebuild and reinstall the Android APK before starting the server."
