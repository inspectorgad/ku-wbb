// Runs the roster parser on a real saved page and on hand-written blocks, so a
// change that loses players — or quietly loses the bio fields while keeping the
// names — shows up here instead of in the app.
//
// The page is a frozen copy in scripts/fixtures, not the live
// scraped/roster-page.txt: that one changes as the roster does, so testing
// against it would fail the moment a player arrives or leaves.
//
// Run with:  node --test scripts/*.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { parseRoster, tidyHeight } from './roster-parser.mjs';

const page = fs.readFileSync(
  new URL('./fixtures/roster-page-2026-09-27.txt', import.meta.url), 'utf8'
);

test('heights read as feet-inches, and anything odd passes through', () => {
  assert.equal(tidyHeight("6' 0''"), '6-0');
  assert.equal(tidyHeight("5' 11''"), '5-11');
  assert.equal(tidyHeight('6\' 4"'), '6-4');
  assert.equal(tidyHeight('6-2'), '6-2');
  assert.equal(tidyHeight(''), '');
});

test('a player block yields every bio field', () => {
  const block = ['Jersey Number', '0', 'Anna Gooden', 'Position', 'G ',
    'Academic Year', ' So.', 'Height', " 6' 0''", 'Hometown', 'Fort Smith, Ark.',
    'Last School', 'Northside HS / Colorado', 'Full Bio ', 'for Anna Gooden'].join('\n');
  const [p] = parseRoster(block);
  assert.deepEqual(p, {
    name: 'Anna Gooden', jerseyNumber: '0', position: 'G', academicYear: 'So.',
    height: '6-0', hometown: 'Fort Smith, Ark.', lastSchool: 'Northside HS / Colorado',
  });
});

test('a block missing a field simply omits it rather than shifting the others', () => {
  const block = ['Jersey Number', '5', 'Jane Doe', 'Position', 'F',
    'Hometown', 'Lawrence, Kan.', 'Full Bio '].join('\n');
  const [p] = parseRoster(block);
  assert.equal(p.position, 'F');
  assert.equal(p.hometown, 'Lawrence, Kan.');
  assert.equal(p.height, undefined);
  assert.equal(p.academicYear, undefined);
});

test('page furniture is not mistaken for a player', () => {
  // The roster page carries the schedule rotator above it, which also has
  // numbers and capitalized words.
  const noise = ['Upcoming Event: Women\'s Basketball versus Omaha on November 3, 2026',
    'Thu. 10/22/26', '6:30 p.m. CT', 'vs Northeastern State (exh.)'].join('\n');
  assert.deepEqual(parseRoster(noise), []);
});

test('the saved roster page yields the whole squad with bios', () => {
  const got = parseRoster(page);
  // As of the 27 Sep capture: 13 players, every one with all five bio fields.
  assert.equal(got.length, 13);
  for (const field of ['position', 'academicYear', 'height', 'hometown', 'lastSchool']) {
    assert.equal(got.filter((p) => p[field]).length, 13, `every player has ${field}`);
  }
  const nichols = got.find((p) => p.name === "S'Mya Nichols");
  assert.deepEqual(
    [nichols.jerseyNumber, nichols.position, nichols.academicYear, nichols.height],
    ['12', 'G', 'Sr.', '6-0']
  );
  // A transfer keeps the whole path, not just the most recent school.
  assert.equal(
    got.find((p) => p.name === 'Mariyah Noel').lastSchool,
    'Bonner Springs HS / Ole Miss / Xavier'
  );
  // Jersey numbers are unique, which is what the app keys its badges on.
  assert.equal(new Set(got.map((p) => p.jerseyNumber)).size, 13);
});
