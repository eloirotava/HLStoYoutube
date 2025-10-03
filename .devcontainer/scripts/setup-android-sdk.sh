#!/usr/bin/env bash
set -euo pipefail

export ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}
export ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-/opt/android-sdk}
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

echo "[devcontainer] Installing Android SDK packages for API 35…"
yes | sdkmanager --licenses || true
sdkmanager --install \
  "platforms;android-35" \
  "build-tools;35.0.0" \
  "platform-tools" \
  >/dev/null

echo "[devcontainer] Gradle sync (warm cache)…"
./gradlew --no-daemon tasks >/dev/null || true

echo "[devcontainer] Done."
