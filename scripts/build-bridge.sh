#!/usr/bin/env bash
# Baut die Go-Bridge (whatsmeow) als Android-AAR nach app/libs/bridge.aar.
set -euo pipefail
cd "$(dirname "$0")/../bridge"

export PATH="$HOME/.local/go/bin:$HOME/go/bin:$PATH"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -1)}"

mkdir -p ../app/libs
gomobile bind -target=android/arm64 -androidapi 26 -javapkg de.picsync \
  -ldflags="-s -w" -o ../app/libs/bridge.aar .
echo "OK: app/libs/bridge.aar"
