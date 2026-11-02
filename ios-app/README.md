# iOS app (Phase 3) — HealthKit feasibility check

There is no Mac in this project, so the app is compiled by a GitHub-hosted Mac (workflow
`.github/workflows/ios-build.yml`) and installed from Windows with Sideloadly using a free Apple ID.

This first version only answers one question: **may an app signed with a free Apple ID use Apple Health?**
It asks for permission to write steps, heart rate and workouts, and can write one test step.

## Build
1. Push to GitHub. The workflow runs on every push that touches `ios-app/` (or run it by hand: Actions >
   "iOS build (unsigned IPA)" > Run workflow).
2. When it is green, open the run and download the artifact `Watch2iPhoneHealth-ipa` (a zip containing the `.ipa`).
   The "Stamp entitlements" step prints the entitlements embedded in the binary — it should list
   `com.apple.developer.healthkit`.

## Install (Windows)
1. Install Sideloadly. Its Windows instructions (per third-party guides) ask for the web versions of iTunes and iCloud,
   not the Microsoft Store ones. Use a free Apple ID; consider a secondary one rather than your main iCloud account.
2. Connect the iPhone by USB, tap Trust, drag the `.ipa` into Sideloadly, enter the Apple ID, press Start.
3. On the iPhone, trust the developer profile (Settings > General > VPN & Device Management) and, if iOS asks,
   turn on Developer Mode.
4. Free-Apple-ID signing lasts about 7 days; Sideloadly can re-sign while the iPhone is reachable.

## The test
1. Open the app, tap **1. Ask for Health access**. A Health permission sheet listing Steps, Heart Rate and Workouts
   should appear. Allow all.
2. Tap **2. Write 1 test step**. The log should say `saved 1 test step`. Check Health > Browse > Activity > Steps >
   Show All Data, then delete the sample.

Report back one of: works / no permission sheet appears / an error message (copy the log line).

## If it does not work
HealthKit needs an entitlement in the signed app. If the free signing strips it, this route is closed and Phase 3
switches to the Shortcuts-based route (no iPhone app).

Nothing in here has been compiled yet — it was written on Windows and is verified only by the cloud build.
