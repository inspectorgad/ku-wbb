// The fetching half of the opponent roster scrape cannot be exercised here —
// this container's egress proxy refuses athletics sites — so the parser is
// where the testing effort goes. Every shape below is a basketball-shaped
// version of a layout the volleyball app met on a real school's site.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  parseRoster, playersFromJson, normalizeHeight,
} from './opponent-roster-parser.mjs';

test('heights are read however the school punctuates them', () => {
  assert.equal(normalizeHeight("6' 3''"), '6-3');
  assert.equal(normalizeHeight('6\'3"'), '6-3');
  assert.equal(normalizeHeight('6′3″'), '6-3');
  assert.equal(normalizeHeight('5-11'), '5-11');
  assert.equal(normalizeHeight("6'"), '6-0');
  // A season label is not a height, which is why the bare form is anchored.
  assert.equal(normalizeHeight('2025-26'), '');
  assert.equal(normalizeHeight(''), '');
  assert.equal(normalizeHeight(null), '');
});

test('Sidearm labelled blocks', () => {
  const page = [
    'Roster', '', 'Jersey Number', '12', 'Jane Doe',
    'Position', 'G', 'Academic Year', 'Jr.', 'Height', "5' 9''",
    'Hometown', 'Austin, Texas', 'Full Bio',
    'Jersey Number', '33', 'Mary Roe',
    'Position', 'F/C', 'Academic Year', 'Sr.', 'Height', "6' 4''",
    'Full Bio',
  ].join('\n');
  assert.deepEqual(parseRoster(page), [
    { name: 'Jane Doe', jerseyNumber: '12', position: 'G', height: '5-9' },
    { name: 'Mary Roe', jerseyNumber: '33', position: 'F/C', height: '6-4' },
  ]);
});

test('unlabelled cards, with the name shouted', () => {
  const page = [
    '1', 'G', 'SARAH HICKMAN', '5\'7"Senior', 'Houston, Texas',
    '21', 'C', 'ANNA PETROVA', '6\'5"Freshman', 'Sofia, Bulgaria',
  ].join('\n');
  const got = parseRoster(page);
  // Shouted names are folded back to title case.
  assert.deepEqual(got.map(p => p.name), ['Sarah Hickman', 'Anna Petrova']);
  assert.deepEqual(got.map(p => p.height), ['5-7', '6-5']);
  assert.deepEqual(got.map(p => p.position), ['G', 'C']);
});

test('position and height on the line above the number', () => {
  const page = [
    'Forward 6\'0"', '1', '', 'Jane Doe', 'Sophomore Columbia, Mo.',
    'Guard 5\'6"', '4', '', 'Mary Roe', 'Freshman Topeka, Kan.',
  ].join('\n');
  assert.deepEqual(parseRoster(page), [
    { name: 'Jane Doe', jerseyNumber: '1', position: 'Forward', height: '6-0' },
    { name: 'Mary Roe', jerseyNumber: '4', position: 'Guard', height: '5-6' },
  ]);
});

test('one player per tab-separated line', () => {
  const page = [
    'No.\tName\tPos.\tHt.\tYr.\tHometown',
    "0\tFaith Jordan\tG\t5' 11''\tFr.\tJoliet, Illinois",
    "15\tPaula Ruiz\tF\t6' 2''\tSo.\tMadrid, Spain",
  ].join('\n');
  const got = parseRoster(page);
  assert.deepEqual(got.map(p => [p.jerseyNumber, p.name, p.position, p.height]), [
    ['0', 'Faith Jordan', 'G', '5-11'],
    ['15', 'Paula Ruiz', 'F', '6-2'],
  ]);
});

test('names outside ASCII are players, not noise', () => {
  // An ASCII-only name test read one volleyball roster as empty rather than as
  // eighteen players with two missing. Same risk here.
  const page = [
    'Jersey Number', '7', 'Živa Labinjan', 'Position', 'F', 'Height', "6' 1''",
    'Jersey Number', '9', 'Anaëlle Dutat', 'Position', 'G', 'Height', "5' 10''",
  ].join('\n');
  assert.deepEqual(parseRoster(page).map(p => p.name), ['Živa Labinjan', 'Anaëlle Dutat']);
});

test('a hash before the number is decoration', () => {
  const page = ['Jersey Number', '#4', 'Jane Doe', 'Position', 'G', 'Height', "5' 8''"].join('\n');
  assert.equal(parseRoster(page)[0].jerseyNumber, '4');
});

test('a page listing the roster twice yields each player once', () => {
  const block = ['Jersey Number', '12', 'Jane Doe', 'Position', 'G', 'Height', "5' 9''"];
  assert.equal(parseRoster([...block, ...block].join('\n')).length, 1);
});

test('a page with no roster on it parses to nothing, not to junk', () => {
  const page = ['Womens Basketball', 'Schedule', 'News', 'Tickets', '2025-26 Season'].join('\n');
  assert.deepEqual(parseRoster(page), []);
  assert.deepEqual(parseRoster(''), []);
  assert.deepEqual(parseRoster(null), []);
});

test('a roster fetched as JSON is read from whatever the page pulled down', () => {
  // Some schools serve a shell and fetch the roster afterwards, so there is no
  // rendered text to parse. A player is recognised by its fields.
  const payloads = [
    { nav: [{ title: 'Schedule' }] },
    {
      data: {
        roster: [
          { firstName: 'Jane', lastName: 'Doe', jersey_number: '12', positionShort: 'G', height: "5'9\"" },
          { fullName: 'Mary Roe', uniform: '33', pos: 'F', heightFormatted: "6'4\"" },
        ],
        staff: [{ name: 'Coach Smith', title: 'Head Coach' }],
      },
    },
  ];
  assert.deepEqual(playersFromJson(payloads), [
    { name: 'Jane Doe', jerseyNumber: '12', position: 'G', height: '5-9' },
    { name: 'Mary Roe', jerseyNumber: '33', position: 'F', height: '6-4' },
  ]);
});

test('staff and other people on the page are not mistaken for players', () => {
  // A height is what separates a roster entry from the many other objects an
  // athletics site ships, all of which carry names.
  assert.deepEqual(playersFromJson([{ staff: [{ name: 'Coach Smith', title: 'Head Coach' }] }]), []);
  assert.deepEqual(playersFromJson([]), []);
  assert.deepEqual(playersFromJson(null), []);
});
