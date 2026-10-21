# OpenCircuit for Android — questions and answers

Last updated: the code after 0.3.0, heading for 0.4.0 — 2026-10-22

Not affiliated with RingConn · not a medical device · no account, no cloud

This page says plainly what the Android app does today and what it does not. For install steps and
the details of each screen, see [`ANDROID.md`](ANDROID.md).

## What it can do today / What it can't do yet

| | What |
|---|---|
| Can | Open on a Today screen (from 0.4.0): your latest heart rate and SpO₂ with their age, a readiness score with one line on how today compares with your usual, and eight tiles — heart rate, HRV, resting heart rate, SpO₂, breathing rate, skin temperature, sleep and steps |
| Can | Turn Today's heart rate live by itself once a sync ends while Today is on screen (never during a sync or your night), and measure SpO₂ with one tap |
| Can | Open any tile's 14- or 30-day trend against your own usual range, and browse back through every day the app has stored, even while a sync runs |
| Can | Show each day's estimated calories and four goal rings (steps, active calories, active minutes, sleep), and let you reorder Today's sections and tiles (the order is kept) |
| Can | Keep every reading on the phone for good (from 0.4.0), and show how much space it takes |
| Can | Find and pair a RingConn Gen 2, Gen 2 Air or Gen 3 over Bluetooth, and reconnect to it |
| Can | Show the ring's battery, charging state and connection details |
| Can | Take a live heart-rate or blood-oxygen reading (Measure) |
| Can | Sync the ring's history onto the phone (from 0.2.0): heart rate, HRV, blood oxygen, breathing and each night's sleep |
| Can | Sync by itself when the ring connects while the app is open, and every hour while it stays connected (while the app is open, or after you leave it with "Disconnect after syncing" off) |
| Can | Show when you last synced (on Today and the Ring tab), the date range and nights stored, and warn in amber or red when the ring has gone too long without a sync |
| Can | Keep older Gen 2 firmware's history safely on the phone |
| Can't yet | Read the history of rings on older firmware (seen on FR02.013). It is stored, not readable yet: update the ring's firmware in the RingConn app |
| Can | Write to Health Connect on the phone (from 0.3.0): heart rate, resting heart rate, HRV, blood oxygen, breathing, skin temperature, steps, distance, active calories, basal metabolic rate and sleep, after every sync and on "Write to Health Connect now" — from 0.4.0 only once you turn on "Send to Health Connect" in Settings (it is off until you do) |
| Can | Write again without duplicates, skip types you did not allow, and retry a failed type on the next sync |
| Can | Keep what the ring and Measure find live — steps, skin temperature, a Measure's settled heart rate or last SpO₂ — on the phone as they arrive, so closing the app does not lose them, and write them to Health Connect |
| Can't yet | Sync in the background. Keep the app open while it syncs; leaving it pauses the sync until you come back |
| Can't yet | Record steps or skin temperature while the ring is disconnected. They are only recorded (and written to Health Connect) while connected |
| Can't yet | Write skin temperature on a phone whose Health Connect does not offer it (Android 14). The card says "Not supported on this phone" |
| Can't yet | Show sleep stages, workouts, strain or intraday charts: Today's sleep tile shows only last night's time asleep and score |
| Can't yet | Log periods, headaches or workouts in the app. Headaches have no Health Connect type and will never be written there |
| Can't | Read health data from Health Connect: it asks for write permissions only, and reads no data back out of Health Connect. Today shows only what the app itself stored |
| Can't | Work without Bluetooth, or on a phone older than Android 14 |
| Can't | Sync to any cloud, or use an account: there is none |

## Questions

**Does it need an account or the internet?** No. There is no account, no cloud and no server. The
app has no internet permission, so it cannot reach any server even by mistake.

**Is this the RingConn app?** No. It is an independent port of OpenCircuit and is not affiliated
with RingConn. It is not a medical device: its readings are estimates, not for diagnosis or
treatment.

**Which rings work?** RingConn Gen 2, Gen 2 Air and Gen 3, on Android 14 or later.

**Does it sync the ring's history?** Yes, from the 0.2.0 release: heart rate, HRV, blood oxygen,
breathing and every night's sleep are saved on the phone. Steps and skin temperature are only
recorded while the ring is connected.

**What is Health Connect?** The health store built into Android 14 and later, part of the system
(you can find it in Settings → Security & privacy → Privacy → Health Connect; on GrapheneOS it is
built in and needs no Google Play services). OpenCircuit writes your ring's data there and nowhere
else, and any app you allow to read Health Connect can then show it.

**Can it write to Health Connect?** Yes, from 0.3.0 — and from 0.4.0 only if you turn it on. Go
to Settings and turn on **Send to Health Connect**; the Health Connect card appears under it. Tap
**Connect Health Connect** on the card and choose what to allow. The app then writes after every
sync that stored something, and **Write to Health Connect now** writes at once. It asks only to
write, never to read. Which metric becomes which Health Connect record:
[`HEALTH_CONNECT_MAPPING.md`](HEALTH_CONNECT_MAPPING.md).

**Why did Health Connect writing turn off after I updated?** From 0.4.0 sending to Health Connect
is a switch, OFF on a fresh install and after updating from 0.3.0, so nothing goes there unless
you choose it. If you had connected Health Connect before, Today shows a one-time notice saying so,
with **Open Settings**. Turning the switch back on does not ask again for what you already allowed.
What was written before stays in Health Connect.

**What does the switch do while it is off?** Nothing reaches Health Connect: the app writes
nothing, reads nothing and shows no Health Connect prompt. Turning it off stops a write that is
under way, too. Your readings stay in the app either way, and Today never reads Health Connect.

**I allowed nothing. How do I change it?** The card then shows **Open Health Connect settings**,
which opens Health Connect's page for OpenCircuit. Turn on what it may write and come back: the
card updates.

**Health Connect asked why the app wants these permissions. What did it answer?** One short page that
is offline and has nothing to fetch: OpenCircuit writes, it never reads other apps' data, and nothing
leaves the phone. Android 14 and later refuse every health permission to an app that cannot show such
a page, so it is not a formality — without it nothing would be granted.

**What does "Not allowed: …" in amber mean?** Those types are off for OpenCircuit in Health
Connect; the rest is written. **Change in Health Connect** opens the page to turn them on.

**Why is skin temperature missing from Health Connect?** Three things have to be true, and until they
are nothing is written and nothing is wrong. Your phone's Health Connect must offer the type (Android
15 and later in practice — otherwise the card says "Skin temperature · Not supported on this phone");
the ring must have been connected overnight, since skin temperature comes only from the live readings;
and at least 3 earlier nights must give the night a baseline to measure against. It appears on its own
once all three hold.

**What does "Catching up" mean?** Health Connect asked the app to slow down. Nothing is lost:
the rest is written on the next sync.

**What is the difference between Sync now and Write to Health Connect now?** **Sync now** talks to the
ring: it downloads what the ring recorded since the last sync and saves it on the phone, and the write
follows on its own once the data is saved. **Write to Health Connect now** never touches the ring: it
writes what the phone already holds. Use it after you change what Health Connect may accept, when the
card says a write failed, or to check that writing again adds nothing.

**Why are Health Connect's steps not the ring's?** Where several apps write steps, Health Connect
counts the app highest in its priority list, not the sum. Change it in Health Connect → Data and
access → Steps → App priority.

**If I uninstall the app, is my Health Connect data deleted?** No. It stays in Health Connect.
Delete it there (Data and access → OpenCircuit) if you want it gone.

**If I delete OpenCircuit's records in Health Connect, does the app put them back?** No, unless
that item changes later (a night re-staged, a new reading in that hour).

**Where do I look in Health Connect to see what OpenCircuit wrote?** Health Connect → **Data and
access** → OpenCircuit. Its own setup banner lists apps that *read* Health Connect, so a write-only app
never appears there and the banner can even say "no compatible apps installed" while the data is being
written — that is Health Connect's quirk, not a failure. Any app you allow to read Health Connect
shows the same data.

**Does "Stop reconnecting" unpair the ring?** No. It forgets the ring in the app only; Android's
own pairing is kept, so pairing again later asks nothing.

**What does Sync now do?** It connects to the ring (if it is not already connected), downloads
everything the ring recorded since the last sync, saves it on the phone, and shows what it
stored. It works at any hour and ignores the automatic rules below.

**What does "N records waiting" mean?** The app saves a record only once every one of the ring's
history channels has been read past it, so nothing older is ever skipped. When a channel did not
answer, did not finish or was not reached, the newer records wait on the phone and are saved by
the next sync. Nothing is lost.

**How often does it sync by itself?** When the ring connects (opening the app reconnects it), it
syncs unless the last complete sync was under 5 minutes ago. It does not sync during your night:
the app learns your usual sleep window from the nights it has stored (21:30 to 10:00 until it has
three), and syncs once you are up. The very first sync runs at any hour. While the ring stays
connected — the app is open, or "Disconnect after syncing" is off — it syncs about every hour. All
of this only happens while the app is running. When an automatic sync is skipped, Connection details says why under
"Auto-sync skipped".

**What does "Disconnect after syncing" do?** With it on (the default), the ring stays connected
while the app is open — that is what lets Today's heart rate go live — and the connection closes
once you leave the app, after any sync that is running. So you can sync once a day or every few
days without keeping the ring connected. Once you have left, it does not leave an idle connection
after a skipped automatic sync, and it never closes a connection you started yourself (Scan &
connect, Try again, Sync now) or one a live measurement is using. With it off, the ring stays
connected after you leave too, syncs about every hour, and steps and skin temperature keep being
recorded while the app is running.

**Why is Today's heart rate live without me tapping anything?** Once a sync ends while Today is on
screen, the app starts a live heart-rate reading by itself, in 90-second measures one after another,
for as long as Today stays on screen. It never runs during a sync or your night, it stops when you
leave Today, and if the ring seems to be off your finger it waits longer between tries (up to 10
minutes). Tapping SpO₂ **Measure** takes over, and heart rate resumes after.

**Can I look at an earlier day?** Yes. Swipe Today sideways, tap a date in the strip, or use **Pick
a day**: every day the app has stored is there, and the **Today** chip brings you back. A sync can
run while you browse; data landing on another day marks that date with a dot and leaves you where
you are.

**Why does a tile say "Learning your usual"?** Each tile compares a value with your own usual range,
which needs a few days of readings first. Until then the tile counts the days it has.

**Where is my data?** Only on your phone, in the app's own storage. It is excluded from cloud
backup and device-to-device transfer. Every reading from the ring is kept for good: nothing is
removed because of its age, whether or not Health Connect has it, and nothing you entered yourself
is removed either. (Builds before 0.4.0 removed raw readings older than 30 days once Health Connect
had them; updating deletes nothing.) Connection details › Show all › Storage shows how much space
the app's data takes and how many readings it holds.

**Why should the RingConn app stay closed while I sync?** Both apps share one place in the ring's
history, and a ring hands each record over once. If the other app is syncing too, it can take the
records or hold the Bluetooth connection. Close it (force-stop it) before you sync here.

**A sync says "Couldn't reach the ring — try again". What do I do?** The app now waits up to 2
minutes for the ring to answer, because some rings only start talking on their own timer. Keep
the ring close to the phone, make sure the RingConn app is closed, and tap Sync now again. Open
Connection details and copy the Sync log if it keeps happening: that shows what each step did.

**"This ring's firmware sends history this version can't read yet". What is this?** Your ring is on
older firmware (seen on FR02.013), which sends its history in a format this version cannot read
yet. The pages are kept safely on your phone and nothing is lost. Update the ring's firmware in
the RingConn app, then sync again.

**Why does Today (or the Ring data card) warn that I haven't synced?** A ring holds only a limited
stretch of history, and how much a ring really keeps has not been measured yet. The warning turns
amber at 4 days (Gen 3: 7) and red at 5 days (Gen 3: 9) since the oldest record the ring may still
hold, with **Sync now**. Sync before then so nothing is lost.
