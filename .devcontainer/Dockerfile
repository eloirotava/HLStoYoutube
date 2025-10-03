# Simple Android build environment for GitHub Codespaces
# Base with Java 17 (Gradle uses JDK 17 per project config)
FROM mcr.microsoft.com/devcontainers/java:17

# Android SDK paths
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH

# Install deps and create directories
RUN apt-get update && \
    apt-get install -y --no-install-recommends unzip wget tar ca-certificates && \
    mkdir -p $ANDROID_HOME/cmdline-tools && \
    rm -rf /var/lib/apt/lists/*

# Install Android cmdline-tools (latest)
RUN wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/cmdtools.zip && \
    unzip -q /tmp/cmdtools.zip -d /tmp/cmdtools && \
    mkdir -p $ANDROID_HOME/cmdline-tools/latest && \
    mv /tmp/cmdtools/cmdline-tools/* $ANDROID_HOME/cmdline-tools/latest/ && \
    rm -rf /tmp/cmdtools /tmp/cmdtools.zip

# Pre-create licenses dir
RUN mkdir -p $ANDROID_HOME/licenses

# Accept licenses noninteractively (can be overridden by postCreateCommand script as well)
RUN yes | sdkmanager --licenses || true

# Pre-install platform tools (adb) to speed up later steps
RUN sdkmanager "platform-tools"
