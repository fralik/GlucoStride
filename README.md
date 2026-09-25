# GlucoStride

<img src="docs/brand/assets/strider-logo-mint-on-navy.svg" alt="GlucoStride Strider logo in mint on navy" width="360">

A local, read-only MiniMed CGM reader for **Android 12+**, with a
**Suunto Vertical** workout screen.

There are no insulin-delivery, calibration or pump-setting commands, cloud
services, internet permission or glucose-history database. The phone-to-watch
link is **unencrypted and unauthenticated**: nearby devices may read or imitate
it. Do not use this experimental display for treatment decisions or as a
replacement for established monitoring or alarms.

## Connect and use

1. Open GlucoStride and grant **Nearby devices** and notifications when prompted.
2. Choose **Pair pump** for a new connection and follow the pump's mobile-device
   pairing procedure. Use **Connect paired pump + watch** for a saved pairing.
3. Check the phone's glucose, trend, source age and sample time against the pump.
   When a reading is unusable, the app explains why.
4. Watch sharing starts automatically with a saved pairing, or after the
   authenticated pump peer is saved during first pairing.
5. Select **GlucoStride** in the workout's SuuntoPlus apps. A subscription or
   completed Bluetooth send does not prove the watch displayed the reading.
6. **Stop pump + watch** closes both connections.

Phone and watch units are selected independently; both default to mmol/L.
See [watch setup and units](suunto/LIVE.md) and [pump reader](docs/pump-reader.md).

## Background operation

Keep the ongoing notification enabled. If Android restricts background work,
set GlucoStride's battery mode to **Unrestricted**. On Samsung, also remove it
from **Sleeping apps** and **Deep sleeping apps**. The app reports restrictions
and links to settings but does not change them automatically.

Ordinary pump range loss retries with a capped delay; watch transport failures
retry independently. Authentication errors, Bluetooth/permission loss and Stop
require explicit recovery. Monitoring does not start at boot or resume after
process death or an app update. Verify delivery with the phone screen off.

## Build

### Android

Open `android` in Android Studio and run the application. It is a standalone
Gradle project; its wrapper, configuration and local caches stay in that folder.
The build uses
Android Gradle Plugin 9.2.1, Gradle 9.4.1, compile SDK 37, Build Tools 36.0.0 and
Java 17 bytecode. From the root, adjusting installation paths as needed:

```powershell
Set-Location .\android
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug --console=plain
```

In VS Code on Windows, run the **Android: Build debug APK** build task to
build just the APK using the default Android Studio and SDK locations above.

Output from the repository root: `android\build\outputs\apk\debug\glucostride-debug.apk`.

### Watch

Use Node.js 18+ and the [pinned SuuntoPlus SDK](suunto/README.md#tests-and-build).
From the repository root:

```powershell
Set-Location .\suunto
npm test
npm run build
```

Deploy `suunto\dist\GLUCOLIV-o.dev` using **SuuntoPlus: Add SuuntoPlus Binary to
Watch**. This is a Vertical activity screen, not a watch face. Keep normal Suunto
mobile pairing; synchronization can remove development apps.

## Documentation and release

- [Visual identity and logo assets](docs/brand/index.html): primary, reverse, avatar
  and optical 16 px / 24 px artwork, plus Android app and notification drawables.
- [Pump reader](docs/pump-reader.md): pairing, source-time validation and limitations.
- [BLE protocol](docs/protocol.md): packet format, freshness and reconnect rules.
- [Watch build](suunto/README.md) and [usage / Store preparation](suunto/LIVE.md).

The Android reader is **GPL-3.0-only**. Include [LICENSE](LICENSE),
[third-party notices](THIRD_PARTY_NOTICES.md) and corresponding source.
SuuntoPlus Editor is separately licensed and is not redistributed.

A source release should include the Gradle wrapper JAR, sources, tests and docs.
Exclude caches, `**/build`, `local.properties`, signing keys, `suunto/.sdk`,
`suunto/dist`, `suunto/.build-work*` and editor-generated `suunto/*.dev` binaries.
Copying or zipping the folder does not automatically honor its ignore files.
