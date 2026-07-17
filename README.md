# KU Women's Basketball

An Android app for following the Kansas Jayhawks Women's Basketball team:
full game results with quarter scores, per-player box scores, season
aggregates, and team leaderboards — automatically updated throughout the
season. Modeled on the
[ku-volleyball](https://github.com/inspectorgad/ku-volleyball) app and
seeded with the complete 2025-26 season (36 games, WBIT finalists).

## Features

- **Roster** — the Jayhawks roster (name, jersey number, position) with
  each player's career line at a glance, split into current and former
  players (former players keep their stats but sit in their own section).
  Tap a player for per-season aggregates, career totals, and a full game log.
- **Games** — every game with result and quarter-by-quarter scores. Inside
  a game, every player's full stat line (MIN, FG, 3PT, FT, REB, AST, TO,
  STL, BLK, PF, PTS). Games and lines can also be added or edited by hand.
- **Leaders** — filter by season (or all-time) to see the team's record,
  scoring margins, team shooting, and leaderboards for points, rebounds,
  assists, steals, and blocks per game, plus FG%, FT%, and threes made.

Derived stats (shooting percentages, per-game averages) are computed
automatically from the raw counting stats.

All data is stored locally on the device in a Room (SQLite) database.

## Data pipeline

1. `scripts/scrape-ku-wbb.mjs` (GitHub Actions, nightly) discovers KU
   games via the [NCAA API](https://github.com/henrygd/ncaa-api) daily
   scoreboard, captures each finished game's official box score, and pulls
   the current roster + upcoming schedule from kuathletics.com. The roster
   scrape is also what flags departed players as "former."
2. `scripts/update-seed.py` regenerates `app/src/main/assets/seed.json`
   from the scraped data.
3. A seed change triggers the APK build workflow, which publishes the APK
   and `season-data.json` to the rolling `latest-apk` release.
4. On launch (and via pull-to-refresh) the app downloads `season-data.json`
   and merges it — gap-filling only, never overwriting user-entered data.

Install the latest build directly on a phone:
`https://github.com/inspectorgad/ku-wbb/releases/latest/download/app-debug.apk`

### If Advanced Protection blocks the install

Android's Advanced Protection mode blocks APKs downloaded in the browser but
allows installs from a computer over ADB:

1. On the phone: Settings → About phone → tap **Build number** 7 times, then
   Settings → System → Developer options → enable **USB debugging**.
2. On the computer: install
   [Android platform-tools](https://developer.android.com/tools/releases/platform-tools)
   (macOS: `brew install android-platform-tools`).
3. Plug the phone in, accept the "Allow USB debugging?" prompt, and run
   `scripts/adb-install.sh` (Mac/Linux) or `scripts\adb-install.bat` (Windows).

The scripts download the latest release APK and run `adb install -r`, which
keeps the app's data on upgrades. Turning USB debugging back off afterward is
fine — it's only needed while installing.

## Tech

- Kotlin + Jetpack Compose (Material 3), KU crimson & blue theme
- Room for persistence
- Pure-Kotlin basketball stats engine in `app/src/main/java/com/example/stats/`,
  covered by unit tests in `app/src/test/`

## Data validation

The 2025-26 source data was validated before the app was built — see
[DATA-VALIDATION.md](DATA-VALIDATION.md). Raw evidence lives in `probe/`.

## Run Locally

**Prerequisites:** [Android Studio](https://developer.android.com/studio)

1. Open Android Studio
2. Select **Open** and choose the directory containing this project
3. Allow Android Studio to fix any incompatibilities as it imports the project
4. Run the app on an emulator or physical device
