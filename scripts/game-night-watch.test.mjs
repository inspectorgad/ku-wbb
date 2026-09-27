// The watcher decides, from a clock and a schedule, whether to spend a job
// waiting on a game. Both halves of that are easy to get subtly wrong — the
// season straddles the November clock change, and the schedule dates are
// Central while everything in Actions is UTC — so they are tested directly.
//
// Run with:  node --test scripts/*.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { centralToUtc, centralDate, plan, kuGame, isExhibition, PLAN_WINDOW }
  from './game-night-watch.mjs';

const iso = (t) => new Date(t).toISOString();

test('Central wall time converts either side of the clock change', () => {
  // The basketball season starts in CDT and spends most of itself in CST,
  // so both offsets matter within one season.
  assert.equal(iso(centralToUtc('2026-10-22', '18:30')), '2026-10-22T23:30:00.000Z'); // CDT
  assert.equal(iso(centralToUtc('2026-11-01', '14:00')), '2026-11-01T20:00:00.000Z'); // CST
  assert.equal(iso(centralToUtc('2026-11-03', '18:30')), '2026-11-04T00:30:00.000Z'); // CST
  assert.equal(iso(centralToUtc('2027-03-03', '11:00')), '2027-03-03T17:00:00.000Z'); // CST
});

test('the date is Central, not UTC', () => {
  // 02:00 UTC on the 4th is still the evening of the 3rd in Lawrence — which
  // is exactly when a night game goes final.
  assert.equal(centralDate(Date.parse('2026-11-04T02:00:00Z')), '2026-11-03');
  assert.equal(centralDate(Date.parse('2026-11-03T18:00:00Z')), '2026-11-03');
});

const games = [
  { date: '2026-10-22', opponent: 'Northeastern State (exh.)', time: '18:30' },
  { date: '2026-11-03', opponent: 'Omaha', time: '18:30' },
  { date: '2026-11-06', opponent: 'Drake', time: '11:00' },
  { date: '2026-11-14', opponent: 'Nebraska', time: '15:30' },
  { date: '2026-12-20', opponent: 'Oklahoma State' },
  { date: '2025-11-05', opponent: 'Kansas City', teamScore: 74, opponentScore: 64 },
];

test('plans a watch only inside the window on game day', () => {
  // The 17:37 UTC scrape on opener day: tip is 6h55m out, too far.
  assert.equal(plan(games, Date.parse('2026-11-03T17:37:00Z')).watch, false);
  // The 21:37 UTC scrape: tip is 2h53m out, inside the window.
  const p = plan(games, Date.parse('2026-11-03T21:37:00Z'));
  assert.equal(p.watch, true);
  assert.equal(p.date, '2026-11-03');
  assert.equal(p.game.opponent, 'Omaha');
});

test('a game already under way is still watched', () => {
  // A late cron delivery must not lose the result it exists to catch.
  const p = plan(games, Date.parse('2026-11-04T01:00:00Z'));  // tipped 30m ago
  assert.equal(p.watch, true);
});

test('a game long finished is not watched', () => {
  const p = plan(games, Date.parse('2026-11-04T06:00:00Z'));  // 5.5h after tip
  assert.equal(p.watch, false);
});

test('a played game is never watched again', () => {
  assert.equal(plan(games, Date.parse('2025-11-06T00:00:00Z')).watch, false);
});

test('exhibitions are skipped — they never reach the D1 scoreboard', () => {
  assert.equal(isExhibition({ opponent: 'Northeastern State (exh.)' }), true);
  assert.equal(isExhibition({ opponent: 'Washburn (exh)' }), true);
  assert.equal(isExhibition({ opponent: 'Omaha' }), false);
  // Watching one would burn a 4.5-hour job polling for a final that the D1
  // scoreboard will never report.
  const p = plan(games, Date.parse('2026-10-22T21:37:00Z'));
  assert.equal(p.watch, false);
  assert.match(p.reason, /exhibition/);
});

test('a game with no published tip time is assumed to start at 6 p.m.', () => {
  // Most Big 12 dates have no time until the TV windows are set.
  const p = plan(games, Date.parse('2026-12-20T21:00:00Z'));  // 15:00 CST
  assert.equal(p.watch, true);
  assert.equal(iso(p.start), '2026-12-21T00:00:00.000Z');
});

test('a morning tip is planned from the window, not from the evening default', () => {
  // Drake tips at 11:00 CST; the 13:37 UTC scrape is 2h23m before it.
  assert.equal(plan(games, Date.parse('2026-11-06T13:37:00Z')).watch, true);
  // and the 05:37 UTC scrape is far too early.
  assert.equal(plan(games, Date.parse('2026-11-06T05:37:00Z')).watch, false);
});

test('the window is wide enough that a four-hour cadence cannot miss a game', () => {
  // Whatever the tip time, some scrape on the 4-hourly grid lands inside the
  // window. Checked against the real grid rather than asserted in prose.
  const CRON_HOURS = [1, 5, 9, 13, 17, 21];
  for (const time of ['11:00', '13:30', '15:30', '18:30', '20:00']) {
    const start = centralToUtc('2026-11-14', time);
    const hits = CRON_HOURS.map((h) => Date.parse(`2026-11-14T${String(h).padStart(2, '0')}:37:00Z`))
      .filter((t) => start - t <= PLAN_WINDOW && start - t > 0);
    assert.ok(hits.length > 0, `no scrape falls inside the window for a ${time} tip`);
  }
});

test('finds the KU game on a scoreboard', () => {
  const board = {
    games: [
      { game: { gameState: 'final', home: { names: { seo: 'iowa-st' } }, away: { names: { seo: 'baylor' } } } },
      { game: { gameState: 'live', home: { names: { seo: 'kansas' } }, away: { names: { seo: 'omaha' } } } },
    ],
  };
  assert.equal(kuGame(board).gameState, 'live');
  assert.equal(kuGame({ games: [] }), null);
  assert.equal(kuGame(null), null);
});
