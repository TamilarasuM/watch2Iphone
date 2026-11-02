---
name: wear-deploy
description: Build the Wear OS app in wear-app/ and install it on the Galaxy Watch over Wi-Fi debugging (adb pair / connect), launch it, and read Logcat. Use when asked to build, install, run, test or debug the watch app on the real watch.
---

# Build, install and debug on the real watch

Source for the Wi-Fi steps: https://developer.android.com/training/wearables/get-started/debug-wifi

## Prerequisites on this machine
- `JAVA_HOME` = JDK 17, `GRADLE_USER_HOME` = `D:\gradle-home`, `adb` on PATH (new terminals only; restart VS Code / the terminal after the first setup). The default `java` on PATH is Java 8 — never use it for Gradle.
- adb 30.0.0 or higher; PC and watch on the same Wi-Fi. Enterprise Wi-Fi often isolates devices — if so, use a phone hotspot for both.

## Build
```powershell
cd D:\Git\learning\watchSyncApp\watch2Iphone\wear-app
.\gradlew.bat assembleDebug
```
APK: `wear-app\app\build\outputs\apk\debug\app-debug.apk`

## One-time pairing (watch)
1. Enable Developer options on the watch (the exact Samsung path was not verified from an official page).
2. Settings > Developer options: turn on **ADB debugging**, then **Wireless debugging** (choose Allow / Always allow on this network).
3. Wireless debugging > **Pair new device**: note the pairing code, IP address and pairing port.
```powershell
adb pair <ip>:<pairing-port>        # enter the code shown on the watch
```

## Connect (every time Wi-Fi debugging restarts or the network changes)
The connection port differs from the pairing port — read it on the main Wireless debugging screen, not the "Pair new device" screen.
```powershell
adb connect <ip>:<connection-port>
adb devices
```
If several devices are listed, add `-s <ip>:<connection-port>` to every adb command.

## Install, launch, log
```powershell
adb install -r wear-app\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.tamilarasu.watch2iphone/.MainActivity
adb logcat -s W2I.Perm W2I.Ble W2I.Svc W2I.Ancs
```
Reset to re-test first-run behaviour: `adb uninstall com.tamilarasu.watch2iphone`.

## Troubleshooting (from the docs)
`adb kill-server` then `adb start-server`; toggle Wireless debugging off and on on the watch; confirm both devices are on the same network.

## Project rule
After every step, give the user the exact commands above plus the exact on-device test, and do not mark the step done until the user confirms it works.
