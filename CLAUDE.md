# watch2Iphone

Personal-use app that connects a Samsung Galaxy Watch 4 Classic (Wear OS 6) to an iPhone 15 Plus, similar to "Merge: Connect Android Watches".

## Phases (strictly in order — do not start the next until the user confirms the current one works on real devices)

1. **Notifications** — Wear OS app (Kotlin, Compose for Wear OS) is a BLE GATT client of the iPhone's Apple Notification Center Service (ANCS): subscribe to Notification Source + Data Source, request attributes via Control Point, post native Wear OS notifications, dismiss on iPhone, accept/decline calls, foreground service with auto-reconnect.
2. **Music control** — Apple Media Service (AMS) client: now-playing, play/pause/next/previous/volume.
3. **Health sync** — small iOS app (Swift, CoreBluetooth + HealthKit) receiving steps / heart rate / workouts from the watch over a custom BLE GATT service and writing to Apple Health.

Current state:
- Phase 1 code-complete and compile-verified only (APK builds, 15 JVM unit tests for the ANCS wire format pass). NOTHING about BLE has been tested on the real watch/iPhone yet — the user must run the device test and confirm before Phase 1 is called done. Step 1 (permissions screen) was also never confirmed on the watch.
- Phase 2 (AMS) skipped at the user's request.
- Phase 3: only a feasibility experiment exists so far: `ios-app/` (SwiftUI, XcodeGen `project.yml`) + `.github/workflows/ios-build.yml` build an unsigned IPA on a GitHub macOS runner; the user installs it with Sideloadly (free Apple ID). It asks for HealthKit access and writes one test step. NOT built or pushed yet (the repo is public; ask before commit/push). If free-Apple-ID signing strips the HealthKit entitlement, fall back to a Shortcuts-based route (Log Health Sample fed by data fetched over the internet). Original blocker, for context: the iOS build path — the user has no Mac (Windows only). A hybrid framework (Flutter/React Native/Capacitor) does not remove the need for Xcode on macOS. Options (all unverified, see below) need a decision from the user.

Code map (`wear-app/app/src/main/java/com/tamilarasu/watch2iphone/`): `ancs/AncsProtocol.kt` (pure wire format + tests), `ancs/AncsClient.kt` (GATT client: bonding, discovery, MTU, subscribe Data Source then Notification Source, request pipeline), `ble/` (BleScanner, BleAdvertiser with ANCS service-solicitation UUID, GattServerHost), `notify/` (NotificationPoster, ActionReceiver), `service/` (AncsService foreground service type connectedDevice, AncsController with reconnect backoff), `MainActivity.kt` (permissions + scan/advertise/reconnect UI).
Two connection modes exist on purpose, because the ANCS spec does not say who initiates: SCAN (watch is the central) and ADVERTISE (watch advertises, iPhone connects, then the watch is the GATT client over that link).

## Working rules (from the user)

- Verify every API, permission and protocol detail against official docs (Apple ANCS/AMS spec, Android BLE / Wear OS docs) before implementing. If something cannot be verified, stop and tell the user — do not assume.
- Small steps. After each step give the exact build + install commands (adb over Wi-Fi debugging) and the exact test to run on the real devices. Do not mark a step done until the user confirms.
- Log every BLE state change to Logcat using the tags in `BleLog.kt` (`W2I.Perm`, `W2I.Ble`, `W2I.Svc`, `W2I.Ancs`).
- If a feature hits a platform limitation (e.g. call audio), explain it plainly instead of faking a workaround.
- Do not copy code from third-party apps. github.com/k97/WearBridge is a reference for approach only. The user said reverse engineering is allowed, but the ANCS/AMS protocols are publicly documented, so it should not be needed — ask before decompiling anything.

## Layout

- `wear-app/` — Android (Wear OS) project. Package `com.tamilarasu.watch2iphone`.
- `ios-app/` — Phase 3 only; does not exist yet.

## Toolchain (Windows, this machine)

- JDK 17 (Microsoft OpenJDK) — `JAVA_HOME` is set at user level. The `java` on PATH is Java 8 and must not be used for Gradle.
- Android SDK at `%LOCALAPPDATA%\Android\Sdk` (platforms 34, 36, 37.0; build-tools 34.0.0, 36.0.0). `ANDROID_HOME` set; `platform-tools` (adb) on PATH.
- `GRADLE_USER_HOME=D:\gradle-home` (C: has little free space).
- AGP 9.5.0 (built-in Kotlin: do not apply `org.jetbrains.kotlin.android`), Gradle 9.6.0 via wrapper, Kotlin 2.4.21 (Compose compiler plugin).
- `compileSdk = 37` because current androidx releases require it; `targetSdk = 36` (Wear OS 6); `minSdk = 33`.
- Build: `cd wear-app; .\gradlew.bat assembleDebug`.

## Open questions (resolve by test on the real devices, not by assumption)

- Who initiates the BLE connection: the watch scanning for the iPhone (WearBridge README) vs. the watch advertising the ANCS solicited-service UUID so the iPhone connects (forum reports). Apple's ANCS spec text does not say. Test in Phase 1 step 2.
- Machine limits: C: is nearly full (~0.5 GB free; the Windows paging file lives there) and RAM is ~8 GB. `gradle.properties` keeps the build light (Kotlin in-process, 1.5 GB heap); the test JVM heap is capped at 256 MB. If Gradle dies with "daemon disappeared" or "paging file is too small", free space on C: first.
- iOS build path for Phase 3 without a Mac — unverified: (a) GitHub Actions macOS runner + sideload from Windows with Sideloadly/AltStore, (b) rented cloud Mac, (c) borrowed Mac. Not verified: whether HealthKit works with a free Apple ID when sideloaded, macOS runner free-tier terms, whether the repo is public.
- `neverForLocation` on `BLUETOOTH_SCAN` may filter the iPhone out of scan results (docs warn some beacons are filtered). If the iPhone is missing from scans, remove the flag and add `ACCESS_FINE_LOCATION`.
