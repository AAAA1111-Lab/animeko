#!/usr/bin/env bash
#
# Copyright (C) 2024-2025 OpenAni and contributors.
# Licence: GNU AGPL v3  – see project root for details.
#

set -euo pipefail

# --------------------------------------------------------------------
# Base SDK from Debian/Ubuntu repos
apt -qq update
apt -qq install -y --install-recommends android-sdk unzip curl

# --------------------------------------------------------------------
# Environment
export ANDROID_HOME=/usr/lib/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin"

echo "sdk.dir=$ANDROID_HOME" > local.properties

# --------------------------------------------------------------------
# Fetch latest command-line tools (needed for `sdkmanager`)
mkdir -p "$ANDROID_HOME/cmdline-tools"
cd /tmp
curl -sSL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o cmdline-tools.zip
rm -rf cmdline-tools
unzip -q cmdline-tools.zip
mv cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"
rm cmdline-tools.zip

# --------------------------------------------------------------------
# Accept licences required by Gradle / Android build-tools
# `yes` may get SIGPIPE when sdkmanager closes stdin early → exit-status 141.
# We treat that as success so the script doesn’t abort under `pipefail`.
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null || [[ $? -eq 141 ]]

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
yes | "$SDKMANAGER" \
      "platforms;android-35" \
      "build-tools;35.0.0" \
      "platform-tools"             || [[ $? -eq 141 ]]

echo "✅ Android SDK API 35 installed."
