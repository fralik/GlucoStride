# Read-only pump reader

Android `GlucoStride` uses one pump reader and automatically shares its
validated readings with the watch. It does not change therapy, calibration,
pump time or settings, and does not retrieve glucose history.

## Pairing and lifecycle

- Grant Nearby devices and enable Bluetooth. The phone must support BLE
  peripheral advertising. Stop competing pump phone apps before pairing.
- Choose **Pair pump**, then select **Mobile Gluco** through the pump's
  mobile-device pairing workflow. The pump connects to the phone; GlucoStride
  does not scan for pumps or require a manually entered serial or key.
- Use **Connect paired pump + watch** for a saved pairing. Reconnect accepts only
  the saved pump address. Unresolved address changes may require deliberate
  re-pairing; unrelated peers are not trusted automatically.
- **Stop pump + watch** closes both links. **Forget local pairing** removes only
  this app's metadata, not Android bonds or the pump's mobile-device pairing.
  Never remove the sensor/transmitter or reset all Bluetooth settings as a shortcut.
- When changing phones, stop monitoring on the first phone and pair deliberately
  on the second. Do not copy pairing preferences or session keys.

One user-started `connectedDevice` foreground service renews a bounded wake lock.
Ordinary saved-pump link loss retries with 5/10/15-second capped delays until
stopped. Authentication failures and Bluetooth/permission loss require explicit
recovery. Boot, process death and app updates do not restart monitoring.
See [background settings](../README.md#background-operation).

Watch advertising starts with saved-pair monitoring and sends unavailable
during authentication. On first pairing it waits until the authenticated pump
peer is saved. Pump and watch peer state, callbacks and subscriptions remain
separate; watch failures retry after 30 seconds without restarting the pump.

## Reading and source time

After SAKE authentication, the transport reads the CGM feature, session reference,
sensor status and last measurement, then rechecks the session reference. Another
poll runs about 15 seconds after the exchange completes; this is not the sensor's
measurement cadence. Necessary writes are limited to authentication,
notification subscriptions and the report-last-record request.

The parser checks frame structure, feature-dependent CRC, SFLOAT values and
sensor status. Measurement time is session start plus measurement offset, not
retrieval time. If session timezone/DST is absent, a read-only Current Time
reference (`0x1805/0x2A2B`) compares the pump's local times without assuming the
phone's timezone.

The bounded clock mapping stays fixed during monitoring. Invalid dates,
missing/slow clock reads, inconsistent sessions, future samples and detected
pump-clock jumps suppress values. A detected clock change latches a time error
until restart. Without timezone metadata, mapped UTC is an estimate; after a
monitoring restart it can shift by read uncertainty, and the watch may reject an
older estimate until a newer sample arrives.

Accepted samples age using Android elapsed time. Repeated reads cannot make them
younger. Values are hidden at source age **600 seconds**, on read failure or
disconnect, and after **90 seconds without a completed record**. Current sensor
status is checked even when an older stored record contains a number.
Stopped/calibration-pending/calibration-not-allowed states are unavailable, not
guessed to be warm-up. These thresholds are engineering limits, not clinical advice.

## Diagnostics and compatibility

The app separates authentication, completed reads, reading validation and watch
delivery. Completed records can be repeated or rejected samples; confirmed BLE
sends can carry unavailable status and do not confirm watch rendering.

The optional **Experimental discovery pacing: BALANCED** setting is off by
default and can be changed only while stopped. It requests link parameters after
bonding; the pump may ignore or reject it. It does not change MTU or pump data.

The driver uses the reference CRC-16/CCITT-FALSE dialect and JavaSake
`clientCrypt()` direction; it does not try alternate checksums or ciphers after
failure. Unsolicited authenticated notifications advance the cipher but are not
used as the requested record. Multiple records in one exchange are rejected.
Android GATT attachment, device-information values and pump firmware compatibility
require testing on actual devices, not just a successful build.

## Privacy and licensing

Readings stay in memory and are cleared when monitoring stops. The phone blocks
screenshots/recent-app previews while monitoring. Pairing addresses are encrypted
with an Android Keystore AES-GCM key; backup/device transfer is disabled.
Notifications and application logs exclude glucose and device identifiers.

The local watch link is unencrypted and unauthenticated. No internet permission,
cloud endpoint or measurement database is used. Do not publish raw Bluetooth
captures, pairing keys or health data. See [third-party notices](../THIRD_PARTY_NOTICES.md).
