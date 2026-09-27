// Same-night results: wait for KU's game to go final, then run the scrape.
//
// The scrape runs on a fixed cadence, and a cron entry cannot know when a game
// ends — a 6:30 p.m. tip finished by 8:30 would otherwise sit until the next
// scheduled run, which for the old single 09:00 UTC cron meant the next
// morning. More cron slots do not fix that; they are just as blind. This
// watches the one game instead.
//
//   node scripts/game-night-watch.mjs plan
//       Run by each scrape. Writes watch=true and date=YYYY-MM-DD to
//       $GITHUB_OUTPUT when KU tips off within the window, so the scrape can
//       dispatch game-night.yml.
//   node scripts/game-night-watch.mjs watch YYYY-MM-DD
//       Run by game-night.yml. Sleeps until the game should be near its end,
//       polls the NCAA scoreboard until it is final, then dispatches
//       scrape-data.yml.

import { readFileSync, appendFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

const API = 'https://ncaa-api.henrygd.me';
const TEAM_SEO = 'kansas';
const ZONE = 'America/Chicago'; // the athletics schedule lists every time in Central

const MIN = 60_000;
const HOUR = 60 * MIN;
// A scrape dispatches the watcher when tip-off is at most this far off. A
// little over the four-hour cron spacing, so one scrape always falls inside
// it; a second one inside it is harmless (the concurrency group holds it).
export const PLAN_WINDOW = 4.5 * HOUR;
// Women's college basketball runs 40 minutes of clock; with stoppages and a
// halftime, a game rarely ends inside 100 minutes, so polling starts then.
const FIRST_POLL_AFTER = 100 * MIN;
const POLL_EVERY = 5 * MIN;
// Give up this long after tip: a postponed game, or a scoreboard that never
// flips. The scheduled scrape still catches it later.
const GIVE_UP_AFTER = 4.5 * HOUR;
// The box score can trail the final buzzer by a few minutes.
const SETTLE = 8 * MIN;
// GitHub stops a job at six hours. A watcher dispatched early in the window
// plus a long overtime game can together need more, so a watcher that reaches
// this budget hands over to a fresh run of itself rather than being killed.
const RUN_BUDGET = 340 * MIN;
// With no published tip time, assume an evening start for the plan and watch.
const DEFAULT_TIME = '18:00';

/** The UTC instant of a Central wall-clock time, DST included. */
export function centralToUtc(date, time) {
  const [y, mo, d] = date.split('-').map(Number);
  const [h, mi] = time.split(':').map(Number);
  const wall = Date.UTC(y, mo - 1, d, h, mi);
  // Read the zone's offset at that moment and correct for it; a second pass
  // settles the rare case where the first guess lands across a clock change.
  let t = wall + 6 * HOUR;
  for (let i = 0; i < 2; i++) t = wall - offsetMs(t);
  return t;
}

function offsetMs(t) {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('en-US', {
      timeZone: ZONE, hourCycle: 'h23',
      year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
    }).formatToParts(new Date(t)).map((p) => [p.type, p.value]),
  );
  const asUtc = Date.UTC(+parts.year, +parts.month - 1, +parts.day, +parts.hour, +parts.minute);
  return asUtc - Math.floor(t / MIN) * MIN;
}

/** Today's date in Central, as the schedule dates are. */
export function centralDate(t) {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('en-US', { timeZone: ZONE, year: 'numeric', month: '2-digit', day: '2-digit' })
      .formatToParts(new Date(t)).map((p) => [p.type, p.value]),
  );
  return `${parts.year}-${parts.month}-${parts.day}`;
}

const played = (g) => g.teamScore != null && g.opponentScore != null;

/**
 * An exhibition is never worth watching: the opponents are D2 schools that do
 * not appear on the D1 scoreboard at all, so the watcher would poll a game
 * that can never turn final and burn a 4.5-hour job finding nothing.
 */
export const isExhibition = (g) => /\(exh\.?\)/i.test(g.opponent || '');

/**
 * Whether a scrape at `now` should start a watcher: KU plays today (Central),
 * the game has no result yet, it is not an exhibition, and tip-off is within
 * the window. A game already under way still counts — a late cron delivery
 * should not lose it.
 */
export function plan(games, now) {
  const today = centralDate(now);
  const todays = games.filter((g) => g.date === today && !played(g));
  if (!todays.length) return { watch: false, reason: `no unplayed KU game on ${today}` };
  const game = todays.find((g) => !isExhibition(g));
  if (!game) return { watch: false, reason: `${today} is an exhibition; not on the D1 scoreboard` };
  const start = centralToUtc(today, game.time || DEFAULT_TIME);
  if (start - now > PLAN_WINDOW) {
    return {
      watch: false,
      reason: `tip ${new Date(start).toISOString()} is more than ${PLAN_WINDOW / HOUR}h away`,
    };
  }
  if (now - start > GIVE_UP_AFTER) return { watch: false, reason: 'game started too long ago to watch' };
  return { watch: true, date: today, game, start };
}

/** KU's game on a scoreboard response, or null. */
export function kuGame(scoreboard) {
  for (const wrap of scoreboard?.games ?? []) {
    const g = wrap.game || wrap;
    if ([g.home, g.away].some((s) => s?.names?.seo === TEAM_SEO)) return g;
  }
  return null;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, Math.max(0, ms)));
const stamp = () => new Date().toISOString().slice(11, 16) + 'Z';
/** The branch this run is on — the repo's default branch is not called main. */
const ref = () => process.env.GITHUB_REF_NAME || 'main';

function loadGames() {
  return JSON.parse(readFileSync('app/src/main/assets/seed.json', 'utf8')).games ?? [];
}

async function watch(date) {
  const budgetEnd = Date.now() + RUN_BUDGET;
  const game = loadGames().find((g) => g.date === date && !isExhibition(g));
  if (!game) return console.log(`no KU game to watch on ${date}`);
  if (played(game)) return console.log(`${date} vs ${game.opponent} already has a result; nothing to do`);
  const start = centralToUtc(date, game.time || DEFAULT_TIME);
  const firstPoll = start + FIRST_POLL_AFTER;
  const deadline = start + GIVE_UP_AFTER;
  console.log(`watching ${game.opponent} on ${date}: tip ${new Date(start).toISOString()}` +
    (game.time ? '' : ' (no time published; assumed 6 p.m. CT)'));
  await sleep(Math.min(firstPoll, budgetEnd) - Date.now());

  const [y, mo, d] = date.split('-');
  while (Date.now() < deadline) {
    if (Date.now() >= budgetEnd) return handOver(date);
    try {
      const resp = await fetch(`${API}/scoreboard/basketball-women/d1/${y}/${mo}/${d}`,
        { headers: { accept: 'application/json' } });
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
      const g = kuGame(await resp.json());
      const state = g?.gameState ?? 'not on the scoreboard';
      console.log(`${stamp()} ${state}` +
        (g ? ` · ${g.away?.names?.short} ${g.away?.score ?? ''} at ${g.home?.names?.short} ${g.home?.score ?? ''}` : ''));
      if (g?.gameState === 'final') {
        await sleep(SETTLE);
        dispatchScrape();
        return;
      }
    } catch (e) {
      console.log(`${stamp()} scoreboard: ${e.message}`);
    }
    await sleep(POLL_EVERY);
  }
  console.log('gave up waiting; the scheduled scrape will pick the result up');
}

function handOver(date) {
  console.log('run budget spent before the final; handing over to a fresh watcher');
  // Queues behind this run in the per-date concurrency group, then starts.
  execFileSync('gh', ['workflow', 'run', 'game-night.yml', '--repo', process.env.GITHUB_REPOSITORY,
    '--ref', ref(), '-f', `date=${date}`], { stdio: 'inherit' });
}

function dispatchScrape() {
  const repo = process.env.GITHUB_REPOSITORY;
  console.log(`final; dispatching scrape-data.yml on ${repo}`);
  execFileSync('gh', ['workflow', 'run', 'scrape-data.yml', '--repo', repo, '--ref', ref()],
    { stdio: 'inherit' });
}

async function main() {
  const [mode, arg] = process.argv.slice(2);
  if (mode === 'plan') {
    const p = plan(loadGames(), Date.now());
    console.log(p.watch
      ? `game night: ${p.game.opponent} at ${new Date(p.start).toISOString()}`
      : `no watch: ${p.reason}`);
    if (process.env.GITHUB_OUTPUT) {
      appendFileSync(process.env.GITHUB_OUTPUT, `watch=${p.watch}\n` + (p.watch ? `date=${p.date}\n` : ''));
    }
  } else if (mode === 'watch' && /^\d{4}-\d{2}-\d{2}$/.test(arg ?? '')) {
    await watch(arg);
  } else {
    console.error('usage: game-night-watch.mjs plan | watch YYYY-MM-DD');
    process.exit(2);
  }
}

// Only run when invoked as the script, not when imported by a test — and not
// at all when there is no script path (`node -e`, a REPL), which would throw.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) await main();
