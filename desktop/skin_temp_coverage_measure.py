#!/usr/bin/env python3
"""Measure the nightly skin-temperature COVERAGE distribution over real stored exports.

This is the reproducible basis for `SkinTempBaseline.minNightlyCoverage` (the gate that
withholds a night whose temperature samples are too clustered to represent it). That
constant's doc comment used to say, verbatim:

    To enable: run `coverage` over ~14 real stored nights per tester to get the actual
    distribution, pick the threshold from it …

so this script IS that instruction, committed rather than done by hand. It reimplements
`SkinTempBaseline.coverage` byte-faithfully (see `coverage()` below) and reports, per RING:

  * every night carrying in-window `.temperature` samples, with its coverage and sample count
  * which nights the COUNT floor (`minNightlySamples` = 10) already withholds
  * which nights the COVERAGE gate withholds that the count floor did NOT — i.e. this gate's
    true marginal effect, which is the only number that describes what enabling it changes
  * surviving nights per ring, against `minBaselineNights` = 3 — the cascade check, which is a
    PER-STORE quantity (each phone has its own store), so a pooled total is the wrong unit

stdlib only, per the repo convention for desktop tooling (no numpy/pandas).

Usage:
    python3 desktop/skin_temp_coverage_measure.py ~/Downloads/opencircuit-export-*.json \
                                                  ~/Downloads/opencircuit-export-*.csv

⚠️ Exports hold real health data. Point this at files OUTSIDE the repo; never commit them
(CLAUDE.md: commit decoded findings only, never raw captures).
"""

import csv
import datetime
import glob
import io
import json
import math
import statistics
import sys
from collections import defaultdict

# Mirrors of the Swift constants under test (OpenCircuitKit/Analytics/SkinTempBaseline.swift).
MIN_NIGHTLY_SAMPLES = 10
MIN_BASELINE_NIGHTS = 3
CANDIDATE_COVERAGE = 0.6


def parse_time(s):
    if not s:
        return None
    try:
        return datetime.datetime.fromisoformat(str(s).replace("Z", "+00:00"))
    except ValueError:
        return None


def coverage(times, start, end):
    """EXACT reimplementation of SkinTempBaseline.coverage(samples:in:).

        let buckets = max(1, Int((window.duration / 3600).rounded(.up)))
        let hit = Set(samples.filter { window.contains($0.time) }
                             .map { Int($0.time.timeIntervalSince(window.start) / 3600) })
        return Double(hit.count) / Double(buckets)

    `DateInterval.contains` is [start, end] inclusive of both ends.
    """
    duration = (end - start).total_seconds()
    buckets = max(1, math.ceil(duration / 3600))
    hit = {int((t - start).total_seconds() // 3600) for t in times if start <= t <= end}
    return len(hit) / buckets


def evidence_ring(evidence):
    """The ring a capture is attributed to, per the export's OWN schema note (topic `ringIdentity`):

        "historySyncEvidence[].ringID is the only per-capture ring attribution in this file."

    `meta.ring*` describes only the LAST ring the app connected to, and the six pre-schema-3 exports
    carry no meta at all — but every export measured here carries exactly one distinct `ringID`
    across all of its evidence rows, and it agrees with `meta.ringIdentifier` on every export that
    has both. So this recovers attribution for the meta-less exports.

    ⚠️ Still per-FILE, not per-NIGHT. The same schema note warns that on an install which has paired
    more than one ring, an older night may have come from a different ring than the one named. Treat
    the per-ring decomposition below as a good approximation, not a proof of per-night provenance.
    """
    ids = {r.get("ringID")[:8] for r in evidence or [] if r.get("ringID")}
    return next(iter(ids)) if len(ids) == 1 else None


def blocks_of_csv(text):
    """Split a multi-table OpenCircuit CSV export on blank lines."""
    out, cur = [], []
    for line in text.split("\n"):
        if line.strip() == "":
            if cur:
                out.append(cur)
                cur = []
        else:
            cur.append(line)
    if cur:
        out.append(cur)
    return out


def read_export(path):
    """-> (meta dict, temperature samples [(time, celsius)], nights [dict]) for CSV or JSON."""
    if path.endswith(".json"):
        d = json.load(open(path))
        meta = d.get("meta", {}) or {}
        temps = [
            (parse_time(s.get("start")), s.get("value"))
            for s in d.get("samples", [])
            if s.get("kind") == "temperature"
        ]
        nights = d.get("sleep", []) or []
        meta = dict(meta)
        meta.setdefault("ringIdentifier", evidence_ring(d.get("historySyncEvidence", [])) or "")
        return meta, [(t, v) for t, v in temps if t is not None], nights

    blocks = blocks_of_csv(open(path).read())
    meta, temps, nights = {}, [], []
    for b in blocks:
        header = b[0]
        rows = list(csv.DictReader(io.StringIO("\n".join(b))))
        if header.startswith("kind,start,end,value"):
            temps = [
                (parse_time(r["start"]), float(r["value"]))
                for r in rows
                if r.get("kind") == "temperature"
            ]
        elif header.startswith("field,value"):
            meta = {r["field"]: r["value"] for r in rows}
        elif header.startswith("night,asleepMin"):
            nights = rows
        elif header.startswith("capturedAt,ringID"):
            meta.setdefault("ringIdentifier", evidence_ring(rows) or "")
    return meta, [(t, v) for t, v in temps if t is not None], nights


def main(paths):
    # Dedupe across exports: the same night is re-exported by later dumps. Key on the ring plus
    # the night's own identity, so two files describing one night count ONCE. Six of the JSON exports
    # predate schema 3 and carry no `meta`, but `evidence_ring` recovers their ring from
    # `historySyncEvidence[].ringID`, so they dedupe against the schema-3 exports of the same ring
    # rather than pooling into an "unknown" bucket. Their ring MODEL/FIRMWARE stays unknown — the
    # pre-schema-3 export simply does not record it.
    seen = {}
    excluded = set()
    for path in paths:
        try:
            meta, temps, nights = read_export(path)
        except Exception as exc:  # noqa: BLE001 - a malformed export should not abort the sweep
            print("  !! skipped %s: %s" % (path.split("/")[-1], exc), file=sys.stderr)
            continue
        ring = (meta.get("ringIdentifier") or "unknown")[:8]
        model = meta.get("ringModel", "?")
        fw = meta.get("ringFirmware", "?")
        temps.sort()
        for n in nights:
            a, b = parse_time(n.get("inBedStart")), parse_time(n.get("inBedEnd"))
            if not a or not b or b <= a:
                continue
            in_window = [(t, float(v)) for t, v in temps if a <= t <= b]
            if not in_window:
                # EXCLUDED, and legitimately so: with no readings at all the verdict is
                # `.notMeasured` both before and after the gate, so such a night cannot contribute
                # to a delta. Counted and reported rather than silently dropped, because a bare
                # night total cannot tell "excluded" from "never existed" — and the original cascade
                # objection was phrased in exactly those terms ("7 of 14 nights with no temperature").
                excluded.add((ring, a.isoformat(), b.isoformat()))
                continue
            key = (ring, a.isoformat(), b.isoformat())
            if key in seen:
                continue
            seen[key] = dict(
                ring=ring,
                model=model,
                fw=fw,
                src=path.split("/")[-1],
                night=str(n.get("night"))[:10],
                start=a,
                end=b,
                n=len(in_window),
                cov=coverage([t for t, _ in in_window], a, b),
                mean=statistics.mean(v for _, v in in_window),
                stored=n.get("skinTempC"),
            )

    rows = sorted(seen.values(), key=lambda r: (r["ring"], r["start"]))
    print("%-9s %-17s %-11s %-11s %5s %7s %7s  %s"
          % ("ring", "model/fw", "night", "src", "n", "cov", "mean", "verdict"))
    print("-" * 104)
    for r in rows:
        by_count = r["n"] < MIN_NIGHTLY_SAMPLES
        by_cov = r["cov"] < CANDIDATE_COVERAGE
        if by_count and by_cov:
            v = "withheld ALREADY (count floor n<%d)" % MIN_NIGHTLY_SAMPLES
        elif by_count:
            v = "withheld ALREADY (count floor)"
        elif by_cov:
            v = "*** NEWLY WITHHELD by coverage gate ***"
        else:
            v = "published"
        print("%-9s %-17s %-11s %-11s %5d %7.4f %7.2f  %s"
              % (r["ring"], (r["model"] + " " + r["fw"])[:17], r["night"], r["src"][19:29],
                 r["n"], r["cov"], r["mean"], v))

    print("\n%d distinct nights with in-window temperature samples, across %d rings"
          % (len(rows), len({r["ring"] for r in rows})))
    print("%d further distinct nights carried NO in-window readings and are excluded "
          "(`.notMeasured` before and after, so they cannot move the delta); %d nights in total."
          % (len(excluded), len(rows) + len(excluded)))
    per_gen = {}
    for r in rows:
        per_gen.setdefault("%s %s" % (r["model"], r["fw"]), set()).add(r["ring"])
    for gen, rings in sorted(per_gen.items()):
        n = sum(1 for r in rows if "%s %s" % (r["model"], r["fw"]) == gen)
        print("    %-24s %2d nights across %d ring(s): %s"
              % (gen, n, len(rings), ", ".join(sorted(rings))))

    covs = sorted(r["cov"] for r in rows)
    fail = [r for r in rows if r["cov"] < CANDIDATE_COVERAGE]
    ok = [r for r in rows if r["cov"] >= CANDIDATE_COVERAGE]
    print("\nCOVERAGE DISTRIBUTION  min %.4f  median %.4f  max %.4f" % (covs[0], statistics.median(covs), covs[-1]))
    if fail and ok:
        lo, hi = max(r["cov"] for r in fail), min(r["cov"] for r in ok)
        inside = [r for r in rows if lo < r["cov"] < hi]
        print("  best failing night %.4f   worst passing night %.4f" % (lo, hi))
        print("  nights inside the gap: %d %s" % (len(inside), "(EMPTY — the threshold is not tuned)" if not inside else "!!"))
        print("  candidate %.2f lies inside that gap: %s" % (CANDIDATE_COVERAGE, lo < CANDIDATE_COVERAGE < hi))

    # THE MARGINAL EFFECT — the only figure that describes what enabling the gate changes.
    newly = [r for r in rows if r["cov"] < CANDIDATE_COVERAGE and r["n"] >= MIN_NIGHTLY_SAMPLES]
    already = [r for r in fail if r["n"] < MIN_NIGHTLY_SAMPLES]
    print("\nMARGINAL EFFECT OF THE COVERAGE GATE")
    print("  nights below the candidate coverage : %d" % len(fail))
    print("   … of those, already withheld by the count floor (n < %d) : %d" % (MIN_NIGHTLY_SAMPLES, len(already)))
    print("   … NEWLY withheld by this gate alone                     : %d of %d (%.1f%%)"
          % (len(newly), len(rows), 100 * len(newly) / len(rows)))
    for r in newly:
        print("        %s  %s  n=%d cov=%.4f mean=%.2f  (%s %s)"
              % (r["ring"], r["night"], r["n"], r["cov"], r["mean"], r["model"], r["fw"]))

    # CASCADE CHECK — per ring, because the baseline is read from ONE phone's store.
    print("\nCASCADE CHECK (per ring — the baseline is a per-store quantity; minBaselineNights = %d)"
          % MIN_BASELINE_NIGHTS)
    per = defaultdict(list)
    for r in rows:
        per[r["ring"]].append(r)
    for ring, rs in sorted(per.items()):
        surv_after = [r for r in rs if r["cov"] >= CANDIDATE_COVERAGE and r["n"] >= MIN_NIGHTLY_SAMPLES]
        surv_before = [r for r in rs if r["n"] >= MIN_NIGHTLY_SAMPLES]
        delta = len(surv_before) - len(surv_after)
        flag = ""
        if len(surv_after) < MIN_BASELINE_NIGHTS:
            flag = "  <-- BELOW the baseline floor%s" % (
                " (ALREADY was, before this change)" if len(surv_before) < MIN_BASELINE_NIGHTS else " — CAUSED BY THIS CHANGE")
        print("  %-9s %-17s %2d nights  survive: %2d before -> %2d after  (delta %d)%s"
              % (ring, (rs[0]["model"] + " " + rs[0]["fw"])[:17], len(rs),
                 len(surv_before), len(surv_after), delta, flag))


if __name__ == "__main__":
    args = sys.argv[1:]
    paths = []
    for a in args:
        paths.extend(sorted(glob.glob(a)) if any(c in a for c in "*?[") else [a])
    if not paths:
        print(__doc__)
        sys.exit(1)
    main(paths)
