# WinUAE Android Port: Initial Engineering Plan

This repository tracks an Android ARM64 port of WinUAE, retaining the upstream emulator core and existing Unix host work wherever practical.

## Current baseline

- Upstream project: https://github.com/tonioni/WinUAE
- Existing host-port notes: [README_unix.md](README_unix.md)
- The Unix port uses CMake and SDL3 for video, input, and audio when available.
- The current Unix configuration UI is based on Qt Widgets. Android does not provide a desktop Qt Widgets environment by default, so the first Android build should avoid assuming that UI can be used unchanged.
- SDL3 documents an Android project integration in its official guide: https://wiki.libsdl.org/SDL3/README-android

## Porting principles

1. Keep upstream WinUAE history and licensing notices intact.
2. Make Android-specific changes in isolated files and CMake options where possible.
3. Do not bundle Kickstart ROMs, copyrighted disks, or other proprietary Amiga media.
4. Prefer a repeatable cloud CI build so contributors can work without a local desktop toolchain.
5. Validate each stage with build logs and tests before claiming the emulator runs.

## Milestones

### M0: Repository and build reconnaissance
- Confirm upstream branch and Unix-port baseline.
- Inspect existing CI workflow and CMake options.
- Identify platform APIs used by the Unix host layer that are unavailable or unsuitable on Android.
- Record the first concrete compile blockers.

### M1: Android build scaffold
- Add an Android Gradle project and wrapper, using a supported Android Gradle Plugin / NDK combination.
- Integrate SDL3 using its documented Android approach.
- Configure CMake to build a shared native library for Android rather than a desktop executable.
- Initially target arm64-v8a.

### M2: Minimal native startup
- Make the app package installable.
- Start the native entry point and SDL Android lifecycle.
- Produce useful startup diagnostics in Android logcat.
- Keep optional desktop dependencies such as Qt, native CD/SCSI, and desktop packaging disabled for Android.

### M3: Emulator host integration
- Connect SDL window/framebuffer, audio, keyboard, and game-controller events to the emulator.
- Handle pause/resume, focus changes, and activity recreation safely.
- Add Android-safe file access for user-selected ROM and disk-image files.

### M4: First boot and iterative compatibility
- Test with user-supplied, legally obtained Kickstart ROMs and disk images.
- Add automated smoke tests where feasible.
- Profile ARM64 performance and investigate JIT restrictions only after a non-JIT build works.

## Immediate next technical task

Inspect the top-level CMake target definitions and .github/workflows/build-winuae-binary.yml, then design an Android-only configuration that excludes desktop-only components without disturbing Linux/macOS builds.

## APK updates and CI signing

Android installs an APK as an update only when its package name and signing certificate match the installed app and its `versionCode` is higher. The Android CI workflow can use a persistent keystore through these GitHub Actions repository secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_PASSWORD`, and `ANDROID_KEY_ALIAS`. Create and back up the keystore once; never commit it. For example, create it with `keytool -genkeypair -keystore winuae-release.jks -alias winuae -keyalg RSA -keysize 2048 -validity 10000`, then store its base64 form as `ANDROID_KEYSTORE_BASE64` (`base64 -w 0 winuae-release.jks` on Linux). Set the other secrets to the keystore password, key password, and alias.

Without these secrets, CI falls back to a temporary signing key and its APKs cannot update installs from other CI runs. If an installed APK was signed with a previous temporary CI key, Android cannot update it with the new persistent key; that installation needs a one-time reinstall. Future builds signed with the configured keystore can update in place as `versionCode` increases.
