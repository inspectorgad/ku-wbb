// Parses a basketball roster out of the rendered text of a college athletics
// roster page, for opposing teams. Ported from the KU volleyball app, which
// probed nine schools and found three distinct shapes; rather than sniff the
// site, every shape is tried and the one that yields the most players wins, so
// a school that redesigns into a different shape keeps working.
//
// This is the only source of an opposing player's height: the NCAA box scores
// carry a name, a number and a position and nothing else. Kansas'
// own roster has its own parser (roster-parser.mjs) because kuathletics
// publishes hometown and last school too, which these pages often do not.
//
//   labelled  Sidearm's default:
//               Jersey Number / 12 / Jane Doe / Position / G / Height / 6' 3''
//   card      unlabelled cards, name in caps:
//               1 / F / SARAH HICKMAN / 6'5"Senior / Houston, Texas...
//   header    position and height share the line above the number:
//               Forward 6'0" / 1 / Jane Doe / Sophomore Columbia, Mo.
//   row       one player per line, tab-separated:
//               0 / Faith Jordan / G / 5' 11'' / Fr. / Joliet, Illinois
// Heights appear as 6' 3'', 6'3", or 6′3″ — straight quotes, doubled
// apostrophes, and Unicode primes all mean the same thing.
/**
 * Bumped whenever this file learns to read something it could not read before.
 *
 * A stored roster is reused for a week, which is right while the parser is
 * fixed but wrong the moment it improves: the page has not changed, so nothing
 * in the timestamp says the stored players are now missing fields that could
 * be had. The cache carries this number and refetches when it moves, so a fix
 * here reaches the data on the next run rather than over the following week.
 *
 *   1  heights only
 *   2  the Arizona layout (name above the label), inline nicknames, and the
 *      hometown / academic year / last school the labelled shape walked past
 */
export const PARSER_VERSION = 2;

const HEIGHT = /(\d)\s*['‘’′]\s*(\d{1,2})?\s*(?:''|"|”|″)?/;
// Some sites drop the quote marks entirely and write 6-4. Anchored, so a year
// range like 2025-26 is not mistaken for a height.
const PLAIN_HEIGHT = /^([4-7])-(\d{1,2})$/;

export function normalizeHeight(raw) {
  const plain = PLAIN_HEIGHT.exec((raw || '').trim());
  if (plain && Number(plain[2]) <= 11) return `${plain[1]}-${plain[2]}`;
  const m = HEIGHT.exec(raw || '');
  return m ? `${m[1]}-${m[2] ?? 0}` : '';
}

// Letters, not just A-Z: Arizona St. field Una Vajagić and Živa Labinjan, and
// an ASCII-only name test skipped both — reading their roster as empty rather
// than as eighteen players with two missing.
//
const NAME = /^[\p{L}'.’-]+(?: [\p{L}'.’-]+)+$/u;

/**
 * Whether this line is a player's name — judged on the name as it would be
 * STORED, not as it is printed.
 *
 * The two differ because schools print nicknames inline (Arizona lists Arynn
 * "Simba" Finley) and [stripNickname] takes them out. Testing the raw line
 * instead lets through anything the quotes made look name-shaped: a quoted
 * pull-quote on the page reduces to an empty string once cleaned, and would
 * be stored as a player with no name at all — worse than being skipped, since
 * it displaces the real name the surrounding shapes would otherwise find.
 */
const isName = (s) => NAME.test(cleanName(s));

/**
 * Drops an inline nickname: `Arynn "Simba" Finley` -> `Arynn Finley`.
 *
 * The nickname is kept out of the stored name deliberately. Every other source
 * this app joins against — the NCAA box scores above all — writes the plain
 * name, and heights are matched on the name alone, so carrying the nickname
 * would read nicely on a roster card and silently fail to match the same
 * player's box score line.
 */
function stripNickname(s) {
  return (s || '').replace(/["“][^"”]*["”]/g, ' ').replace(/\s+/g, ' ').trim();
}

/**
 * Link text and headings that sit between players on a roster page and read
 * as names. "Full Bio" is the one that matters — it closes a player's block on
 * Sidearm's default layout, which puts it directly above the next player's
 * "Jersey Number" label, exactly where [parseLabelled] looks for a name when
 * the page prints names above.
 */
const FURNITURE = new Set([
  'full bio', 'view bio', 'view profile', 'read more', 'player bio',
  'full profile', 'season stats', 'career stats',
]);
// UCF prints "#1" rather than "1"; the hash is decoration, not part of the number.
const isNumber = (s) => /^#?\d{1,2}$/.test((s || '').trim());
const numberOf = (s) => (s || '').trim().replace(/^#/, '');

// Spelled-out positions ("Point Guard") and the abbreviations ("G", "G/F").
// The abbreviation branch is unchanged from the volleyball original and already
// covers basketball's G, F, C, G/F and F/C; only the spelled-out words differ.
const POSITION = /^(?:[A-Z]{1,3}(?:\/[A-Z]{1,3})?|(?:Point|Shooting|Small|Power)\s+\w+|Guard|Forward|Center|Wing|Post)$/i;

// Cards often print names in caps. Fold those to title case, but leave a name
// that already carries its own capitalisation alone - McMillan, DeMaria.
function tidyName(s) {
  if (s !== s.toUpperCase()) return s;
  return s.replace(/\S+/g, (w) => w.charAt(0).toUpperCase() + w.slice(1).toLowerCase());
}

/** A name as it should be stored: nickname removed, shouting folded away. */
const cleanName = (s) => tidyName(stripNickname(s));

/**
 * The labels a Sidearm block can carry, lower-cased.
 *
 * Two things turn on this set. A block whose line two below "Jersey Number"
 * holds one of these has its name *above* the label rather than below it —
 * Arizona redesigned into that shape in October 2026 — and the field scan then
 * has to start at that label rather than one line further on.
 */
const LABELS = new Set([
  'position', 'height', 'academic year', 'class', 'year', 'hometown',
  'last school', 'previous school', 'high school', 'weight', 'experience',
  'major', 'jersey number',
]);

/**
 * One roster entry, in the shape every shape returns.
 *
 * The three bio fields are filled only by the labelled layout and by JSON —
 * the others print nothing but the measurements — so they default to empty
 * rather than being absent, and a caller can read `.hometown` off any entry
 * without checking which layout produced it.
 */
function entry({ name, number, position, height, academicYear, hometown, lastSchool }) {
  return {
    name: cleanName(name).trim(),
    jerseyNumber: numberOf(number),
    position: (position || '').trim(),
    height: height || '',
    academicYear: (academicYear || '').trim(),
    hometown: (hometown || '').trim(),
    lastSchool: (lastSchool || '').trim(),
  };
}

/**
 * Whether the line above a block can be the player's name.
 *
 * Reading a name from above is only ever a guess, and a wrong guess here is
 * expensive: a dozen fake players clear the scraper's minimum-roster floor, so
 * the page records as a success and the junk reaches the screen. Three things
 * rule the candidate out.
 *
 * @param above    the line directly above the "Jersey Number" label
 * @param twoAbove the line above that
 * @param counts   how many blocks each line sits above, from [parseLabelled]
 */
function plausibleNameAbove(above, twoAbove, counts) {
  const key = above.trim().toLowerCase();
  // A link or heading between players, named outright.
  if (FURNITURE.has(key)) return false;
  // A label immediately above it means the candidate is that label's VALUE —
  // the previous block's last field, not a name. This is what a page whose
  // names sit below looks like when one block happens to have no name line.
  if (LABELS.has(twoAbove.trim().toLowerCase())) return false;
  // And whatever it is, a name belongs to one player. Anything standing above
  // more than one block is part of the page, not part of the team.
  return (counts.get(key) || 0) <= 1;
}

/** The labels worth keeping a value from, as opposed to merely recognising. */
const WANTED = new Set([
  'position', 'height', 'academic year', 'class', 'year', 'hometown',
  'last school', 'previous school',
]);

/** Sidearm's labelled blocks: scan each player's block for the labels we want. */
function parseLabelled(lines) {
  const roster = [];
  // How often each line sits directly above a block. A player's name is there
  // once; a link or heading repeated between every player is there many times,
  // and that count is what tells the two apart on a page whose furniture this
  // file has never seen.
  const aboveCount = new Map();
  for (let i = 1; i < lines.length; i++) {
    if (lines[i].toLowerCase() !== 'jersey number') continue;
    const key = lines[i - 1].trim().toLowerCase();
    aboveCount.set(key, (aboveCount.get(key) || 0) + 1);
  }
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].toLowerCase() !== 'jersey number') continue;
    const number = lines[i + 1] || '';
    if (!isNumber(number)) continue;
    // Sidearm's default prints the name two lines below the label; Arizona
    // prints it on the line above, so what sits at i+2 there is "Position".
    // The two are told apart by whether a *label* occupies the name's place.
    // Gating on that, rather than on "the line below does not look like a
    // name", is what keeps the fallback safe: a real name the test rejects
    // would otherwise send us to the line above, which on an ordinary page is
    // "Full Bio" — the link closing the previous player's block, and a string
    // that reads as a name.
    const below = lines[i + 2] || '';
    const nameAbove = LABELS.has(below.trim().toLowerCase());
    const above = lines[i - 1] || '';
    if (nameAbove && !plausibleNameAbove(above, lines[i - 2] || '', aboveCount)) continue;
    const name = nameAbove ? above : below;
    if (!isName(name)) continue;
    const fields = {};
    for (let j = nameAbove ? i + 2 : i + 3;
      j < lines.length && lines[j].toLowerCase() !== 'jersey number'; j++) {
      const label = lines[j].toLowerCase();
      if (WANTED.has(label)) fields[label] = (lines[j + 1] || '').trim();
    }
    roster.push(entry({
      name,
      number,
      position: fields.position,
      height: normalizeHeight(fields.height),
      academicYear: fields['academic year'] || fields.class || fields.year,
      hometown: fields.hometown,
      // "Last School" is Sidearm's own label. Where a page carries only
      // "Previous School", its value repeats the label back at you —
      // "Previous School: UC San Diego" — so that prefix comes off.
      lastSchool: fields['last school']
        || (fields['previous school'] || '').replace(/^previous school:\s*/i, ''),
    }));
  }
  return roster;
}

/**
 * Unlabelled cards: a number, then the name, position and height in some order
 * — Stanford lists position first, Cincinnati the name — so the three lines
 * after the number are identified by what they look like rather than by place.
 */
function parseCards(lines) {
  const roster = [];
  for (let i = 0; i + 3 < lines.length; i++) {
    if (!isNumber(lines[i])) continue;
    const window = lines.slice(i + 1, i + 4);
    // Requiring all three - a person's name, a volleyball position and a
    // height, on three distinct lines - is what separates a real card from
    // unrelated lines that happen to sit together. Case is not a reliable
    // signal: Stanford shouts the name, UCF does not.
    // Arizona St. prints the name above the number rather than below it, and
    // its hometown ("San Diego") reads as a name too, so preferring the window
    // would file every player under their home town. What settles it is that
    // the card repeats the real name just below as image alt text - a
    // duplicate the hometown never has.
    const above = i > 0 && isName(lines[i - 1]) ? lines[i - 1] : undefined;
    const repeated = above && lines.slice(i + 1, i + 7).includes(above);
    const name = repeated ? above : (window.find(isName) ?? above);
    const position = window.find((l) => l !== name && POSITION.test(l));
    const height = window.filter((l) => l !== name && l !== position)
      .map(normalizeHeight).find(Boolean);
    if (!name || !position || !height) continue;
    roster.push(entry({ name, number: lines[i], position, height }));
  }
  return roster;
}

/** Position and height on the line above the number, then the name. */
function parseHeader(lines) {
  const roster = [];
  for (let i = 0; i + 2 < lines.length; i++) {
    const header = lines[i];
    // This shape needs the position and the height on one line, so it wants the
    // quoted form specifically — a line holding nothing but a bare "6-4" has no
    // position in front of it to split off.
    const quoted = HEIGHT.exec(header);
    if (!quoted) continue;
    const height = normalizeHeight(header);
    if (!height) continue;
    const position = header.slice(0, quoted.index).trim();
    if (!position || !POSITION.test(position)) continue;
    if (!isNumber(lines[i + 1])) continue;
    // A blank line or two can sit between the number and the name.
    const name = [lines[i + 2], lines[i + 3], lines[i + 4]].find(isName);
    if (!name) continue;
    roster.push(entry({ name, number: lines[i + 1], position, height }));
  }
  return roster;
}

/**
 * A tab-separated table: the number and name on their own lines, then one line
 * carrying position, height, class and hometown. Heights here are already
 * written "6-4", with no quote marks, so they need their own match.
 */
function parseTable(lines) {
  const roster = [];
  for (let i = 0; i + 2 < lines.length; i++) {
    if (!isNumber(lines[i].replace(/\t+$/, ''))) continue;
    const name = lines[i + 1];
    if (!isName(name)) continue;
    const cells = lines[i + 2].split('\t').map((c) => c.trim()).filter(Boolean);
    const heightCell = cells.find((c) => {
      const m = PLAIN_HEIGHT.exec(c);
      return m && Number(m[2]) <= 11;
    });
    if (!heightCell) continue;
    const position = cells[cells.indexOf(heightCell) - 1] ?? '';
    if (!position) continue;
    roster.push(entry({
      name, number: lines[i].replace(/\t+$/, ''), position, height: heightCell,
    }));
  }
  return roster;
}

/**
 * One player per line, tab-separated (Texas Tech):
 *   0 \t Faith Jordan \t MB \t 5' 11'' \t Fr. \t Joliet, Illinois / West Joliet HS
 *
 * Distinct from parseTable above, which wants the number and name on their own
 * lines. Cells are matched by what they look like rather than by position,
 * because schools order the columns differently and some add or drop one.
 */
function parseRows(lines) {
  const roster = [];
  for (const line of lines) {
    if (!line.includes('\t')) continue;
    const cells = line.split('\t').map((c) => c.trim()).filter(Boolean);
    // Number, name, position and height at least; a row shorter than that is
    // a heading or a stray pair of cells.
    if (cells.length < 4 || !isNumber(cells[0])) continue;
    const name = cells.find(isName);
    if (!name) continue;
    const position = cells.find((c) => c !== name && POSITION.test(c));
    if (!position) continue;
    const height = cells.filter((c) => c !== name && c !== position)
      .map(normalizeHeight).find(Boolean);
    if (!height) continue;
    roster.push(entry({ name, number: cells[0], position, height }));
  }
  return roster;
}

// --- Rosters that never reach the page as text -------------------------------
// Texas Tech and Arizona St. serve a page shell and fetch the roster afterwards
// — Texas Tech from an API, Arizona St. into a Nuxt state blob — so there is no
// rendered text to parse and no markup either. Guessing each school's endpoint
// would be a per-site hack that rots; instead the caller hands over whatever
// JSON the page fetched, and a player is recognised by its fields.
//
// Field names vary by platform, so each is matched by a set of aliases with
// punctuation and case ignored (jersey_number, jerseyNumber and Jersey all
// read the same).
const FIELDS = {
  name: ['name', 'fullname', 'playername', 'title'],
  first: ['firstname'],
  last: ['lastname'],
  number: ['jerseynumber', 'jersey', 'uniformnumber', 'uniform', 'number'],
  position: ['positionshort', 'position', 'pos'],
  height: ['height', 'heightformatted'],
  academicYear: ['academicyear', 'classyear', 'class', 'yearformatted', 'eligibility'],
  hometown: ['hometown', 'hometownformatted'],
  lastSchool: ['lastschool', 'previousschool', 'highschool'],
};

function pick(obj, aliases) {
  for (const key of Object.keys(obj)) {
    const flat = key.toLowerCase().replace(/[^a-z]/g, '');
    if (aliases.includes(flat) && obj[key] != null && obj[key] !== '') {
      return String(obj[key]);
    }
  }
  return '';
}

/**
 * A player, if this object looks like one. A height is required: it is the
 * field that separates a roster entry from the many other objects an athletics
 * site ships (staff, news, opponents), which carry names but no measurements.
 */
function playerFromObject(o) {
  if (!o || typeof o !== 'object' || Array.isArray(o)) return null;
  const height = normalizeHeight(pick(o, FIELDS.height));
  if (!height) return null;
  const name = pick(o, FIELDS.name)
    || `${pick(o, FIELDS.first)} ${pick(o, FIELDS.last)}`.trim();
  if (!isName(name)) return null;
  return entry({
    name,
    number: pick(o, FIELDS.number),
    position: pick(o, FIELDS.position),
    height,
    academicYear: pick(o, FIELDS.academicYear),
    hometown: pick(o, FIELDS.hometown),
    lastSchool: pick(o, FIELDS.lastSchool),
  });
}

/**
 * Best roster found in any of the JSON payloads a page fetched. Each array in
 * each payload is a candidate; the one yielding the most players wins, on the
 * same reasoning as the text shapes above.
 */
export function playersFromJson(payloads) {
  let best = [];
  const visit = (node, depth) => {
    if (depth > 12 || !node || typeof node !== 'object') return;
    if (Array.isArray(node)) {
      const players = node.map(playerFromObject).filter(Boolean);
      if (players.length > best.length) best = players;
      for (const item of node) visit(item, depth + 1);
      return;
    }
    for (const value of Object.values(node)) visit(value, depth + 1);
  };
  for (const payload of payloads || []) visit(payload, 0);
  return best;
}

/**
 * Best-effort roster from a page's innerText. Returns [] when nothing parses,
 * which callers treat as "no roster available" rather than an error.
 */
export function parseRoster(pageText) {
  const lines = (pageText || '').split('\n').map((l) => l.trim());
  const shapes = [parseLabelled, parseCards, parseHeader, parseTable, parseRows];
  let best = [];
  for (const shape of shapes) {
    let players = [];
    try {
      players = shape(lines);
    } catch {
      players = [];
    }
    // Same player can appear twice when a page repeats the list in two views.
    const seen = new Set();
    players = players.filter((p) => {
      const key = `${p.jerseyNumber}|${p.name.toLowerCase()}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
    if (players.length > best.length) best = players;
  }
  return best;
}
