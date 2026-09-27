# KU Women's Basketball

An Android app and a web dashboard for following the Kansas Jayhawks women's
basketball team: every game with quarter scores and both teams' box scores,
season splits and shooting efficiency, the Big 12 standings and the AP poll,
and a question box that answers from the season data. Updated automatically
through the season, and again within minutes of a game going final.

Modeled on the
[ku-volleyball](https://github.com/inspectorgad/ku-volleyball) app, and seeded
with the complete 2025-26 season — 36 games, WBIT finalists — plus the 34-game
2026-27 schedule.

## The app

- **Roster** — name, number, position, and the bio kuathletics carries
  (height, class, hometown, the schools before this one). Current and former
  players are separated; a departed player keeps their stats and their bio.
  Tap a player for per-season and career totals, a full game log, their best
  games, milestones already reached, and recent form against the season behind
  it.
- **Games** — every game and every fixture, with the site shown three ways —
  home, away or neutral — because a neutral-site game is neither. Inside a
  game: the quarter scores, both teams' full box scores, the official team
  totals, and a *Notable* card stating only what is true of that night — a
  streak that is actually a run, a comeback that actually happened, a ranked
  opponent and their record coming in.
- **Leaders** — the team's record, scoring and shooting for a season or
  all-time, and leaderboards for points, rebounds, assists, steals and blocks
  per game, plus FG%, FT% and threes.
- **Season** — the record and margin; shooting as eFG%, TS%, 3PAr, FTr and
  A/TO against the opponent's; splits by site, by conference, by how close the
  game was, and by the half; and a quarter-by-quarter table with the running
  margin.
- **Opponents** — every team Kansas played, their record and production
  *against Kansas* — the only box scores this app holds — with each meeting,
  their shooting against KU's, and every opposing player summed across the
  meetings.
- **Big 12** — conference standings with NET and national rank, and the
  latest AP top 25 with the Big 12 teams marked.

Press and hold any stat label — `eFG%`, `TS%`, `A/TO`, `OR`, `Neutral` — for
what it means in plain words.

### Ask about the team

Type a question and Claude answers it from the season data. It is given a
Python sandbox and a tool that fetches any table of the season, so every figure
is computed in code rather than recalled, and "Show the code" lists exactly
what it ran. It can only answer from the box scores — it knows nothing about
injuries, practice or line-ups, and says so rather than guessing.

Each reader brings their own Anthropic API key, so questions are billed to
whoever asks; the cost is shown under every answer, usually a few cents. The
key is encrypted with a key the Android Keystore never lets off the phone,
excluded from cloud backup and device transfer, and sent only to
`api.anthropic.com`.

## Season dashboard

**https://inspectorgad.github.io/ku-wbb/**

The same data as a web page: season picker, game-by-game margins, team
leaders, the full schedule with a box score behind every played game, season
splits, opponents, standings and poll, and clickable per-player game logs —
plus the same Ask box.

Add it to your home screen and it installs as an app: it opens offline and
shows the last season it saw, but always asks the network first, because a
stat page that quietly shows yesterday's numbers is worse than one that admits
it is offline.

The schedule is also a calendar feed you can subscribe to —
[`docs/ku-wbb.ics`](https://inspectorgad.github.io/ku-wbb/ku-wbb.ics) — which
refreshes itself as tip times and broadcasts are set.

## Data pipeline

1. **`scripts/scrape-ku-wbb.mjs`** finds KU games through the
   [NCAA API](https://github.com/henrygd/ncaa-api) scoreboard, captures each
   finished game's official box score for both teams, and pulls the roster and
   schedule from kuathletics.com. It runs six times a day, on the :37 — the
   top of the hour is the most contended minute there is — and the roster
   scrape is what flags departed players as former.
2. **`scripts/update-seed.py`** rebuilds `app/src/main/assets/seed.json`:
   games keyed on date plus a normalized opponent name, since the schedule
   says "South Dakota State" where the box score says "South Dakota St.";
   site derived from the venue; conference, overtime and non-Division-I flags;
   and the official team totals for both sides.
3. **`scripts/build-dashboard.py`**, **`scripts/ics_feed.py`** and
   **`scripts/ask_pack.py`** render the dashboard, the calendar feed and the
   tables behind Ask from that same seed.
4. A real change — not just a moved timestamp — triggers the APK build, which
   publishes `app-debug.apk`, `season-data.json`, `ask-data.json` and the Play
   bundle to the rolling `latest-apk` release. Assets are uploaded under a
   staging name and renamed into place, so the download link never points at
   nothing mid-build.
5. On launch and on pull-to-refresh the app downloads `season-data.json` and
   merges it — gap-filling only, never overwriting anything entered by hand.

**`scripts/game-night-watch.mjs`** covers what a schedule cannot: when a game
is due, it starts polling about an hour and a half after tip and scrapes the
moment the result settles, so a final lands the same night rather than waiting
for the next scheduled run.

### Two things the data will mislead you about

- **Team figures come from the official team totals, never the sum of the
  player lines.** Team rebounds and team turnovers belong to no individual, so
  the lines add up about four rebounds a game short.
- **The NCAA leaves non-Division-I opponents out of its records.** KU's 22-14
  is the 21-14 the NET shows for that reason.

Both are flagged in the app and stated in the instructions Ask is given.

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

- Kotlin + Jetpack Compose (Material 3), KU crimson and blue
- Room for persistence, currently schema v7. The schema is not exported, so
  the migration tests are the only guard that an upgrade on a phone holding
  data survives; they walk a v3 database the whole chain.
- A pure-Kotlin stats engine in `app/src/main/java/com/example/stats/` —
  aggregates, efficiency, splits, game facts, player form, opponents — kept
  free of Compose so it can be tested as plain functions.
- Tests run in CI before anything is built or published: Kotlin unit and
  screenshot tests, `node --test` for the schedule and roster parsers and the
  game-night watcher, and `python3 -m unittest` for the calendar feed and the
  Ask pack.

## Data validation

The 2025-26 source data was validated before the app was built — see
[DATA-VALIDATION.md](DATA-VALIDATION.md). Raw evidence lives in `probe/`.

Site splits reproduce the NCAA's published ones exactly, the conference record
is the Big 12's own 18-game figure rather than a naive membership count, and
every game's quarter scores add up to its final.

## Run locally

**Prerequisites:** [Android Studio](https://developer.android.com/studio)

1. Open Android Studio
2. Select **Open** and choose the directory containing this project
3. Allow Android Studio to fix any incompatibilities as it imports the project
4. Run the app on an emulator or physical device

The pipeline scripts run on their own:

```sh
python3 scripts/update-seed.py      # rebuild the seed from scraped/
python3 scripts/build-dashboard.py  # rebuild docs/dashboard.html
python3 scripts/ics_feed.py         # rebuild docs/ku-wbb.ics
python3 scripts/ask_pack.py         # rebuild docs/ask-data.json
python3 -m unittest discover -s scripts -p 'test_*.py'
node --test scripts/*.test.mjs
```
