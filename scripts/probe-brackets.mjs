// Temporary probe: how do conference-tournament and postseason games look on
// the scoreboard, so they can be excluded from conference records?
// 2026-03-06 = Big 12 Tournament quarterfinals; 2026-03-22 = NCAA tournament.
import fs from 'fs';

fs.mkdirSync('probe-brackets', { recursive: true });
const out = [];
const note = (s) => { out.push(s); console.log(s); };

for (const date of ['2026-03-06', '2026-02-25', '2026-03-22']) {
  const [y, m, d] = date.split('-');
  const resp = await fetch(`https://ncaa-api.henrygd.me/scoreboard/basketball-women/d1/${y}/${m}/${d}`);
  note(`${date} -> ${resp.status}`);
  if (!resp.ok) continue;
  const data = await resp.json();
  for (const wrap of data.games ?? []) {
    const g = wrap.game ?? wrap;
    const conf = (s) => (s?.conferences ?? []).map((c) => c.conferenceSeo);
    const b12 = conf(g.home).includes('big-12') || conf(g.away).includes('big-12');
    if (!b12) continue;
    note(`  ${g.away?.names?.short} at ${g.home?.names?.short} | bracketId=${JSON.stringify(g.bracketId)} bracketRound=${JSON.stringify(g.bracketRound)} title=${JSON.stringify(g.title)} url=${JSON.stringify(g.url)}`);
  }
  await new Promise((r) => setTimeout(r, 1200));
}
fs.writeFileSync('probe-brackets/summary.txt', out.join('\n') + '\n');
