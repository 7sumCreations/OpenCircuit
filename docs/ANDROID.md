# OpenCircuit for Android — developer notes

The Android port lives in [`android/`](../android). Start with its
[`README.md`](../android/README.md) (what it is, how to build and test) and
[`PORTING.md`](../android/PORTING.md) (which upstream file each Kotlin file comes from, and every
deliberate difference). This page collects the notes about the Android code that the rest of the
project should know.

## The on-device store (`:store`)

Everything the app keeps lives in one SQLite database on the phone, opened through Room with the
bundled SQLite driver (the same SQLite build on the phone and in the JVM tests). It never leaves
the phone.

**What is stored.** Schema version 1 has eleven tables:

- the ring's raw samples (heart rate, HRV, SpO₂, temperature, steps and the rest), kept for 30
  days, and the per-metric sync position, which is written in the same transaction as the samples
  so a sync that fails part-way stores nothing and is simply repeated;
- daily step totals and their step samples, and daytime skin-temperature readings;
- sleep summaries and naps, including the edits a person makes to a night or a nap and naps they
  add by hand. A night is filed under the day it ends; a later sync replaces it only with a fuller
  staging and never overwrites a person's edit (only the edit's allowed window widens). An edit
  writes the night, its undo history and its edited sleep onset in one transaction, so a failure
  leaves all three as they were. A nap the ring detects never replaces one a person added or
  edited, and the automatic naps a saved night covers are removed in the night's own transaction;
- what a person logs: periods and headaches (an edit that only changes the notes keeps the entry's
  health-store state; a moved entry keeps its health-store sample ids), and the frozen daily
  headache-risk rows, written once per day;
- small stored values in one key-value table: the sync and alert ledgers, the ring alarm, workout
  recovery state, the energy ledger and the sleep epoch archive. A value that cannot be read loads
  as empty or unknown, never as a wrong value; an alarm that cannot be read is kept as stored until
  the person saves a new one.

The store deletes only raw ring data on its own: samples, step samples and daytime readings older
than 30 days, plus a one-time clean-up of raw samples that fail the import checks (heart rates
outside 30–220 bpm, and times before the ring's clock epoch or more than a day ahead). Nothing a
person entered is pruned. At launch the store also repairs sleep history: once, it moves nights
an older build filed under the day they started onto the day they ended — with everything kept
under the night's key, in one transaction (two nights that would land on one day stop the move and
change nothing); it fills in the measured-versus-estimated breakdown of unedited nights that lack
it (a night with no timeline stays unknown); and it restores sleep scores an edit had withheld.
Each step's outcome is reported, and a failing step does not stop the next.

**No destructive fallback, ever.** The store is never deleted or recreated to get past a problem.
If the database cannot be opened (a damaged file, a schema with no migration path, a newer schema
than the app knows), the open fails in front of the caller and the file is left exactly as it was.
No source file or build script in `android/` may use Room's destructive-migration fallback; a test
scans the whole tree for it.

**Changing the schema.** A change to what is stored is a new schema version with its own exported
JSON under `android/store/schemas/`, a migration, and a test in `StoreMigrationTest` named after
the version (`version2…`) that creates the old version from its JSON, fills it with realistic and
hand-made rows, reopens it through the app's own open path and checks that every row is still
there with every value unchanged. Guard tests fail when a committed version has no such test, when
the database version is not the highest committed one, and when a released schema file changes:
each released file is pinned by its SHA-256 in `android/store/schemas/RELEASED.sha256`. Until the
first GitHub Release, version 1 may still be re-pinned.

**Gate A.** Before shipping any schema change, run the migration test class on its own and check
that every test it declares actually ran:

```sh
cd android
scripts/store-gate-a.sh
```

It must end with `GATE A: declared N, executed N, failed 0 — PASS`. Any other line (a failure, a
skipped test, or a test that never ran) is a `FAIL` with exit status 1, and the change does not
ship. The count matters because a test can drop out without failing: JUnit 5 silently ignores a
test function that returns a value, and Gradle reports a skipped test as a success.
