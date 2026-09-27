// Runs the schedule parser on a real saved page and on small hand-written
// rows, so a change that loses fixtures, tip times or venues shows up here
// instead of as a quieter schedule in the app.
//
// The page is a frozen copy in scripts/fixtures, not the live
// scraped/schedule-page.txt: that one changes as games are played, so testing
// against it would fail — and block the scrape it guards — the morning after
// the opener drops off the upcoming list.
//
// Run with:  node --test scripts/*.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { parseSchedule, siteOf, to24h, postalCity } from './schedule-parser.mjs';

const page = fs.readFileSync(
  new URL('./fixtures/schedule-page-2026-09-27.txt', import.meta.url), 'utf8'
);

test('tip times read in 24-hour form, and nothing else counts as one', () => {
  assert.equal(to24h('6:30 p.m. CT'), '18:30');
  assert.equal(to24h('11 a.m. CT'), '11:00');
  assert.equal(to24h('12 p.m. CT'), '12:00');
  assert.equal(to24h('12 a.m.'), '00:00');
  assert.equal(to24h('3 p.m. CT'), '15:00');
  assert.equal(to24h('History'), null);
  assert.equal(to24h('(Sat)'), null);
  assert.equal(to24h(''), null);
});

test('AP-style regions become postal codes, unknown ones pass through', () => {
  assert.equal(postalCity('Lawrence, Kan.'), 'Lawrence, KS');
  assert.equal(postalCity('Sioux Falls, S.D.'), 'Sioux Falls, SD');
  assert.equal(postalCity('Morgantown, W. Va.'), 'Morgantown, WV');
  // Not a US state: left exactly as the page wrote it rather than mangled.
  assert.equal(postalCity('Cancun, Mexico'), 'Cancun, Mexico');
});

test('a row carries its venue, city, tip time and channel', () => {
  const row = ['MarketBeat Invitational', 'vs', 'Nebraska', '', 'Sanford Pentagon',
    'Sioux Falls, S.D.', '', 'TV: BTN+', '', 'Nov 14', '(Sat)', '', '3:30 p.m. CT',
    '', 'History'].join('\n');
  const [f] = parseSchedule(`2026-27 Women's Basketball Schedule\n${row}`);
  assert.equal(f.date, '2026-11-14');
  assert.equal(f.time, '15:30');
  assert.equal(f.venue, 'Sanford Pentagon');
  assert.equal(f.city, 'Sioux Falls, SD');
  assert.equal(f.tv, 'BTN+');
  assert.equal(f.event, 'MarketBeat Invitational');
});

test('a row with no tip time yet gets none, not the next row\'s', () => {
  const rows = [
    'at', 'West Virginia', '', 'WVU Coliseum', 'Morgantown, W. Va.', '', 'Dec 30', '(Wed)', '', 'History',
    'at', 'Cincinnati', '', 'Fifth Third Arena', 'Cincinnati, Ohio', '', 'Jan 2', '(Sat)', '', '1 p.m. CT',
  ].join('\n');
  const got = parseSchedule(`2026-27 Women's Basketball Schedule\n${rows}`);
  assert.equal(got.find((f) => f.opponent === 'West Virginia').time, null);
  assert.equal(got.find((f) => f.opponent === 'Cincinnati').time, '13:00');
});

test('a season spanning two years dates January from the later one', () => {
  const rows = [
    'vs', 'Texas Tech', '', 'Allen Fieldhouse', 'Lawrence, Kan.', '', 'Nov 28', '(Sat)', '', 'History',
    'at', 'Utah', '', 'Jon M. Huntsman Center', 'Salt Lake City, Utah', '', 'Jan 16', '(Sat)', '', 'History',
  ].join('\n');
  const got = parseSchedule(`2026-27 Women's Basketball Schedule\n${rows}`);
  assert.equal(got.find((f) => f.opponent === 'Texas Tech').date, '2026-11-28');
  assert.equal(got.find((f) => f.opponent === 'Utah').date, '2027-01-16');
});

test('site is home, away or neutral — "vs" alone does not mean home', () => {
  // The page says "vs Nebraska", but it is played at the Sanford Pentagon,
  // which is neither team's floor. Trusting "vs" is exactly the bug this fixes.
  assert.equal(siteOf({ away: false, venue: 'Allen Fieldhouse' }), 'home');
  assert.equal(siteOf({ away: false, venue: 'Sanford Pentagon' }), 'neutral');
  assert.equal(siteOf({ away: true, venue: 'The Barn' }), 'away');
  // A rotator-only row names no venue; the table row settles it on merge.
  assert.equal(siteOf({ away: false, venue: null }), 'home');
});

test('the saved schedule page yields the whole season', () => {
  const got = parseSchedule(page);
  // As of the 27 Sep capture: the full 2026-27 season, every row with a venue
  // and city, 14 with a tip time set. If the page's layout moves, these drop.
  assert.equal(got.length, 34);
  assert.equal(got.filter((f) => f.venue).length, 34);
  assert.equal(got.filter((f) => f.city).length, 34);
  assert.equal(got.filter((f) => f.time).length, 14);

  const opener = got.find((f) => f.opponent === 'Omaha');
  assert.deepEqual(
    [opener.date, opener.time, opener.venue, siteOf(opener)],
    ['2026-11-03', '18:30', 'Allen Fieldhouse', 'home']
  );

  // The four fixtures the old rotator-only scrape stored as home games.
  for (const [opponent, venue] of [
    ['Nebraska', 'Sanford Pentagon'],
    ['South Dakota State', 'T-Mobile Center'],
    ['Washington State', 'Hard Rock Hotel Rivera Maya'],
    ['Miami (OH)', 'Hard Rock Hotel Rivera Maya'],
  ]) {
    const f = got.find((x) => x.opponent === opponent);
    assert.equal(siteOf(f), 'neutral', `${opponent} should be neutral`);
    assert.equal(f.venue, venue);
  }

  // Conference road games are away, and Allen games are home.
  assert.equal(siteOf(got.find((f) => f.opponent === 'Minnesota')), 'away');
  assert.equal(siteOf(got.find((f) => f.date === '2027-02-20')), 'away');
  assert.equal(siteOf(got.find((f) => f.opponent === 'Iowa State')), 'home');
});
