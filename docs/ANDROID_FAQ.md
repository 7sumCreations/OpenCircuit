# OpenCircuit for Android — questions and answers

Last updated: 0.2.1 — 2026-10-16

Not affiliated with RingConn · not a medical device · no account, no cloud

This page says plainly what the Android app does today and what it does not. For install steps and
the details of each screen, see [`ANDROID.md`](ANDROID.md).

## What it can do today / What it can't do yet

| | What |
|---|---|
| Can | Find and pair a RingConn Gen 2, Gen 2 Air or Gen 3 over Bluetooth, and reconnect to it |
| Can | Show the ring's battery, charging state and connection details |
| Can | Take a live heart-rate or blood-oxygen reading (Measure) |
| Can | Sync the ring's history onto the phone (from 0.2.0): heart rate, HRV, blood oxygen, breathing and each night's sleep |
| Can | Sync by itself when the ring connects while the app is open, and every hour while it stays connected (with "Disconnect after syncing" off) |
| Can | Show when you last synced, the date range and nights stored, and warn when the ring has gone too long without a sync |
| Can | Keep older Gen 2 firmware's history safely on the phone |
| Can't yet | Read the history of rings on older firmware (seen on FR02.013). It is stored, not readable yet: update the ring's firmware in the RingConn app |
| Can't yet | Write anything to Health Connect |
| Can't yet | Sync in the background. Keep the app open while it syncs; leaving it pauses the sync until you come back |
| Can't yet | Record steps or skin temperature while the ring is disconnected. They are only recorded while connected |
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

**Can it write to Health Connect?** Not yet.

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
three), and syncs once you are up. The very first sync runs at any hour. With "Disconnect after
syncing" off, the ring stays connected and syncs about every hour. All of this only happens while
the app is running. When an automatic sync is skipped, Connection details says why under
"Auto-sync skipped".

**What does "Disconnect after syncing" do?** With it on (the default), the app closes the
connection once the data is saved, so you can sync once a day or every few days without keeping
the ring connected. It does not leave an idle connection after a skipped automatic sync, and it
never closes a connection you started yourself (Scan & connect, Try again, Sync now) or one a
live measurement is using. With it off, the ring stays connected and syncs about every hour, and
steps and skin temperature keep being recorded.

**Where is my data?** Only on your phone, in the app's own storage. It is excluded from cloud
backup and device-to-device transfer. Raw ring readings older than 30 days are removed; nothing
you entered yourself is.

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

**Why does the card warn that I haven't synced?** A ring keeps only a few days of history: a
Gen 2 or Gen 2 Air about 7 days, a Gen 3 about 10. The card turns amber at 4 days (Gen 3: 7) and
red at 5 days (Gen 3: 9) since the oldest record the ring may still hold. Sync before then so
nothing is overwritten.
