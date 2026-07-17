# Data validation — KU Women's Basketball 2025-26

Result: **PASS**. The full 2025-26 season is retrievable from the same
two-source pipeline the ku-volleyball app uses, and the data is internally
consistent. Raw evidence lives in `probe/` (captured by the
`probe-data.yml` GitHub Actions workflow on 2026-07-17).

## Source 1: NCAA API (`ncaa-api.henrygd.me`)

- Scanned the daily scoreboard `basketball-women/d1` from 2025-11-01 to
  2026-04-10: **36 Kansas games found, 0 scan errors** — 30 regular-season
  games, 2 Big 12 tournament games, and a 4-game WBIT run (final record
  22-14, ending in the WBIT title game loss at BYU on 2026-03-30).
- **36/36 box scores captured** (`probe/games/`). Every player line is a
  `PlayerStatsBasketball` object with: minutes, FGM/FGA, 3PM/3PA, FTM/FTA,
  offensive/total rebounds, assists, turnovers, steals, blocks, personal
  fouls, points, jersey number, position, starter flag.

### Consistency checks (all pass)

- Per-player points formula `2×FG2 + 3×FG3 + FT = PTS`: holds for every
  player line in all 36 games.
- Sum of player points = official final score: **72/72 team-games**.
- 12 distinct KU players appear across the season; season aggregates look
  right (S'Mya Nichols 17.4 ppg over 36 games; Jaliya Davis 19.8 ppg over
  26 games).

Gotcha for the app: in the boxscore JSON, `teams[].teamId` is a *string*
while `teamBoxscore[].teamId` is a *number* — compare as strings.

## Source 2: kuathletics.com (Sidearm, headless Chromium)

- Roster page parsed cleanly: 13 players with name / jersey / position.
- **Note:** the roster page shows the *current* (2026-27) roster — it no
  longer lists departed 2025-26 players (e.g. Lilly Meister, Elle Evans).
  The 2025-26 roster should therefore be derived from the box scores;
  the live page is only useful for the upcoming season.
- Schedule page renders and is capturable (useful later for upcoming
  2026-27 games, same as the volleyball app).

## Conclusion

Green light to build the app on the ku-volleyball architecture:
nightly NCAA-API scrape → seed.json → Kotlin/Compose app with Room,
with the 2025-26 season fully seeded from the data already in `probe/`.
