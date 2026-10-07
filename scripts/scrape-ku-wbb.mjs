// Scrapes KU Women's Basketball data from three sources:
//  1. NCAA API (ncaa-api.henrygd.me, JSON wrapper around ncaa.com) — game
//     discovery via the daily scoreboard, then per-game box scores. The same
//     sweep also yields every Big 12 game (each team carries a conference
//     tag), which is what the Big 12 standings are computed from.
//  2. NCAA API rankings — AP top 25 and NCAA NET. Both are current-snapshot
//     endpoints (no per-season history), so each nightly run captures the
//     latest and update-seed.py keys it by season.
//     Note: /standings/basketball-women/d1 is unreliable (volleyball's
//     equivalent returns HTTP 500), so conference records are computed
//     rather than fetched.
//  3. kuathletics.com (Sidearm) via headless Chromium — current roster and
//     upcoming schedule (which the NCAA scoreboard only shows day-of).
// Runs in GitHub Actions where outbound network is open. Incremental: an
// index in scraped/ku-index.json records scanned dates and finished games so
// nightly runs only touch new dates.
import { chromium } from 'playwright';
import fs from 'fs';
import { parseSchedule, siteOf } from './schedule-parser.mjs';
import { parseRoster } from './roster-parser.mjs';
import { parseRoster as parseOpponentRoster, playersFromJson }
  from './opponent-roster-parser.mjs';

const API = 'https://ncaa-api.henrygd.me';
// Season start years: "2025" means the 2025-26 season.
const SEASONS = (process.env.SEASONS || '2025 2026').trim().split(/\s+/);
const TEAM_SEO = 'kansas';
const CONFERENCE_SEO = 'big-12';
// Bump to force a one-time full re-sweep when the sweep starts capturing
// something new (the scannedDates cache would otherwise skip old dates).
const INDEX_VERSION = 3;

fs.mkdirSync('scraped', { recursive: true });

const INDEX_PATH = 'scraped/ku-index.json';
const index = fs.existsSync(INDEX_PATH)
  ? JSON.parse(fs.readFileSync(INDEX_PATH, 'utf8'))
  : { indexVersion: INDEX_VERSION, scannedDates: {}, games: {}, big12Games: {} };

index.games ??= {};
index.big12Games ??= {};
if ((index.indexVersion ?? 1) < INDEX_VERSION) {
  console.log(
    `index v${index.indexVersion ?? 1} < v${INDEX_VERSION}: clearing ${Object.keys(index.scannedDates ?? {}).length} scanned dates for a one-time full re-sweep`
  );
  index.scannedDates = {};
  index.indexVersion = INDEX_VERSION;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function getJson(url) {
  const resp = await fetch(url, { headers: { accept: 'application/json' } });
  await sleep(400); // public instance is limited to 5 req/s
  if (!resp.ok) throw new Error(`${resp.status} for ${url}`);
  return resp.json();
}

function* seasonDates(startYear) {
  // D1 women's basketball: early-November openers through the April final.
  const start = new Date(Date.UTC(startYear, 10, 1));
  const end = new Date(Date.UTC(startYear + 1, 3, 15));
  for (let d = start; d <= end; d = new Date(d.getTime() + 86_400_000)) {
    yield d.toISOString().slice(0, 10);
  }
}

const today = new Date().toISOString().slice(0, 10);
const recentCutoff = new Date(Date.now() - 4 * 86_400_000).toISOString().slice(0, 10);

// --- 1. Scoreboard scan: find KU games ------------------------------------
for (const season of SEASONS) {
  for (const date of seasonDates(Number(season))) {
    if (date > today) break;
    // Rescan recent dates (results may have just gone final); skip older
    // dates we've already scanned.
    if (index.scannedDates[date] && date < recentCutoff) continue;

    const [y, m, d] = date.split('-');
    let data;
    try {
      data = await getJson(`${API}/scoreboard/basketball-women/d1/${y}/${m}/${d}`);
    } catch (e) {
      // Days with no D1 games return 404; that still counts as scanned.
      if (String(e.message).startsWith('404')) {
        index.scannedDates[date] = true;
      } else {
        console.log(`scoreboard ${date}: ${e.message}`);
      }
      continue; // non-404 failures stay unscanned so a transient error retries tomorrow
    }
    for (const wrap of data.games || []) {
      const g = wrap.game || wrap;
      const sides = [g.home, g.away];

      // Big 12 capture: any game with at least one conference team. Conference
      // games (both sides tagged) drive conference records; the rest still
      // count toward each team's overall record. Membership comes from the
      // tags themselves, never a hardcoded list, so realignment can't stale it.
      const confTags = (s) => (s?.conferences ?? []).map((c) => c.conferenceSeo);
      const homeInConf = confTags(g.home).includes(CONFERENCE_SEO);
      const awayInConf = confTags(g.away).includes(CONFERENCE_SEO);
      if ((homeInConf || awayInConf) && g.gameState === 'final') {
        const side = (s, inConf) => ({
          name: s?.names?.short ?? '',
          seo: s?.names?.seo ?? '',
          score: Number(s?.score ?? 0),
          winner: Boolean(s?.winner),
          rank: s?.rank ? Number(s.rank) : null,
          inConference: inConf,
        });
        // Basketball seasons span two calendar years; key by start year
        // (games in Jan-Apr belong to the season that began the fall before).
        const [gy, gm] = date.split('-').map(Number);
        index.big12Games[g.gameID] = {
          date,
          season: String(gm >= 8 ? gy : gy - 1),
          home: side(g.home, homeInConf),
          away: side(g.away, awayInConf),
          conferenceGame: homeInConf && awayInConf,
          // Conference tournament and NCAA tournament games carry bracket
          // fields on the scoreboard (WBIT games don't — update-seed.py
          // handles those with a date cutoff). These count toward overall
          // records but never conference records.
          bracket: Boolean(g.bracketRound || g.bracketId),
        };
      }

      if (!sides.some((s) => s?.names?.seo === TEAM_SEO)) continue;
      const existing = index.games[g.gameID];
      if (existing?.final && existing?.boxscored) continue;
      index.games[g.gameID] = {
        date,
        final: g.gameState === 'final',
        home: g.home?.names?.short,
        away: g.away?.names?.short,
        boxscored: existing?.boxscored || false,
      };
      console.log(`found KU game ${g.gameID} on ${date}: ${g.away?.names?.short} at ${g.home?.names?.short} (${g.gameState})`);
    }
    index.scannedDates[date] = true;
  }
}

console.log(`big12 games captured: ${Object.keys(index.big12Games).length}`);

// --- 1b. Rankings snapshots (best effort; never fail the run) ---------------
// Both endpoints serve only the CURRENT poll/ranking — no per-season history.
// The unstamped file is the live snapshot; a season-stamped copy is also kept
// so a completed season's final table survives the rollover to the next one.
// Without the archive, the day the first 2026-27 NET publishes the final
// 2025-26 table (all 363 teams) would be gone for good.
const MONTHS = 'JAN FEB MAR APR MAY JUN JUL AUG SEP OCT NOV DEC'.split(' ');

/** "Through Games APR. 5, 2026" -> "2025-26" (Aug-Dec belongs to that year). */
function snapshotSeason(updated) {
  const m = /\b([A-Z]{3})[A-Z]*\.?\s+\d{1,2},?\s+(20\d{2})/i.exec(updated || '');
  if (!m) return null;
  const month = MONTHS.indexOf(m[1].toUpperCase()) + 1;
  if (month === 0) return null;
  const year = Number(m[2]);
  const start = month >= 8 ? year : year - 1;
  return `${start}-${String(start + 1).slice(-2)}`;
}

for (const [name, path] of [
  ['ap', 'rankings/basketball-women/d1/associated-press'],
  ['net', 'rankings/basketball-women/d1/ncaa-womens-basketball-net-rankings'],
]) {
  try {
    const data = await getJson(`${API}/${path}`);
    const body = JSON.stringify(data, null, 1);
    fs.writeFileSync(`scraped/rankings-${name}.json`, body);
    const season = snapshotSeason(data.updated);
    if (season) fs.writeFileSync(`scraped/rankings-${name}-${season}.json`, body);
    console.log(
      `rankings ${name}: ${data.data?.length ?? 0} rows (${data.updated ?? 'no date'})` +
      `${season ? ` archived as ${season}` : ' — season unparsed, not archived'}`
    );
  } catch (e) {
    console.log(`rankings ${name} failed (non-fatal): ${e.message}`);
  }
}

// --- 2. Box scores for final games not yet captured ------------------------
for (const [gameId, meta] of Object.entries(index.games)) {
  if (!meta.final || meta.boxscored) continue;
  try {
    const info = await getJson(`${API}/game/${gameId}`);
    const box = await getJson(`${API}/game/${gameId}/boxscore`);
    fs.writeFileSync(
      `scraped/ncaa-game-${gameId}.json`,
      JSON.stringify({ gameId, date: meta.date, info, box }, null, 1)
    );
    meta.boxscored = true;
    console.log(`captured box score for ${gameId} (${meta.date})`);
  } catch (e) {
    console.log(`boxscore ${gameId}: ${e.message}`);
  }
}

fs.writeFileSync(INDEX_PATH, JSON.stringify(index, null, 1));

// --- 3. kuathletics.com: current roster + upcoming schedule ----------------
// Best-effort: NCAA data alone keeps results flowing if Sidearm blocks us.
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

  // Roster, with bios. Parsing lives in roster-parser.mjs so it can be tested
  // against a frozen page; the NCAA feed cannot supply height, class, hometown
  // or previous school (its year/elig fields are empty on every row), so this
  // page is the only source for them.
  const rosterText = await pageText('https://kuathletics.com/sports/womens-basketball/roster');
  fs.writeFileSync('scraped/roster-page.txt', rosterText);
  const roster = parseRoster(rosterText);
  fs.writeFileSync('scraped/roster.json', JSON.stringify(roster, null, 1));
  const withBio = roster.filter((p) => p.height && p.hometown).length;
  console.log(`roster: ${roster.length} players (${withBio} with a full bio)`);
  if (roster.length < 8) {
    console.log('  WARNING: implausibly small roster — did the page layout change?');
  }

  // The whole season's fixtures, with venue, city, tip time and broadcast.
  // Parsing lives in schedule-parser.mjs so it can be tested against a frozen
  // page; reading only the rotator (as this did) found a quarter of the season
  // and could not tell a neutral floor from a home game.
  const schedText = await pageText('https://kuathletics.com/sports/womens-basketball/schedule');
  fs.writeFileSync('scraped/schedule-page.txt', schedText);
  const fixtures = parseSchedule(schedText).map((f) => ({
    date: f.date,
    opponent: f.opponent,
    site: siteOf(f),
    // Kept so an older seed builder, which knows only this field, still places
    // the game on the right side of a home/away split.
    home: siteOf(f) === 'home',
    venue: f.venue,
    city: f.city,
    time: f.time,
    tv: f.tv,
    event: f.event,
  }));
  fs.writeFileSync('scraped/upcoming.json', JSON.stringify(fixtures, null, 1));
  const withTime = fixtures.filter((f) => f.time).length;
  const neutral = fixtures.filter((f) => f.site === 'neutral').length;
  console.log(
    `upcoming: ${fixtures.length} games (${withTime} with a tip time, ${neutral} neutral)`
  );
  if (fixtures.length < 10) {
    console.log('  WARNING: far fewer fixtures than a full season — did the page layout change?');
  }

  // Opposing players' heights, from each school's own roster page. The NCAA box
  // scores give an opponent a name, a number and a position and nothing else,
  // so this is the only source. Everything here is best-effort: a team with no
  // URL mapped, a page that will not load, or a page that parses to too few
  // players is skipped and logged, never an error. Nothing downstream requires
  // a roster, and a partial one is worse than none — a missing height reads as
  // "not known", a wrong one reads as fact.
  try {
    const sites = JSON.parse(fs.readFileSync('scripts/opponent-sites.json', 'utf8'));
    const siteMap = { ...sites.verified, ...sites.unverified };
    const ROSTERS = 'scraped/opponent-rosters.json';
    const previous = fs.existsSync(ROSTERS)
      ? JSON.parse(fs.readFileSync(ROSTERS, 'utf8'))
      : {};
    // Rosters change about twice a year. Refetching every four hours would be
    // thousands of requests to other people's servers for a page that has not
    // moved, so a stored roster is left alone for a week.
    const weekAgo = new Date(Date.now() - 7 * 86_400_000).toISOString();
    const rosters = { ...previous };
    let fetched = 0;
    const failed = [];
    for (const [key, url] of Object.entries(siteMap).sort()) {
      if (previous[key]?.fetchedAt > weekAgo && previous[key]?.players?.length) continue;
      const page = await context.newPage();
      // Some rosters arrive as JSON after the shell renders, with no text to
      // read, so whatever the page fetches is kept as a fallback.
      const payloads = [];
      page.on('response', async (r) => {
        if (!/json/i.test(r.headers()['content-type'] || '')) return;
        try { payloads.push(await r.json()); } catch { /* not ours to parse */ }
      });
      try {
        await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 45_000 });
        await page.waitForTimeout(5_000);
        for (let i = 0; i < 8; i++) {
          await page.evaluate(() => window.scrollBy(0, 1500));
          await page.waitForTimeout(400);
        }
        const text = await page.evaluate(() => (document.body ? document.body.innerText : ''));
        let players = parseOpponentRoster(text);
        if (players.length < 8) {
          const fromJson = playersFromJson(payloads);
          if (fromJson.length > players.length) players = fromJson;
        }
        // A women's basketball roster runs 12-18. Below this we have matched
        // page furniture rather than the roster, and the page is kept so the
        // shape can be read off it rather than guessed at.
        const MIN_ROSTER = 8;
        if (players.length < MIN_ROSTER) {
          fs.writeFileSync(`scraped/roster-miss-${key.replace(/[^a-z0-9]+/g, '-')}.txt`, text);
          failed.push(`${key} (parsed ${players.length}, below the ${MIN_ROSTER} floor; page saved)`);
        } else {
          rosters[key] = { url, fetchedAt: new Date().toISOString(), players };
          fetched++;
          fs.rmSync(`scraped/roster-miss-${key.replace(/[^a-z0-9]+/g, '-')}.txt`, { force: true });
          console.log(`  roster ${key}: ${players.length} players ` +
            `(${players.filter((p) => p.height).length} with a height)`);
        }
      } catch (e) {
        failed.push(`${key} (${e.message.split('\n')[0]})`);
      }
      await page.close();
    }
    fs.writeFileSync(ROSTERS, JSON.stringify(rosters, null, 1));
    console.log(`opponent rosters: ${Object.keys(rosters).length} teams stored, ` +
      `${fetched} refreshed this run, ${Object.keys(siteMap).length} mapped`);
    if (failed.length) console.log(`  did not parse: ${failed.join('; ')}`);
  } catch (e) {
    console.log(`opponent roster scrape failed (non-fatal): ${e.message}`);
  }

  await browser.close();
} catch (e) {
  console.log(`kuathletics scrape failed (non-fatal): ${e.message}`);
}

console.log('scrape complete');
