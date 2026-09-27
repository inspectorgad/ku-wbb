// One-off endpoint probe: which NCAA API paths exist for basketball-women/d1.
//
// Interactive sessions cannot reach ncaa-api.henrygd.me (egress policy denies
// the CONNECT), so this runs in Actions like the other probes in this repo.
// It prints HTTP status plus a snippet of the real body for every candidate
// path, so the job log itself is the evidence.
import fs from 'fs';

const API = 'https://ncaa-api.henrygd.me';
const GAME = process.env.GAME_ID || '6515413';   // Kansas vs Kansas City, 2025-11-05, final
const GAME2 = process.env.GAME_ID2 || '6595192'; // at BYU, 2026-03-30, final
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const results = [];

async function probe(path, note = '') {
  const url = `${API}/${path}`;
  let entry = { path, note, status: null, ok: false, bytes: 0, keys: null, snippet: null, error: null };
  try {
    const resp = await fetch(url, { headers: { accept: 'application/json' } });
    entry.status = resp.status;
    entry.ok = resp.ok;
    entry.contentType = resp.headers.get('content-type');
    const text = await resp.text();
    entry.bytes = text.length;
    try {
      const json = JSON.parse(text);
      entry.json = json;
      entry.keys = Array.isArray(json) ? `[array len ${json.length}]` : Object.keys(json);
      entry.snippet = JSON.stringify(json).slice(0, 900);
    } catch {
      entry.snippet = text.slice(0, 400);
    }
  } catch (e) {
    entry.error = e.message;
  }
  results.push(entry);
  console.log(`\n=== ${entry.status ?? 'ERR'}  /${path}  ${note}`);
  if (entry.error) console.log(`    error: ${entry.error}`);
  else {
    console.log(`    content-type: ${entry.contentType}  bytes: ${entry.bytes}`);
    console.log(`    top-level keys: ${JSON.stringify(entry.keys)}`);
    console.log(`    snippet: ${entry.snippet}`);
  }
  await sleep(1200); // public instance caps at 5 req/s; stay well under
  return entry;
}

// --- per-game endpoints ----------------------------------------------------
for (const id of [GAME, GAME2]) {
  for (const suffix of ['', '/boxscore', '/play-by-play', '/pbp', '/team-stats',
                        '/scoring-summary', '/gameinfo', '/linescore']) {
    await probe(`game/${id}${suffix}`, `game ${id}`);
  }
}

// --- scoreboard ------------------------------------------------------------
await probe('scoreboard/basketball-women/d1/2025/11/05', 'day KU played');
await probe('scoreboard/basketball-women/d1/2026/03/30', 'day KU played (late season)');

// --- standings / rankings --------------------------------------------------
await probe('standings/basketball-women/d1', 'volleyball equivalent returns 500');
await probe('rankings/basketball-women/d1/associated-press', 'already used by the app');
await probe('rankings/basketball-women/d1/ncaa-womens-basketball-net-rankings', 'already used by the app');
await probe('rankings/basketball-women/d1', 'directory?');
await probe('rankings/basketball-women/d1/usa-today-coaches', 'coaches poll?');

// --- national statistics ---------------------------------------------------
const dir = await probe('stats/basketball-women/d1', 'category directory');
const cats = [];
for (const kind of ['individual', 'team']) {
  const list = Array.isArray(dir?.json?.[kind]) ? dir.json[kind] : [];
  console.log(`\n--- ${kind} categories advertised: ${list.length}`);
  for (const c of list) console.log(`      ${c.id}  ${c.name}  -> ${c.path}`);
  // Probe a handful per kind so the run stays short but proves the shape.
  for (const c of list.slice(0, 4)) cats.push({ kind, ...c });
}
for (const c of cats) {
  await probe(`stats/basketball-women/d1/current/${c.path}`, `${c.kind}: ${c.name}`);
}
// If the directory came back empty, try the volleyball-shaped guesses anyway.
if (!cats.length) {
  for (const p of ['stats/basketball-women/d1/current/individual/168',
                   'stats/basketball-women/d1/current/team/168',
                   'stats/basketball-women/d1/current']) {
    await probe(p, 'blind guess (directory was empty)');
  }
}

fs.mkdirSync('probe', { recursive: true });
fs.writeFileSync('probe/endpoint-probe.json', JSON.stringify({
  probedAt: new Date().toISOString(), api: API, gameIds: [GAME, GAME2],
  results: results.map(({ json, ...rest }) => rest),
}, null, 1));

console.log('\n\n================ SUMMARY ================');
for (const r of results) {
  console.log(`${String(r.status ?? 'ERR').padEnd(5)} ${r.bytes.toString().padStart(8)}b  /${r.path}`);
}
