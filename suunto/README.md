# GlucoStride watch app

SuuntoPlus workout screen for **Suunto Vertical**, display `o` (280 x 280).
App ID **`GLUCOLIV`**. The default editor and CLI builds receive
the Android bridge's v2 protocol. See [usage and units](LIVE.md) and
[BLE contract](../docs/protocol.md).

The watch displays existing readings only. It has no therapy controls, alarms,
cloud connection or measurement logging. The BLE link is unencrypted and
unauthenticated; do not use it for treatment decisions.

## Tests and build

From this directory, **Node.js 18+** is sufficient for tests; no npm dependencies
need installing:

```powershell
npm test
```

Builds require these pinned official SDK versions:

| Component | Version |
| --- | --- |
| `Suunto.suuntoplus-editor` | `1.42.0` |
| Bundled `@suunto-internal/suuntoplus-tools` | `2.1.5` |
| VSIX SHA-256 | `E7942C638443984C257F6CC5DC860E209C0C121B1D9131A548008F22B9E816F7` |

Obtain the SDK from the official Marketplace into ignored `.sdk`, or use an
installed/extracted copy of the same version:

```powershell
New-Item -ItemType Directory -Path .sdk -Force | Out-Null
Invoke-WebRequest `
  -Uri 'https://marketplace.visualstudio.com/_apis/public/gallery/publishers/Suunto/vsextensions/suuntoplus-editor/1.42.0/vspackage' `
  -OutFile '.sdk\suuntoplus-editor.vsix'
$expected = 'E7942C638443984C257F6CC5DC860E209C0C121B1D9131A548008F22B9E816F7'
if ((Get-FileHash '.sdk\suuntoplus-editor.vsix' -Algorithm SHA256).Hash -ne $expected) {
  throw 'Unexpected SuuntoPlus Editor package checksum.'
}
Copy-Item '.sdk\suuntoplus-editor.vsix' '.sdk\suuntoplus-editor.zip'
Expand-Archive -Path '.sdk\suuntoplus-editor.zip' -DestinationPath '.sdk\unpacked'
Remove-Item '.sdk\suuntoplus-editor.zip'
npm run build
```

If the SDK is already at `.sdk\unpacked\extension`, just run `npm run build`.
For another installation location:

```powershell
node .\scripts\build.cjs 'C:\path\to\suunto.suuntoplus-editor-1.42.0'
```

`SUUNTOPLUS_EXTENSION` is an alternative to the path argument. The build rejects
other SDK versions, validates the manifest against the official schema, invokes
`buildApp`, and checks archive contents and compiled/minified callbacks.
Intermediates stay in `.build-work-live` and are removed on success.
Output: **`dist\GLUCOLIV-o.dev`**. The SDK is separately licensed and must not be
included in a source release.

## Editor and deployment

1. Open this folder in VS Code with the pinned SuuntoPlus Editor.
2. Use the **SuuntoPlus Apps** view to build or simulate display `o`. Check layout,
   units and unavailable states; the simulator does not prove real BLE delivery.
3. Connect the watch by USB and use **SuuntoPlus: Add SuuntoPlus Binary to Watch**
   with `dist\GLUCOLIV-o.dev`, or use the editor's deploy action.
4. Start pump monitoring on Android, then select **GlucoStride** for a new workout.

Keep normal Suunto mobile pairing. Conflicting SuuntoLink/mobile synchronization
can interfere with deployment or remove development apps. Reselect the sports
app after replacement; reload the editor if it caches old metadata.
Verify readings and connection behavior on target hardware.

## Source package

```powershell
npm test
npm run package:store
```

This builds/verifies the binary and creates a source ZIP in `dist` through
the official SDK. It contains exactly `main.js`, `manifest.json`, `data.json`,
`mmol.html` and `mgdl.html`, checked byte-for-byte. It excludes SDK files, tests,
Android sources and credentials. Nothing is uploaded. This watch archive is not
the Android application's GPL corresponding-source distribution.
See [Store preparation](LIVE.md#store-submission-preparation).

Official references: [Editor guide](https://apizone.suunto.com/suuntoplusEditor),
[Marketplace](https://marketplace.visualstudio.com/items?itemName=Suunto.suuntoplus-editor).
