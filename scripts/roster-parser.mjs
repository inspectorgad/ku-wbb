// Reads kuathletics.com's roster page (as rendered text, one line per line)
// into players. Lives apart from the scraper so it can be tested against a
// saved page — scripts/roster-parser.test.mjs runs it on a frozen copy —
// because a layout change otherwise shows up as a roster that quietly loses
// players, or loses the bio fields while keeping the names.
//
// Each player is a run of label/value pairs after "Jersey Number":
//
//     Jersey Number / 0 / Anna Gooden / Position / G / Academic Year / So. /
//     Height / 6' 0'' / Hometown / Fort Smith, Ark. /
//     Last School / Northside HS / Colorado / Full Bio ...
//
// The NCAA box scores cannot supply any of this — their `year` and `elig`
// fields are empty on every row — so this page is the only source.

const LABELS = new Set([
  'Position', 'Academic Year', 'Height', 'Hometown', 'Last School',
  'Weight', 'Experience', 'High School',
]);

/** "6' 0''" -> "6-0"; passes anything unrecognized through untouched. */
export function tidyHeight(text) {
  const m = /^(\d)'\s*(\d{1,2})(?:''|")?$/.exec((text || '').trim());
  return m ? `${m[1]}-${m[2]}` : (text || '').trim();
}

export function parseRoster(rosterText) {
  const lines = rosterText.split('\n').map((l) => l.trim());
  const players = [];

  for (let i = 0; i < lines.length; i++) {
    if (lines[i] !== 'Jersey Number') continue;
    const number = lines[i + 1] || '';
    const name = lines[i + 2] || '';
    // A jersey is one or two digits; a name is at least two words of letters.
    if (!/^\d{1,2}$/.test(number)) continue;
    if (!/^[A-Za-z'.À-ɏ-]+( [A-Za-z'.À-ɏ-]+)+$/.test(name)) continue;

    const player = { name, jerseyNumber: number };
    // Walk the label/value pairs until the next player or the bio link.
    for (let k = i + 3; k < lines.length - 1; k++) {
      const label = lines[k];
      if (label === 'Jersey Number' || label.startsWith('Full Bio')) break;
      if (!LABELS.has(label)) continue;
      const value = (lines[k + 1] || '').trim();
      if (!value) continue;
      switch (label) {
        case 'Position': player.position = value; break;
        case 'Academic Year': player.academicYear = value; break;
        case 'Height': player.height = tidyHeight(value); break;
        case 'Hometown': player.hometown = value; break;
        // "Northside HS / Colorado" — the schools before this one, most recent
        // last. Kept verbatim; it is a transfer path, not a single school.
        case 'Last School': player.lastSchool = value; break;
        default: break;
      }
      k++;  // the value line is consumed
    }
    players.push(player);
  }

  // The page repeats a player if the roster is rendered more than once.
  const byName = new Map();
  for (const p of players) {
    const prev = byName.get(p.name);
    if (!prev) { byName.set(p.name, p); continue; }
    for (const f of ['position', 'academicYear', 'height', 'hometown', 'lastSchool']) {
      if (!prev[f] && p[f]) prev[f] = p[f];
    }
  }
  return [...byName.values()];
}
