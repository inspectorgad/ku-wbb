// Round 3: the two paths that returned 200 with bodies we never printed.
const API = 'https://ncaa-api.henrygd.me';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

for (const path of [
  'history/basketball-women/d1',
  'standings/basketball-women/d1/big-12',
  'scoreboard/basketball-women/d1/2026/07/04', // no games that day
]) {
  const resp = await fetch(`${API}/${path}`, { headers: { accept: 'application/json' } });
  const text = await resp.text();
  console.log(`\n=== ${resp.status}  /${path}  (${text.length} bytes)`);
  console.log(text.slice(0, 2000));
  await sleep(1200);
}
