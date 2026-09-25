# Watch usage and release

GlucoStride displays glucose, trend, source age and connection status during
a Suunto Vertical workout. It connects to the Android phone, not directly to the
pump. [Build and deploy](README.md), start pump monitoring on the phone, then
select **GlucoStride** in the workout's SuuntoPlus apps.

The phone starts sharing automatically; **Stop pump + watch** ends it.
Disconnection hides values at the next screen update. Without a disconnect
callback, 20 seconds without an accepted packet hides them; source age of
600 seconds also hides them. See the [protocol](../docs/protocol.md).

The local link is unencrypted and unauthenticated. No glucose values are
intentionally saved to exercise logs or workout summaries. This display is not
for treatment decisions, alarms or replacement of established monitoring.
Normal watch wake/button behavior is unchanged; tap-to-wake is not an app feature.

## Choose glucose units

The **Glucose unit** setting controls the main reading independently of the
Android app's unit and the watch's metric/imperial setting:

| Selection | Glucose example |
| --- | --- |
| mmol/L (default) | 7.0 mmol/L |
| mg/dL | 126 mg/dL |

These are synthetic examples. The trend uses MiniMed-style arrows based on the
raw rate: `→` below 1 mg/dL/min, one arrow from 1, two from 2 and three from
3 mg/dL/min. Down arrows use the same absolute thresholds. Unknown trend is
`--`, not flat. Wire values remain unchanged.

The trend row uses Suunto's native medium arrow and dash icons in a fixed-width
centered field. Ordinary Unicode arrows are not present in the watch's text
font and must not be used here. Build verification checks the compiled trend
formatter against the display `o` font's supported characters.

For Store-delivered apps, open the Suunto mobile app's paired watch, then
**SuuntoPlus / My Apps > GlucoStride > settings > Glucose unit**. Sync and start
a new workout. The preference persists between workouts. See
[Suunto's settings workflow](https://apizone.suunto.com/suuntoplus-sports-apps).

Sideloaded development apps may not appear in My Apps; setting sync remains to
be verified on the target devices. `data.json` supplies the initial preference
for local builds (`"0"` mmol/L, `"1"` mg/dL), but does not guarantee overwriting
an existing preference. Always check the actual unit label.

## Clock diagnostics

The SDK does not document a monotonic clock. The adapter uses native
`$.get('/Dev/Time', callback)` UTC reads, including during pause/resume, and hides
values until a fresh response arrives. It does not use the workout timer.

| Display | Meaning |
| --- | --- |
| `CLOCK WAIT` | Waiting for a fresh time response; not latched |
| `CLOCK INVALID` | Missing, non-numeric, non-finite or non-positive time after initialization |
| `CLOCK BACK` | Time moved backwards |
| `CLOCK STOPPED` | Three consecutive evaluate-initiated reads returned unchanged time |
| `CLOCK NO REPLY` | A read remained unanswered across three subsequent evaluate callbacks |

All clock states hide glucose, trend and age. Faults other than `CLOCK WAIT`
latch until the sports app is reselected. Forward clock steps age values
conservatively; delayed callbacks can expire data early, not extend its lifetime.
These guards are not a guaranteed monotonic clock or authenticated time.
Validate pauses, screen changes, synchronization and reconnect on actual firmware.

## Store submission preparation

The app is a submission candidate, not an approved or published Store release.
[Create the source package](README.md#source-package) locally, then:

- Confirm publisher identity, support contact, distribution rights and Suunto
  terms through the publisher's Partner Program/API Zone account.
- Supply authorized screenshots of the actual app in both unit modes.
- Test full workouts, fault handling and unit-setting sync on actual devices.
  List only tested combinations.
- Provide an installable Android companion with its corresponding source and
  [license notices](../THIRD_PARTY_NOTICES.md).
- Disclose the BLE limitations and confirm Suunto accepts this glucose-display
  use case. Do not claim medical approval or general pump/watch compatibility;
  the manifest targets only Vertical display `o`.

Follow [Suunto's publication process](https://apizone.suunto.com/suuntoplus) and
the portal's current requirements. A successful build or source ZIP is not approval.
