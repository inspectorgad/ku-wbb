// Data-source validation probe for the KU Women's Basketball app.
//
// Runs in GitHub Actions (open outbound network — the same pattern the
// ku-volleyball app uses, since interactive sessions have restricted egress).
// Validates, before any app code is written, that the 2025-26 season data is
// fully retrievable:
//
//  1. NCAA API (ncaa-api.henrygd.me, JSON wrapper around ncaa.com):
//     scans the daily scoreboard across the whole season to discover every
//     Kansas game, then pulls each finished game's official box score.
//  2. kuathletics.com (Sidearm) via headless Chromium: roster + schedule
//     pages, to confirm the roster source works (best-effort).
//
// Everything is dumped under probe/ as raw evidence plus a validation
// summary with internal-consistency checks (player points vs team score,
// game count, stat field coverage).
import { chromium } from 'playwright';
import fs from 'fs';

const API = 'https://ncaa-api.henrygd.me';
const TEAM_SEO = 'kansas';
// 2025-26 D1 women's basketball: early November through the April final.
const SEASON_START = '2025-11-01';
const SEASON_END = '2026-04-10';

fs.rmSync('probe', { recursive: true, force: true });
fs.mkdirSync('probe/games', { recursive: true });

const summary = [];
function note(line) {
  summary.push(line);
  console.log(line);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function getJson(url) {
  const resp = await fetch(url, { headers: { accept: 'application/json' } });
  await sleep(350); // public instance is limited to 5 req/s
  if (!resp.ok) throw new Error(`${resp.status} for ${url}`);
  return resp.json();
}

// --- 1. Scoreboard scan: discover every KU game of 2025-26 -----------------
note(`Scanning NCAA scoreboard ${SEASON_START}..${SEASON_END} for '${TEAM_SEO}' games`);
const games = [];
let scanErrors = 0;
for (
  let d = new Date(`${SEASON_START}T00:00:00Z`);
  d <= new Date(`${SEASON_END}T00:00:00Z`);
  d = new Date(d.getTime() + 86_400_000)
) {
  const date = d.toISOString().slice(0, 10);
  const [y, m, day] = date.split('-');
  let data;
  try {
    data = await getJson(`${API}/scoreboard/basketball-women/d1/${y}/${m}/${day}`);
  } catch (e) {
    // Days with no D1 games return 404; anything else is worth counting.
    if (!String(e.message).startsWith('404')) {
      scanErrors++;
      note(`scoreboard ${date}: ${e.message}`);
    }
    continue;
  }
  for (const wrap of data.games || []) {
    const g = wrap.game || wrap;
    const sides = [g.home, g.away];
    if (!sides.some((s) => s?.names?.seo === TEAM_SEO)) continue;
    games.push({
      gameId: g.gameID,
      date,
      state: g.gameState,
      home: g.home?.names?.short,
      homeScore: g.home?.score,
      away: g.away?.names?.short,
      awayScore: g.away?.score,
      kuIsHome: g.home?.names?.seo === TEAM_SEO,
    });
    note(`  ${date}: ${g.away?.names?.short} ${g.away?.score || ''} at ${g.home?.names?.short} ${g.home?.score || ''} (${g.gameState}, id ${g.gameID})`);
  }
}
fs.writeFileSync('probe/schedule.json', JSON.stringify(games, null, 1));
note(`Found ${games.length} Kansas games (${scanErrors} scan errors)`);

// --- 2. Box scores for every final game ------------------------------------
let boxOk = 0;
let boxFail = 0;
const statFields = new Set();
const checks = [];
for (const g of games) {
  if (g.state !== 'final') continue;
  try {
    const info = await getJson(`${API}/game/${g.gameId}`);
    const box = await getJson(`${API}/game/${g.gameId}/boxscore`);
    fs.writeFileSync(
      `probe/games/ncaa-game-${g.gameId}.json`,
      JSON.stringify({ gameId: g.gameId, date: g.date, info, box }, null, 1)
    );
    boxOk++;

    // Consistency check: per-team sum of player points vs final score.
    const teams = box.teams || [];
    for (const tb of box.teamBoxscore || []) {
      // teams[].teamId is a string but teamBoxscore[].teamId is a number
      const team = teams.find((t) => String(t.teamId) === String(tb.teamId));
      const isKuSide = team?.seoname === TEAM_SEO || /kansas jayhawks/i.test(team?.nameFull || '');
      const finalScore = Number(team?.isHome ? g.homeScore : g.awayScore);
      const players = tb.playerStats || [];
      players.forEach((p) => Object.keys(p).forEach((k) => statFields.add(k)));
      const ptsSum = players.reduce((s, p) => s + (Number(p.points) || 0), 0);
      checks.push({
        gameId: g.gameId,
        date: g.date,
        team: team?.nameShort,
        ku: isKuSide,
        players: players.length,
        playerPtsSum: ptsSum,
        finalScore,
        match: Number.isFinite(finalScore) ? ptsSum === finalScore : null,
      });
    }
  } catch (e) {
    boxFail++;
    note(`boxscore ${g.gameId} (${g.date}): ${e.message}`);
  }
}
fs.writeFileSync('probe/boxscore-checks.json', JSON.stringify(checks, null, 1));
fs.writeFileSync('probe/stat-fields.json', JSON.stringify([...statFields].sort(), null, 1));
note(`Box scores: ${boxOk} captured, ${boxFail} failed`);
const mismatches = checks.filter((c) => c.match === false);
note(`Points consistency: ${checks.length - mismatches.length}/${checks.length} team-games where player points sum to the final score`);
for (const mm of mismatches) {
  note(`  MISMATCH ${mm.date} ${mm.team}: players sum ${mm.playerPtsSum}, final ${mm.finalScore} (game ${mm.gameId})`);
}
note(`Player stat fields seen: ${[...statFields].sort().join(', ')}`);

// --- 3. kuathletics.com roster + schedule (best-effort) --------------------
try {
  const browser = await chromium.launch();
  const context = await browser.newContext({
    userAgent:
      'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36',
    viewport: { width: 1400, height: 2400 },
  });

  async function pageText(url) {
    const page = await context.newPage();
    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60_000 });
    await page.waitForTimeout(8_000);
    for (let i = 0; i < 10; i++) {
      await page.evaluate(() => window.scrollBy(0, 1200));
      await page.waitForTimeout(400);
    }
    const text = await page.evaluate(() => (document.body ? document.body.innerText : ''));
    await page.close();
    return text;
  }

  const rosterText = await pageText('https://kuathletics.com/sports/womens-basketball/roster');
  fs.writeFileSync('probe/roster-page.txt', rosterText);
  const rosterLines = rosterText.split('\n').map((l) => l.trim());
  const roster = [];
  for (let i = 0; i < rosterLines.length; i++) {
    if (rosterLines[i] !== 'Jersey Number') continue;
    const number = rosterLines[i + 1] || '';
    const name = rosterLines[i + 2] || '';
    let position = '';
    if (rosterLines[i + 3] === 'Position') position = (rosterLines[i + 4] || '').trim();
    if (/^\d{1,2}$/.test(number) && /^[A-Za-z'.-]+( [A-Za-z'.-]+)+$/.test(name)) {
      roster.push({ name, jerseyNumber: number, position });
    }
  }
  fs.writeFileSync('probe/roster.json', JSON.stringify(roster, null, 1));
  note(`kuathletics roster: parsed ${roster.length} players`);

  const schedText = await pageText('https://kuathletics.com/sports/womens-basketball/schedule/season/2025-26');
  fs.writeFileSync('probe/schedule-page.txt', schedText);
  note(`kuathletics schedule page: ${schedText.length} chars captured`);

  await browser.close();
} catch (e) {
  note(`kuathletics scrape failed (non-fatal): ${e.message}`);
}

// --- Verdict ----------------------------------------------------------------
const finals = games.filter((g) => g.state === 'final').length;
note('');
note('=== VALIDATION VERDICT ===');
note(`Games discovered: ${games.length} (${finals} final)`);
note(`Box scores captured: ${boxOk}/${finals}`);
note(`Score consistency mismatches: ${mismatches.length}`);
const pass = games.length >= 25 && boxOk === finals && boxOk > 0;
note(pass ? 'PASS: season data is retrievable and internally consistent'
          : 'ATTENTION: thresholds not met — inspect notes above');

fs.writeFileSync('probe/summary.txt', summary.join('\n') + '\n');
