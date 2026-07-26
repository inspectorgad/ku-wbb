// Temporary probe for the Big 12 standings / national rankings feature.
// Answers, with evidence committed under probe-big12/:
//   1. does /standings/basketball-women/d1 work (volleyball's returns 500)?
//   2. which rankings slugs exist for women's basketball (AP poll, NET,
//      coaches poll?) and what shape/season does each serve?
//   3. do scoreboard entries carry big-12 conference tags and rank fields on
//      conference-play dates, so standings can be computed from the sweep?
import fs from 'fs';

const API = 'https://ncaa-api.henrygd.me';
const OUT = 'probe-big12';
fs.mkdirSync(OUT, { recursive: true });

const summary = [];
const note = (s) => { summary.push(s); console.log(s); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function grab(name, url) {
  try {
    const resp = await fetch(url, { headers: { accept: 'application/json' } });
    const body = await resp.text();
    note(`GET ${url} -> ${resp.status} (${body.length} chars)`);
    fs.writeFileSync(`${OUT}/${name}`, `// ${url}\n${body.slice(0, 400_000)}`);
    await sleep(1200); // public instance: 5 req/s cap, stay well under
    return resp.ok ? body : null;
  } catch (e) {
    note(`GET ${url} -> ERROR ${e.message}`);
    await sleep(1200);
    return null;
  }
}

// --- 1. Standings endpoint --------------------------------------------------
const standings = await grab('standings-d1.json', `${API}/standings/basketball-women/d1`);
if (standings) {
  note(`  standings mentions Big 12: ${/big[\s-]?12/i.test(standings)}`);
  try {
    const j = JSON.parse(standings);
    note(`  standings top-level keys: ${Object.keys(j).join(', ')}`);
  } catch { note('  standings did not parse as JSON'); }
}

// --- 2. Rankings slug candidates for women's basketball ---------------------
for (const type of [
  'associated-press',
  'ncaa-womens-basketball-net-rankings',
  'net-rankings',
  'usa-today-coaches',
  'wbca-coaches-poll',
]) {
  const body = await grab(`rankings-${type}.json`, `${API}/rankings/basketball-women/d1/${type}`);
  if (!body) continue;
  try {
    const j = JSON.parse(body);
    note(`  ${type} keys: ${Object.keys(j).join(', ')}`);
    const rows = j.data ?? j.rankings ?? [];
    note(`  ${type} row count: ${Array.isArray(rows) ? rows.length : 'n/a'}`);
    if (Array.isArray(rows) && rows.length) {
      note(`  ${type} first row: ${JSON.stringify(rows[0]).slice(0, 240)}`);
    }
    // Which season/week does it describe? Decides whether 2025-26 is reproducible.
    for (const k of ['title', 'updated', 'page', 'sport', 'division', 'week', 'season']) {
      if (j[k] !== undefined) note(`  ${type} ${k}: ${JSON.stringify(j[k]).slice(0, 120)}`);
    }
  } catch { note(`  ${type} did not parse as JSON`); }
}

// --- 3. Conference tags on conference-play vs non-conference dates ----------
// 2026-01-14 was Oklahoma St. at Kansas, mid Big 12 play; 2025-11-19 non-conf.
for (const date of ['2026-01-14', '2025-11-19']) {
  const [y, m, d] = date.split('-');
  const body = await grab(`scoreboard-${date}.json`, `${API}/scoreboard/basketball-women/d1/${y}/${m}/${d}`);
  if (!body) continue;
  try {
    const games = (JSON.parse(body).games ?? []).map((w) => w.game ?? w);
    const confOf = (side) => (side?.conferences ?? []).map((c) => c.conferenceSeo);
    let b12Teams = new Set(), b12VsB12 = 0, ranked = 0;
    for (const g of games) {
      const hc = confOf(g.home), ac = confOf(g.away);
      if (hc.includes('big-12')) b12Teams.add(g.home.names.short);
      if (ac.includes('big-12')) b12Teams.add(g.away.names.short);
      if (hc.includes('big-12') && ac.includes('big-12')) b12VsB12++;
      for (const s of [g.home, g.away]) if (s?.rank) ranked++;
    }
    note(`  ${date}: ${games.length} games | big-12 teams tagged: ${b12Teams.size} | big12-vs-big12: ${b12VsB12} | ranked entries: ${ranked}`);
    note(`  ${date} big-12 teams: ${[...b12Teams].sort().join(', ')}`);
  } catch (e) { note(`  ${date} parse error: ${e.message}`); }
}

fs.writeFileSync(`${OUT}/summary.txt`, summary.join('\n') + '\n');
console.log('probe complete');
