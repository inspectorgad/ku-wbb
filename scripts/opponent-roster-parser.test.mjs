// The fetching half of the opponent roster scrape cannot be exercised here —
// this container's egress proxy refuses athletics sites — so the parser is
// where the testing effort goes. Every shape below is a basketball-shaped
// version of a layout the volleyball app met on a real school's site.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {
  parseRoster, playersFromJson, normalizeHeight,
} from './opponent-roster-parser.mjs';

/** The four measurement fields, for tests that do not care about the bios. */
const core = (p) => ({
  name: p.name, jerseyNumber: p.jerseyNumber, position: p.position, height: p.height,
});

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
  assert.deepEqual(parseRoster(page).map(core), [
    { name: 'Jane Doe', jerseyNumber: '12', position: 'G', height: '5-9' },
    { name: 'Mary Roe', jerseyNumber: '33', position: 'F/C', height: '6-4' },
  ]);
});

test('the labelled shape keeps the bio fields it walks past', () => {
  // These sit between Position and Height on the same page, and were being
  // stepped over. They are what makes an opponent card read like KU's.
  const page = [
    'Jersey Number', '12', 'Jane Doe',
    'Position', 'G', 'Academic Year', 'Jr.', 'Height', "5' 9''",
    'Hometown', 'Austin, Texas', 'Last School', 'Westlake HS', 'Full Bio',
  ].join('\n');
  assert.deepEqual(parseRoster(page), [{
    name: 'Jane Doe', jerseyNumber: '12', position: 'G', height: '5-9',
    academicYear: 'Jr.', hometown: 'Austin, Texas', lastSchool: 'Westlake HS',
  }]);
});

test('a shape that carries no bios still returns the fields, empty', () => {
  // A caller reads .hometown off any entry without asking which layout made it.
  const page = ['1', 'G', 'SARAH HICKMAN', '5\'7"Senior', 'Houston, Texas'].join('\n');
  const [p] = parseRoster(page);
  assert.deepEqual(
    { academicYear: p.academicYear, hometown: p.hometown, lastSchool: p.lastSchool },
    { academicYear: '', hometown: '', lastSchool: '' }
  );
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
  assert.deepEqual(parseRoster(page).map(core), [
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
  assert.deepEqual(playersFromJson(payloads).map(core), [
    { name: 'Jane Doe', jerseyNumber: '12', position: 'G', height: '5-9' },
    { name: 'Mary Roe', jerseyNumber: '33', position: 'F', height: '6-4' },
  ]);
});

test('a JSON roster carries the bio fields too, under whatever key', () => {
  const payloads = [{ roster: [
    { fullName: 'Jane Doe', jersey: '12', pos: 'G', height: "5'9\"",
      classYear: 'Jr.', hometown: 'Austin, Texas', lastSchool: 'Westlake HS' },
  ] }];
  assert.deepEqual(playersFromJson(payloads), [{
    name: 'Jane Doe', jerseyNumber: '12', position: 'G', height: '5-9',
    academicYear: 'Jr.', hometown: 'Austin, Texas', lastSchool: 'Westlake HS',
  }]);
});

test('a page that prints the name above the label, not below it', () => {
  // Arizona redesigned into this shape in October 2026 and the roster went to
  // zero players: the line where the name used to be now holds "Position".
  const page = [
    'Players',
    'Jane Doe', 'Jersey Number', '2',
    'Position', 'G', 'Academic Year', 'Fr.', 'Height', "5' 10''",
    'Hometown', 'Rancho Cucamonga, Calif.', 'Last School', 'Etiwanda High School',
    'Mary Roe', 'Jersey Number', '3',
    'Position', 'F', 'Academic Year', 'Sr.', 'Height', "6' 1''",
    'Hometown', 'Warsaw, Poland',
  ].join('\n');
  assert.deepEqual(parseRoster(page), [
    { name: 'Jane Doe', jerseyNumber: '2', position: 'G', height: '5-10',
      academicYear: 'Fr.', hometown: 'Rancho Cucamonga, Calif.',
      lastSchool: 'Etiwanda High School' },
    { name: 'Mary Roe', jerseyNumber: '3', position: 'F', height: '6-1',
      academicYear: 'Sr.', hometown: 'Warsaw, Poland', lastSchool: '' },
  ]);
});

test('an inline nickname is dropped from the stored name', () => {
  // The box scores this height is matched against write the plain name, so a
  // nickname kept here would read nicely and never join to anything.
  const page = [
    'Jersey Number', '2', 'Arynn "Simba" Finley', 'Position', 'G', 'Height', "5' 10''",
  ].join('\n');
  assert.equal(parseRoster(page)[0].name, 'Arynn Finley');
});

test('the line above a block is never mistaken for a player', () => {
  // The trap in reading the name from above whenever the line below fails the
  // name test: on an ordinary page the line above is "Full Bio", which reads
  // as a name. Only a label in the name's place may send us upwards — and here
  // the line below is a real (quoted) name, so it must be used as-is.
  const page = [
    'Jersey Number', '12', 'Jane Doe', 'Position', 'G', 'Height', "5' 9''",
    'Full Bio',
    'Jersey Number', '33', 'Mary "Mo" Roe', 'Position', 'F/C', 'Height', "6' 4''",
    'Full Bio',
  ].join('\n');
  const got = parseRoster(page);
  assert.deepEqual(got.map((p) => p.name), ['Jane Doe', 'Mary Roe']);
  assert.equal(got.some((p) => /full bio/i.test(p.name)), false);
});

test('page furniture above a block is never read as the player', () => {
  // The hole the label gate alone left open: when the name really is above,
  // nothing checked that the line up there was a person. A page that closes
  // each player with a bio link puts "Full Bio" exactly where the name would
  // be, and a dozen of those clear the scraper's minimum-roster floor — so the
  // page records as a success and the junk reaches the screen.
  const page = [
    'Jane Doe', 'Full Bio', 'Jersey Number', '12', 'Position', 'G', 'Height', "5' 9''",
    'Mary Roe', 'Full Bio', 'Jersey Number', '33', 'Position', 'F', 'Height', "6' 4''",
    'Sue Poe', 'Full Bio', 'Jersey Number', '44', 'Position', 'C', 'Height', "6' 6''",
  ].join('\n');
  assert.deepEqual(parseRoster(page), []);
});

test('a block with no name does not borrow the line above it', () => {
  // On a names-below page, the line above a block is the previous player's
  // last field value. Here that is "Westlake High School", which reads as a
  // name; the label two above it is what gives it away.
  const page = [
    'Jersey Number', '12', 'Jane Doe', 'Position', 'G', 'Height', "5' 9''",
    'Hometown', 'Austin, Texas', 'Last School', 'Westlake High School',
    'Jersey Number', '33', 'Position', 'F', 'Height', "6' 4''",
  ].join('\n');
  assert.deepEqual(parseRoster(page).map((p) => p.name), ['Jane Doe']);
});

test('a quoted line is not a name, and does not displace one', () => {
  // Admitting quotes so nicknames survive also made a pull-quote look
  // name-shaped. Cleaning empties it, so it would have been stored as a player
  // with no name — and taken the real name's place in the window.
  assert.deepEqual(
    parseRoster(['Guard 5\'6"', '4', '"Hard work pays off"', 'Mary Roe'].join('\n'))
      .map((p) => p.name),
    ['Mary Roe']
  );
  assert.deepEqual(
    parseRoster('0\t"Bear Down Cats"\tFaith Jordan\tG\t5\' 11\'\'\tFr.').map((p) => p.name),
    ['Faith Jordan']
  );
});

test('a stray unbalanced quote is not a name', () => {
  // stripNickname needs both marks, so this survives cleaning — and a name
  // carrying a quote would never match the same player's box score line.
  const page = ['Jersey Number', '12', 'Mary "Mo Roe', 'Position', 'G', 'Height', "5' 9''"];
  assert.deepEqual(parseRoster(page.join('\n')), []);
});

test('the real Arizona page parses to its whole roster', () => {
  // A frozen copy of the page that broke: scraped/roster-miss-arizona.txt is
  // deleted the moment the site parses again, so the evidence is kept here.
  const page = fs.readFileSync(
    new URL('./fixtures/arizona-roster-2026-10-21.txt', import.meta.url), 'utf8'
  );
  const got = parseRoster(page);
  assert.equal(got.length, 12);
  // Every player, with a height — a partial roster is worse than none.
  assert.equal(got.filter((p) => p.height).length, 12);
  assert.equal(got.filter((p) => p.position).length, 12);
  // Both nicknamed players survive, under their plain names.
  assert.ok(got.some((p) => p.name === 'Arynn Finley'));
  assert.ok(got.some((p) => p.name === 'Aliyahna Morris'));
  // The bios the page carries come with them.
  const finley = got.find((p) => p.name === 'Arynn Finley');
  assert.equal(finley.hometown, 'Rancho Cucamonga, Calif.');
  assert.equal(finley.lastSchool, 'Etiwanda High School');
  assert.equal(finley.academicYear, 'Fr.');
  // "Previous School: UC San Diego" has its own label stripped back off.
  assert.equal(got.find((p) => p.name === 'Sumayah Sugapong').lastSchool,
    'La Jolla Country Day');
  assert.equal(got.some((p) => /full bio|^position$/i.test(p.name)), false);
});

test('staff and other people on the page are not mistaken for players', () => {
  // A height is what separates a roster entry from the many other objects an
  // athletics site ships, all of which carry names.
  assert.deepEqual(playersFromJson([{ staff: [{ name: 'Coach Smith', title: 'Head Coach' }] }]), []);
  assert.deepEqual(playersFromJson([]), []);
  assert.deepEqual(playersFromJson(null), []);
});
