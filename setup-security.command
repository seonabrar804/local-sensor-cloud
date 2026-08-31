#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIRECTORY="$(cd -- "$(dirname -- "$0")" && pwd)"
FORCE="${1:-}"
TLS_KEY="$PROJECT_DIRECTORY/server/tls/server-key.pem"
TLS_CERT="$PROJECT_DIRECTORY/server/tls/server-cert.pem"

if [[ "$FORCE" != "--force" ]] && { [[ -e "$TLS_KEY" ]] || [[ -e "$TLS_CERT" ]]; }; then
  echo "The laptop TLS certificate already exists. Nothing was changed."
  echo "Use ./setup-security.command --force only when intentionally changing the laptop certificate."
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

mkdir -p "$PROJECT_DIRECTORY/server/tls" "$PROJECT_DIRECTORY/server/keys"

openssl req -x509 -newkey rsa:3072 -sha256 -days 825 -nodes \
  -subj "/CN=Local Sensor Cloud" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1,IP:$LAPTOP_IP" \
  -keyout "$TLS_KEY" -out "$TLS_CERT"
chmod 600 "$TLS_KEY"

echo
echo "The laptop TLS certificate was generated for $LAPTOP_IP."
echo "The Android APK is general and does not need to be rebuilt for this laptop."
echo "Start the server, send a request from the phone, compare the code, and approve it on the laptop."
