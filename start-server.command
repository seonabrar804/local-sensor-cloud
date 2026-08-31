#!/usr/bin/env bash
set -e

PROJECT_DIRECTORY="$(cd -- "$(dirname -- "$0")" && pwd)"
cd "$PROJECT_DIRECTORY/server"

echo "Starting the private laptop cloud with approved-phone pairing, TLS, and AES-256-GCM."
echo "Encrypted dashboard: https://localhost:8787"
echo "Approve new phone requests only when the six-digit codes match."
echo
exec node server.js
