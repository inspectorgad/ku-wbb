// Reads kuathletics.com's schedule page (as rendered text, one line per line)
// into fixtures. Lives apart from the scraper so it can be tested against a
// saved page — scripts/schedule-parser.test.mjs runs it on a frozen copy —
// because the only other way to find out a change broke it is a scrape that
// quietly finds fewer games.
//
// The page carries two independent listings. The site-wide rotator states the
// year outright but covers only the next few weeks and names no venue; the
// schedule table below it covers the whole season with venue, city and TV but
// has to infer the year. Both are read and merged.

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July',
  'August', 'September', 'October', 'November', 'December'];

// "6 p.m. CT", "12:30 p.m. CT". A row with no tip time yet (conference games
// before the TV windows are set) has none of these, and gets null rather than
// a guessed hour.
const TIME = /^(\d{1,2})(?::(\d{2}))?\s*([ap])\.m\.\s*(CT|ET|MT|PT)?$/i;

/** "6:30 p.m." -> "18:30", "11 a.m." -> "11:00"; null for anything else. */
export function to24h(text) {
  const m = TIME.exec((text || '').trim());
  if (!m) return null;
  let h = Number(m[1]) % 12;
  if (m[3].toLowerCase() === 'p') h += 12;
  return `${String(h).padStart(2, '0')}:${m[2] ?? '00'}`;
}

// kuathletics writes cities AP-style ("Lawrence, Kan."); NCAA box scores use
// postal codes ("Lawrence, KS"). Normalizing here keeps one place from being
// spelled two ways in the same table once a fixture becomes a result. An
// abbreviation that is not in the map is left exactly as written.
const POSTAL = {
  Ala: 'AL', Alaska: 'AK', Ariz: 'AZ', Ark: 'AR', Calif: 'CA', Colo: 'CO',
  Conn: 'CT', Del: 'DE', Fla: 'FL', Ga: 'GA', Hawaii: 'HI', Idaho: 'ID',
  Ill: 'IL', Ind: 'IN', Iowa: 'IA', Kan: 'KS', Ky: 'KY', La: 'LA',
  Maine: 'ME', Md: 'MD', Mass: 'MA', Mich: 'MI', Minn: 'MN', Miss: 'MS',
  Mo: 'MO', Mont: 'MT', Neb: 'NE', Nev: 'NV', Ohio: 'OH', Okla: 'OK',
  Ore: 'OR', Pa: 'PA', Tenn: 'TN', Texas: 'TX', Utah: 'UT', Vt: 'VT',
  Va: 'VA', Wash: 'WA', Wis: 'WI', Wyo: 'WY',
  'N.C.': 'NC', 'N.D.': 'ND', 'N.H.': 'NH', 'N.J.': 'NJ', 'N.M.': 'NM',
  'N.Y.': 'NY', 'R.I.': 'RI', 'S.C.': 'SC', 'S.D.': 'SD', 'W. Va.': 'WV',
  'D.C.': 'DC',
};

/** "Sioux Falls, S.D." -> "Sioux Falls, SD"; unknown regions pass through. */
export function postalCity(text) {
  const m = /^(.*?),\s*(.+?)\.?$/.exec((text || '').trim());
  if (!m) return text || null;
  const region = m[2].trim();
  const code = POSTAL[region] ?? POSTAL[`${region}.`] ?? POSTAL[region.replace(/\.$/, '')];
  return code ? `${m[1]}, ${code}` : text.trim();
}

export function parseSchedule(schedText, { stripRank = (s) => s } = {}) {
  const fixtures = [];

  // Source 1 — the rotator's accessibility labels:
  // "Upcoming Event: Women's Basketball versus Omaha on November 3, 2026 at 6:30 p.m. CT"
  const re = /Upcoming Event: Women's Basketball (versus|at) (.+?) on ([A-Z][a-z]+) (\d{1,2}), (\d{4})(?: at ([^\n]+?))?\s*$/gm;
  for (const m of schedText.matchAll(re)) {
    const month = MONTHS.indexOf(m[3]) + 1;
    if (month === 0) continue;
    const date = `${m[5]}-${String(month).padStart(2, '0')}-${String(m[4]).padStart(2, '0')}`;
    fixtures.push({
      date,
      opponent: stripRank(m[2]),
      away: m[1] === 'at',
      time: to24h(m[6]),
      venue: null,
      city: null,
      tv: null,
      event: null,
    });
  }

  // Source 2 — the schedule table's rows, which cover the whole season. Each
  // row puts the side and opponent BEFORE its date and the time after:
  //     MarketBeat Invitational / vs / Nebraska / Sanford Pentagon /
  //     Sioux Falls, S.D. / TV: BTN+ / Nov 14 / (Sat) / 3:30 p.m. CT / History
  //
  // A basketball season spans two calendar years, so the page title gives the
  // first ("2026-27 Women's Basketball Schedule") and Jan-Jul belongs to the
  // year after it.
  const seasonYear = Number(/\b(20\d{2})\b/.exec(schedText)?.[1]) || new Date().getUTCFullYear();
  const SHORT = /^(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) (\d{1,2})$/;
  // "Lawrence, Kan." / "Cancun, Mexico" — a place, then a region, nothing else.
  const CITY = /^[A-Z][A-Za-z.'\- ]*, ?[A-Z][A-Za-z. ]*\.?$/;
  const lines = schedText.split('\n').map((l) => l.trim());
  for (let i = 0; i < lines.length; i++) {
    if (lines[i] !== 'vs' && lines[i] !== 'at') continue;
    let j = i + 1;
    while (j < lines.length && !lines[j]) j++;
    const opponent = stripRank(lines[j]);
    if (!opponent) continue;
    // The date follows within the venue/TV block; a bounded look-ahead keeps a
    // row without one from adopting the next row's date.
    let found = null;
    let dateAt = -1;
    for (let k = j + 1; k < Math.min(j + 14, lines.length); k++) {
      const m = SHORT.exec(lines[k]);
      if (m) { found = m; dateAt = k; break; }
    }
    if (!found) continue;
    const month = MONTHS.findIndex((name) => name.startsWith(found[1])) + 1;
    if (month === 0) continue;
    const year = month >= 8 ? seasonYear : seasonYear + 1;

    // Between the opponent and the date sits the venue, the city, and an
    // optional broadcast note.
    const between = [];
    let tv = null;
    for (let k = j + 1; k < dateAt; k++) {
      const line = lines[k];
      if (!line) continue;
      if (line.startsWith('TV:')) tv = line.slice(3).trim() || null;
      else between.push(line);
    }
    // The tip time sits just after the date and its weekday. Looked for in the
    // next few lines only, so a row with no time cannot borrow the one below.
    let time = null;
    for (let k = dateAt + 1; k < Math.min(dateAt + 5, lines.length); k++) {
      if (SHORT.test(lines[k]) || lines[k] === 'vs' || lines[k] === 'at') break;
      const t = to24h(lines[k]);
      if (t) { time = t; break; }
    }
    // A tournament name sits on the line above "vs"/"at" when there is one
    // ("Cancun Challenge"). Anything that is obviously page furniture is not.
    const above = lines[i - 1] || '';
    const event = above && !/^(History|Skip Ad|MENU|Explore |Sponsored|Add To|Text Only|Live Stats)/.test(above) &&
      !SHORT.test(above) && !CITY.test(above) && above.length < 60 && /[A-Za-z]/.test(above)
      ? above : null;

    const cityAt = between.findIndex((line) => CITY.test(line));
    fixtures.push({
      date: `${year}-${String(month).padStart(2, '0')}-${String(found[2]).padStart(2, '0')}`,
      opponent,
      away: lines[i] === 'at',
      city: cityAt >= 0 ? postalCity(between[cityAt]) : null,
      venue: cityAt > 0 ? between[cityAt - 1] : null,
      time,
      tv,
      event,
    });
  }

  // De-dup: the rotator repeats on every page view, and its fixtures also
  // appear in the table. The first sighting sets the date and the side; either
  // source may fill in a field the other lacks.
  const byKey = new Map();
  for (const f of fixtures) {
    const k = `${f.date}|${f.opponent.toLowerCase()}`;
    const prev = byKey.get(k);
    if (!prev) { byKey.set(k, f); continue; }
    for (const field of ['city', 'venue', 'time', 'tv', 'event']) {
      if (!prev[field] && f[field]) prev[field] = f[field];
    }
  }
  return [...byKey.values()];
}

/**
 * Home, away or neutral for a fixture.
 *
 * The page's own "vs"/"at" says whether KU is the visiting side, but not
 * whether the host is the opponent: KU plays "vs Nebraska" at the Sanford
 * Pentagon in Sioux Falls, which is neither team's floor. So "at" means away,
 * and a "vs" anywhere other than KU's own building is a neutral floor.
 */
export function siteOf(fixture, homeVenue = 'Allen Fieldhouse') {
  if (fixture.away) return 'away';
  if (!fixture.venue) return 'home';  // rotator-only row; the table settles it
  return fixture.venue === homeVenue ? 'home' : 'neutral';
}
