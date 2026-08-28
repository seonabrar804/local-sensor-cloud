#!/bin/zsh
set -e

PROJECT_DIRECTORY="${0:A:h}"
cd "$PROJECT_DIRECTORY/server"

echo "Starting the private laptop cloud with TLS + AES-256-GCM."
echo "Encrypted dashboard: https://localhost:8787"
echo
exec node server.js
