# Host Mtng — Mobile (Capacitor) Wrapper

This directory documents how to turn the existing Spring Boot web app
(deployed on Render) into a cross-platform native mobile application using
[Capacitor](https://capacitorjs.com/).

> **Zero-Regression Promise** — the mobile wrapper does **not** modify any
> existing web behaviour. The Capacitor WebView simply loads the live
> Render-hosted URL, and a tiny `capacitor-bridge.js` (already injected into
> every Thymeleaf page) only activates inside the native shell. Browsers on
> Chrome/Safari behave identically to before.

---

## 1. Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  Spring Boot backend (Render)                                   │
│  https://hoststudentmeeting.onrender.com                        │
│  ─ JPA + PostgreSQL (production DB)                             │
│  ─ /api/**, /ws/** (WebSocket), Thymeleaf pages                 │
│  ─ CORS allow-list:  capacitor://localhost, http://localhost,   │
│                      ionic://localhost  (added in CorsConfig)   │
└──────────────▲──────────────────────────────▲───────────────────┘
               │ HTTPS                        │ HTTPS / WSS
   ┌───────────┴────────────┐    ┌────────────┴───────────┐
   │ Browser (web)          │    │ Capacitor WebView      │
   │  No bridge active      │    │  capacitor-bridge.js   │
   │  Chrome / Safari / FF  │    │  + safe-area + back btn│
   └────────────────────────┘    └────────────────────────┘
```

Key design decisions:

* `capacitor.config.json` → `server.url` points at the **live Render URL**.
  All API and DB traffic stays on the existing backend — the mobile app is
  a thin shell.
* `webDir: "www"` exists only as a safety-net splash screen — Capacitor
  needs it at build time but it is rarely shown.
* `capacitor-bridge.js` is auto-injected into every page via the
  `</head>` insertion script (`scripts/inject-capacitor-bridge.ps1`). It
  performs a **strict native-platform check** (`Capacitor.isNativePlatform()`)
  and exits immediately when running in a regular browser.

---

## 2. Prerequisites

| Target  | Required tooling                                                  |
| ------- | ----------------------------------------------------------------- |
| Both    | Node.js ≥ 20, npm ≥ 10                                            |
| Android | Android Studio (latest), JDK 17, Gradle 8 (bundled), an emulator  |
| iOS     | macOS, Xcode ≥ 15, an Apple Developer account (for signing)       |

> **Note for Windows users:** iOS builds require macOS. You can develop
> Android entirely on Windows.

---

## 3. First-Time Setup (run from project root)

```powershell
# Install Capacitor + the platform packages
npm install

# Initialise Capacitor (only if capacitor.config.json doesn't exist already
# — this repo ships with one, so you can SKIP this step)
# npx cap init "Host Mtng" com.vk.meetinghost --web-dir=www

# Add native platforms
npx cap add android
npx cap add ios            # macOS only

# Sync the www/ + native plugins into each platform
npx cap sync
```

After `cap add`, you'll see two new directories:

```
HostMtngProd/
├── android/        ← native Android Studio project
├── ios/            ← native Xcode project (macOS only)
└── www/            ← splash bundle that ships with the app
```

> Both `android/` and `ios/` are listed in `.gitignore` — they are derived
> artefacts; only the configuration files (`package.json`,
> `capacitor.config.json`, `www/`, `mobile/`) belong in source control.

---

## 4. Configuring the Render URL

Edit `capacitor.config.json` if your Render service has a different name:

```json
{
  "server": {
    "url": "https://YOUR-SERVICE.onrender.com",
    "cleartext": false,
    "allowNavigation": ["*.onrender.com", "api.callmebot.com", "api.mymemory.translated.net"]
  }
}
```

After edits, always run:

```powershell
npx cap sync
```

so the change propagates into both `android/` and `ios/`.

---

## 5. Required Native Permissions

The app uses the **microphone** and **camera** for WebRTC meetings. You
must add the corresponding permission strings to each platform.

### 5a. Android — `android/app/src/main/AndroidManifest.xml`

Add inside `<manifest>` (before `<application>`):

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
<uses-feature android:name="android.hardware.microphone" android:required="false" />
```

A reference snippet is provided in
[`mobile/android-permissions.xml`](android-permissions.xml).

### 5b. iOS — `ios/App/App/Info.plist`

Add inside the top-level `<dict>`:

```xml
<key>NSCameraUsageDescription</key>
<string>Host Mtng uses the camera so you can join video meetings.</string>
<key>NSMicrophoneUsageDescription</key>
<string>Host Mtng uses the microphone to record your voice during meetings.</string>
<key>NSPhotoLibraryAddUsageDescription</key>
<string>Host Mtng saves recorded meetings to your library when you tap Download.</string>
```

A reference snippet is provided in
[`mobile/ios-info-plist.txt`](ios-info-plist.txt).

---

## 6. Building Android — Debug `.apk`

```powershell
# (one time) open in Android Studio so it can index + sync Gradle
npx cap open android

# Or build straight from CLI:
cd android
.\gradlew assembleDebug
# Output: android\app\build\outputs\apk\debug\app-debug.apk
```

Install on a connected device/emulator:

```powershell
adb install -r android\app\build\outputs\apk\debug\app-debug.apk
```

### Building Android — Signed Release `.aab` (Play Store)

1.  Generate a keystore (one-time):

    ```powershell
    keytool -genkey -v -keystore release.keystore -alias hostmtng `
        -keyalg RSA -keysize 2048 -validity 10000
    ```

2.  Add the signing config to `android/app/build.gradle` inside `android { … }`:

    ```groovy
    signingConfigs {
        release {
            storeFile     file('../../release.keystore')
            storePassword System.getenv('HOSTMTNG_KEYSTORE_PWD')
            keyAlias      'hostmtng'
            keyPassword   System.getenv('HOSTMTNG_KEY_PWD')
        }
    }
    buildTypes {
        release {
            signingConfig signingConfigs.release
            minifyEnabled false
        }
    }
    ```

3.  Build the bundle:

    ```powershell
    $env:HOSTMTNG_KEYSTORE_PWD = "your-keystore-password"
    $env:HOSTMTNG_KEY_PWD      = "your-key-password"
    cd android
    .\gradlew bundleRelease
    # Output: android\app\build\outputs\bundle\release\app-release.aab
    ```

Upload the `.aab` to the Google Play Console.

---

## 7. Building iOS — `.ipa` (TestFlight / App Store)

> Requires macOS + Xcode.

```bash
npx cap open ios
```

In Xcode:

1.  Select the **App** target → **Signing & Capabilities** → choose your team.
2.  **Product → Archive**.
3.  In the Organizer that opens, click **Distribute App** → choose
    **App Store Connect** for TestFlight, or **Ad-Hoc** for direct install.
4.  Xcode generates the `.ipa` and uploads it.

---

## 8. CORS / WebSocket configuration

The Spring Boot backend has been updated so it accepts requests from the
native WebView origins:

* `src/main/java/com/host/studen/config/CorsConfig.java` — registers
  `capacitor://localhost`, `http://localhost`, `https://localhost`,
  `ionic://localhost` for `/api/**` and `/ws/**`.
* `src/main/java/com/host/studen/config/WebSocketConfig.java` — appends
  the same patterns to the SockJS allowed-origins list.
* `application.properties` (optional) — `app.cors.allowed-origins=` may
  be set to a comma-separated list of additional origins.

No backend redeploy is needed for CORS to "just work" with the mobile
app, but if your Render env has `APP_WEBSOCKET_ALLOWED_ORIGINS` set, make
sure it doesn't accidentally exclude `capacitor://localhost` — the new
defaults include it automatically even when the env-var is set.

---

## 9. Mobile-only behaviours (already wired)

The injected `static/js/capacitor-bridge.js` adds:

| Feature                | What it does                                                                               |
| ---------------------- | ------------------------------------------------------------------------------------------ |
| `body.capacitor-native`| CSS hook for native-only styling                                                           |
| Safe-area padding      | Pads `.header-bar`, `.navbar`, `[data-safe-area="top"]` etc. with `env(safe-area-inset-*)` |
| `viewport-fit=cover`   | Auto-injected so `env(safe-area-inset-*)` returns non-zero on iPhone notches               |
| Hardware back button   | Goes back in history; at the dashboard root, asks the user to confirm exit instead of closing |

To opt an existing element into safe-area padding, just add the data
attribute — works in both web and native (web is `env() = 0px`):

```html
<div class="header-bar" data-safe-area="top"> … </div>
```

---

## 10. Troubleshooting

| Symptom                                        | Fix                                                                                       |
| ---------------------------------------------- | ----------------------------------------------------------------------------------------- |
| `XHR blocked by CORS` from the mobile app      | Verify `CorsConfig` is loaded; redeploy backend; confirm `cors(...)` is in `SecurityConfig` |
| WebSocket fails (`/ws` returns 403)            | Check `APP_WEBSOCKET_ALLOWED_ORIGINS` env on Render — defaults already cover Capacitor    |
| Microphone never prompts on Android            | `RECORD_AUDIO` permission missing in `AndroidManifest.xml`                                |
| iPhone status bar covers the header            | Add `data-safe-area="top"` to your fixed header element                                   |
| Back button closes the app from a sub-page     | The bridge polls 20×; check console for `[capacitor-bridge] back-button setup failed`     |
| White screen on launch                         | `server.url` is unreachable — check Render service status, then `npx cap sync`            |

---

## 11. Release checklist

- [ ] `capacitor.config.json` → `server.url` points at production Render URL
- [ ] `npx cap sync`
- [ ] Android: bump `versionCode` + `versionName` in `android/app/build.gradle`
- [ ] iOS: bump build number + version in Xcode
- [ ] Test on a real device (camera + mic + WebSocket transcript)
- [ ] Sign + upload the `.aab` / `.ipa`
