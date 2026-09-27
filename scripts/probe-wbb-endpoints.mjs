// Round 2: shape detail for the endpoints round 1 proved exist.
import fs from 'fs';

const API = 'https://ncaa-api.henrygd.me';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function get(path) {
  const resp = await fetch(`${API}/${path}`, { headers: { accept: 'application/json' } });
  const text = await resp.text();
  await sleep(1200);
  console.log(`\n=== ${resp.status}  /${path}  (${text.length} bytes)`);
  try { return { status: resp.status, json: JSON.parse(text) }; }
  catch { console.log(`    non-JSON: ${text.slice(0, 300)}`); return { status: resp.status, json: null }; }
}

// --- 1. play-by-play detail ------------------------------------------------
{
  const { json } = await get('game/6515413/play-by-play');
  const periods = json?.periods ?? [];
  console.log(`    periods: ${periods.length} -> ${periods.map((p) => p.periodDisplay).join(', ')}`);
  const plays = periods.flatMap((p) => p.playbyplayStats ?? []);
  console.log(`    total plays: ${plays.length}`);
  console.log(`    play record keys: ${JSON.stringify(Object.keys(plays[0] ?? {}))}`);
  for (const p of plays.slice(0, 3)) console.log(`    play: ${JSON.stringify(p)}`);
  const scoring = plays.find((p) => p.homeScore > 0 || p.visitorScore > 0);
  console.log(`    first scoring play: ${JSON.stringify(scoring)}`);
  const last = plays[plays.length - 1];
  console.log(`    last play: ${JSON.stringify(last)}`);
  // Which distinct "event" values appear — tells us if plays are typed.
  const events = [...new Set(plays.map((p) => p.event).filter(Boolean))];
  console.log(`    distinct event values (${events.length}): ${JSON.stringify(events.slice(0, 25))}`);
}

// --- 2. stats pagination + whether KU players appear ------------------------
{
  const p1 = await get('stats/basketball-women/d1/current/individual/102');
  console.log(`    title=${p1.json?.title} pages=${p1.json?.pages} rows=${p1.json?.data?.length}`);
  console.log(`    row keys: ${JSON.stringify(Object.keys(p1.json?.data?.[0] ?? {}))}`);
  console.log(`    row 1: ${JSON.stringify(p1.json?.data?.[0])}`);
  const p2 = await get('stats/basketball-women/d1/current/individual/102/p2');
  console.log(`    /p2 rows=${p2.json?.data?.length} first=${JSON.stringify(p2.json?.data?.[0])}`);
  const p2q = await get('stats/basketball-women/d1/current/individual/102?page=2');
  console.log(`    ?page=2 page=${p2q.json?.page} rows=${p2q.json?.data?.length} first=${JSON.stringify(p2q.json?.data?.[0])}`);
  const ku = (p1.json?.data ?? []).filter((r) => r.Team === 'Kansas');
  console.log(`    Kansas rows on page 1 of PPG: ${JSON.stringify(ku)}`);
}

// --- 3. standings: conference coverage -------------------------------------
{
  const { json } = await get('standings/basketball-women/d1');
  const confs = (json?.data ?? []).map((d) => d.conference);
  console.log(`    updated: ${json?.updated}`);
  console.log(`    conferences (${confs.length}): ${JSON.stringify(confs)}`);
  const big12 = (json?.data ?? []).find((d) => /big 12/i.test(d.conference || ''));
  console.log(`    Big 12 row keys: ${JSON.stringify(Object.keys(big12?.standings?.[0] ?? {}))}`);
  console.log(`    Big 12 standings: ${JSON.stringify(big12?.standings)}`);
}

// --- 4. history base (the 422 error names it as a valid base) --------------
await get('history/basketball-women/d1');
await get('standings/basketball-women/d1/big-12');

// --- 5. scoreboard entry detail for a KU game ------------------------------
{
  const { json } = await get('scoreboard/basketball-women/d1/2025/11/05');
  const games = (json?.games ?? []).map((w) => w.game ?? w);
  const ku = games.find((g) => [g.home, g.away].some((s) => s?.names?.seo === 'kansas'));
  console.log(`    games that day: ${games.length}`);
  console.log(`    KU entry: ${JSON.stringify(ku)}`);
}

fs.mkdirSync('probe', { recursive: true });
console.log('\nround 2 done');
