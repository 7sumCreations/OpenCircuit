# Metric → Android Health Connect mapping

The Android app (from `android-v0.3.0`) writes the ring's data to **Health Connect** on the phone.
This is the Android counterpart of [`HEALTHKIT_MAPPING.md`](HEALTHKIT_MAPPING.md). Nothing leaves
the phone: the app has no internet permission, and Health Connect is part of Android 14 and later
(Settings → Security & privacy → Privacy → Health Connect; on GrapheneOS it is built in and needs
no Google Play services).

The app asks only for the **12 write permissions** below. It never asks to read anything, including
other apps' data, and it reads no health data back out of Health Connect. (A write permission does let
an app read back the records it wrote itself — that is the only read the app's code could ever make,
and it is used only by the app's own on-device tests.)

## Ring metrics

| Metric | Health Connect record | Unit | One record per | Id |
|---|---|---|---|---|
| Heart rate | `HeartRateRecord` | beats/min | UTC clock hour (all of the hour's readings as samples) | `oc:hr:<hour start, epoch ms>` |
| Resting heart rate | `RestingHeartRateRecord` | beats/min | local day (derived on the phone from the stored nights) | `oc:rhr:<yyyy-mm-dd>` |
| HRV | `HeartRateVariabilityRmssdRecord` | ms | reading | `oc:hrv:<epoch ms>:<n>` |
| Blood oxygen (SpO₂) | `OxygenSaturationRecord` | % | reading | `oc:spo2:<epoch ms>:<n>` |
| Respiratory rate | `RespiratoryRateRecord` | breaths/min | reading | `oc:rr:<epoch ms>:<n>` |
| Skin temperature | `SkinTemperatureRecord` (location: finger) | °C delta from a baseline | night | `oc:skin:<night>` |
| Steps | `StepsRecord` | count | stored step reading, over its own window | `oc:steps:<start, epoch ms>` |
| Distance | `DistanceRecord` | m (estimated, 0.248 m per step) | steps record | `oc:dist:<start, epoch ms>` |
| Active calories | `ActiveCaloriesBurnedRecord` | kcal | 15-minute bucket from local midnight | `oc:ae:<bucket start, epoch ms>` |
| Basal metabolic rate | `BasalMetabolicRateRecord` | kcal/day | local day | `oc:bmr:<yyyy-mm-dd>` |
| Sleep (nights) | `SleepSessionRecord` with stages | — | night | `oc:sleep:<night>` |
| Naps | `SleepSessionRecord` | — | nap | `oc:nap:<start, epoch ms>` |

`<n>` counts readings of one kind that share a start (usually 0), so two readings at one instant
are both kept.

**Every ring record** carries `recordingMethod = automatically recorded` and a device of type
**ring**, manufacturer `RingConn`, model `Gen 2`, `Gen 2 Air` or `Gen 3` (from the firmware
prefix; `Ring` when unknown). There is no custom metadata: Health Connect has none.

**Sleep stages.** The session spans the night's in-bed time. Deep → `DEEP`, REM → `REM`, awake →
`AWAKE`; core → `LIGHT` on a night the ring staged, `SLEEPING` on a night only motion could tell
(it says "asleep", not which stage); in-bed time no stage covers → `AWAKE_IN_BED`. Stages never
overlap. A span you entered yourself is named in the session's **notes** as local times (Health
Connect has no per-stage "entered by user" flag); a nap you added is a manual-entry record.

**Skin temperature.** Written only on phones whose Health Connect offers it (Android 15 and later
in practice; the card says "Skin temperature · Not supported on this phone" otherwise, and the
readings wait). A night is written once it has a baseline: the mean of at least 3 earlier nights.

## Logged by you

| Entry | Health Connect record | Id |
|---|---|---|
| Period day | `MenstruationFlowRecord` (one per day, at local midnight; manual entry) | `oc:flow:<yyyy-mm-dd>` |
| Period | `MenstruationPeriodRecord` (manual entry; a logged period longer than 31 days is split, Health Connect's limit) | `oc:period:<first day>` |

The app has no screen for logging these yet; the writer is ready for the epics that add them.

## What is not written, and why

- **Headache.** Health Connect has no headache type. Headaches stay in the app.
- **Hourly basal energy.** iOS writes basal energy per hour; Health Connect has a daily basal
  metabolic rate instead, so one record per day.
- **Per-stage "entered by user" and custom metadata** (iOS `OpenCircuit*` keys). Health Connect has
  neither; user-entered sleep spans are named in the session notes, and estimates (distance,
  calories) are documented here instead.
- **Workouts / exercise sessions** come with the workout epic; **blood pressure** with its own epic.

## How writing works

- **When.** After every sync that stored something, and when you tap **Write to Health Connect
  now** on the Ring screen. One write runs at a time; a tap during a write makes one more write
  after it.
- **Nothing twice.** Every record has a fixed id (above) and a version that only grows, so writing
  the same item again replaces it. A night that is re-staged or edited replaces its own record.
- **Bounded.** At most 1,000 records and about 4 MB per call, and at most 300 calls per write. If
  Health Connect asks the app to slow down, the card says "Catching up" and the rest goes on the
  next sync. Nothing is lost: the app only marks what Health Connect accepted.
- **Platform limits.** A reading outside Health Connect's allowed range is left out and counted,
  never changed to fit.
- **Not allowed types.** A type you did not allow is skipped, not failed. The card names it in
  amber ("Not allowed: …") with **Change in Health Connect**.
- **Failures.** A type whose write fails is named in amber ("Could not write …") and retried on
  the next sync.
- **Your deletions.** If you delete OpenCircuit's records in Health Connect, the app does not put
  them back unless that item changes (a night re-staged, a new reading in that hour). It never
  reads Health Connect to check.
- **Retention.** The app removes raw readings older than 30 days, but keeps any that Health Connect
  has not received yet.

## Steps, skin temperature and "live only"

Steps and skin temperature are not in the ring's history: the ring sends them only while it is
connected. The app records them only for the time the ring was connected, so a ring that syncs and
disconnects has none for the time in between. Turn **Disconnect after syncing** off to stay connected
while the app is running. A **Measure** is stored the same way: its settled heart rate, or its last
SpO₂, is kept when you stop the reading (a heart rate that never settled is not kept) and written as
its ordinary record type. Every live reading is stored as it arrives, outside the sync's history
position, so closing the app does not lose it and a later sync still brings the ring's older backlog.

## Steps from several apps — Health Connect's app priority

For steps, distance, calories and sleep, Health Connect does **not** add up overlapping data from
different apps. It keeps the data of the app highest in its priority list. To choose which app
counts: Health Connect → Data and access → (the type, e.g. Steps) → **App priority**. Health
Connect also keeps only the newest of two overlapping records from the same app, which is why the
app writes each 15-minute calorie bucket as a whole value rather than adding to it.

## Permissions, settings and uninstalling

- The Ring screen's **Health Connect** card asks for permission only when you tap **Connect Health
  Connect**, never at launch. If you allowed nothing, the card offers **Open Health Connect
  settings** (Health Connect's own page for OpenCircuit) instead of asking again.
- **The "why does this app want access" page.** Android 14 and later ask an app to explain its health
  permissions before granting any, and refuse them all to an app that cannot show one. OpenCircuit
  answers with a static page that fetches nothing (it has no internet permission): it writes, it never
  reads other apps' data, nothing leaves the phone. The page is declared in the manifest as an alias
  only the system may open — and an update that drops that alias revokes every health permission the
  app holds, which is why it is guarded by a test.
- **Health Connect is not available** on this card means Android reports it missing or disabled,
  or the app runs in a work profile or Private Space, where Health Connect is not offered.
- **Turning a type off** in Health Connect stops new writes of that type. The app can then no
  longer replace or delete the records it wrote earlier either, because Health Connect allows an
  app to change its own records only while it holds that type's write permission. Delete them in
  Health Connect → Data and access → OpenCircuit, or turn the type back on first.
- **Uninstalling** OpenCircuit leaves its records in Health Connect. Delete them in Health Connect
  → Data and access → OpenCircuit before uninstalling if you want them gone.
