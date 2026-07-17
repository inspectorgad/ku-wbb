# KU Women's Basketball

An app for following the Kansas Jayhawks Women's Basketball team — games,
results, and per-player box-score stats for the 2025-26 season. Modeled on
the [ku-volleyball](https://github.com/inspectorgad/ku-volleyball) app.

## Status: data validation

Before any app code, we are validating that the season data can be
retrieved and is internally consistent:

- `scripts/probe-ku-wbb.mjs` — runs in GitHub Actions (`probe-data.yml`),
  scans the NCAA scoreboard for every Kansas game of 2025-26, captures each
  final game's official box score, cross-checks player point totals against
  final scores, and probes the kuathletics.com roster page.
- Raw evidence and a validation summary land in `probe/`.
