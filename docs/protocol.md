# Phone-to-watch BLE protocol v2

GlucoStride sends validated pump readings from Android to the Suunto receiver.
This custom profile is **unencrypted and unauthenticated**; UUIDs, sample IDs and
sequence checks do not provide confidentiality or protection against impersonation.

## Transport and packet format

| Item | Value |
| --- | --- |
| Primary service | `7b9e2000-6d8b-4f3a-9c21-2e8a6f0d5b47` |
| Read + notify characteristic | `7b9e2001-6d8b-4f3a-9c21-2e8a6f0d5b47` |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` |
| Subscription / disable | CCCD bytes `01 00` / `00 00` |
| Payload | Exactly 20 bytes, little-endian |
| Heartbeat | 5 seconds |
| Source expiry / receiver silence | 600 seconds / 20 seconds |

Advertising contains the service UUID and platform flags, not glucose or pump
identifiers. The watch matches AD type 6 or 7, registers the full little-endian
UUIDs, enables notifications and performs one initial read. It issues no
application characteristic writes; subscribing uses the normal CCCD write.

| Offset | Type | Meaning |
| --- | --- | --- |
| 0 | uint8 | Version, exactly `2` |
| 1 | uint8 | Flags, exactly `2` (not a security flag) |
| 2 | uint8 | Status: `0 OK`, `1 UNAVAILABLE`, `2 WARMUP`, `3 SENSOR_ERROR`, `4 TIME_UNKNOWN`, `5 STALE` |
| 3 | uint8 | Reserved, exactly `0` |
| 4-5 | uint16 | Glucose, integer mg/dL; `0xffff` unavailable |
| 6-7 | int16 | Trend, hundredths mg/dL/min; `-32768` unknown |
| 8-11 | uint32 | Source age, seconds; `0xffffffff` unknown |
| 12-15 | uint32 | Source measurement UNIX epoch seconds; `0` unknown |
| 16-19 | uint32 | Packet sequence, modulo 2^32 |

Glucose is rounded to integer mg/dL and trend to hundredths of mg/dL/min.
OK requires glucose `1..65534`, known age below 600 and a nonzero sample ID.
Unknown trend is allowed. Every non-OK state carries both glucose and trend
sentinels. Invalid/out-of-range source values become SENSOR_ERROR. Malformed
lengths, non-byte input, unsupported versions/flags, statuses or contradictory
fields are rejected as `BAD PACKET`.

Synthetic test vector:

```text
02 02 00 00 7e 00 32 00 1e 00 00 00 07 00 00 00 09 00 00 00
```

This means OK, 126 mg/dL, +0.50 mg/dL/min, age 30 seconds, sample ID 7,
sequence 9. Sample ID 7 is a fixture, not a current timestamp.

## Freshness and identity

- Only the authenticated reader's validated snapshot is eligible. Missing data,
  pending authentication, pump disconnect or no completed record for 90 seconds
  produces unavailable. See [source-time validation](pump-reader.md#reading-and-source-time).
- The phone validates source time; the watch does not compare the sample epoch
  with its own UTC. It adds guarded local elapsed time to the supplied age.
- At source age **>=600 seconds**, glucose and trend are hidden. Without a newly
  accepted packet for **>=20 seconds**, they are hidden even during pause/resume.
  Screen publication follows the SDK's approximately one-second cadence.
- Repeated samples retain at least their previous age plus elapsed time,
  including fractions, through outages and reconnects. Once an OK sample's
  glucose/trend is known, neither may change for that ID, including changes to
  or from unknown trend.
- Non-OK sentinels hide values without overwriting known-good sample data.
  Sample-zero packets do not erase source history. Nonzero IDs older than the
  latest accepted nonzero ID are rejected; a newer ID establishes new ordering,
  even if its status is unavailable.

## Sequence and reconnect

The counter advances per emitted packet, including reads and notifications.
A disconnected-to-connected transition permits **one accepted packet** to
establish a new sequence baseline, including for the same sample or a sample-zero
unavailable packet. Malformed/history-violating packets do not consume this
opportunity. Sample age, immutable values and epoch ordering remain enforced.

After that, unsigned wrap-aware ordering rejects duplicates, older counters and
ambiguous half-range jumps. They cannot renew the heartbeat or repair `BAD PACKET`;
`0xffffffff` to `0` is accepted. A repeated connected callback, changed sample or
heartbeat timeout alone does not authorize another rebase.

History is bounded and in-memory only. It survives radio reconnects, not watch-app
destruction/reselection. These rules address delivery faults, not hostile senders.
See [watch clock guards](../suunto/LIVE.md#clock-diagnostics) and
[peer lifecycle](pump-reader.md#pairing-and-lifecycle).
