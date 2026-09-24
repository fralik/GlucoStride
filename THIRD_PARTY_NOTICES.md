# Third-party notices

The GlucoStride Android pump-reader work is licensed under **GPL-3.0-only**.
The full GPL text is in `LICENSE` and bundled in the Android app's open-source
notices. Keep corresponding source and these notices with distributions of
the derivative Android application.

## Adapted pump driver and protocol code

- **GlycemicGPT unofficial Android / contributors**, GPL-3.0-only.
  Upstream: [lumose-health/android-unofficial](https://github.com/lumose-health/android-unofficial),
  revision [59e68104df17614c50173bed954843a5f56e588b](https://github.com/lumose-health/android-unofficial/tree/59e68104df17614c50173bed954843a5f56e588b).
  Adaptations are limited to the Medtronic peripheral, authentication,
  CGM-only GATT exchange and CGM decoding concepts/source documented in the
  headers under `android\src\main\java\io\glucostride\pump`.
  The upstream app, Hilt/plugin infrastructure, insulin/history readers,
  cloud features and therapy surfaces are not included.
- **OpenMinimed / Pal Marci and contributors**, GPL-3.0.
  Their JavaPumpConnector and PythonPumpConnector are the upstream protocol
  choreography and CGM reference acknowledged by the Android driver.
  JavaSake is consumed as a Maven dependency rather than copying its protocol
  key database into this repository.
- **OpenMinimed JavaSake 0.2.0**, `org.openminimed:javasake:0.2.0`, GPL-3.0.
  It implements the SAKE handshake and sequenced session cryptography.
  Upstream: [OpenMinimed/JavaSake](https://github.com/OpenMinimed/JavaSake),
  source reference revision [64ae39dda0a1e5c7742faa8f430156e9f4e22046](https://github.com/OpenMinimed/JavaSake/tree/64ae39dda0a1e5c7742faa8f430156e9f4e22046).
  The build uses the pinned Maven artifact; no local upstream checkout is required.
- **The Legion of the Bouncy Castle Inc.**, `bcprov-jdk18on:1.84`, MIT license.
  Its complete notice is bundled in
  `android\src\main\assets\BouncyCastle-LICENSE.txt`.
  This explicit version overrides JavaSake's older transitive version.

The upstream repositories remain unchanged. The adaptations remove raw
handshake/health-data logs and limit writes to authentication, notification
subscriptions, and requesting the last CGM record. Timestamp/status handling
does not use the upstream reader's retrieval-time timestamp as sample time.

## Build tools and other components

Kotlin and the Gradle wrapper retain their applicable upstream notices.
The Android platform is not redistributed as part of this application's source.
SuuntoPlus Editor is separately licensed development tooling. Obtain it
separately as described in [SDK setup](suunto/README.md#tests-and-build); the
default local SDK folder is ignored. It is not included in the Android app or
relicensed here.
Do not include the SDK in a source or binary distribution without reviewing
its separate terms.

This software comes without warranty. Source availability and successful
compilation do not establish pump compatibility, medical-device approval,
or suitability for treatment decisions.
