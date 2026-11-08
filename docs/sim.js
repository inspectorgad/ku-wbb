// The season simulator: plays the rest of the season out many times from the
// inputs the pipeline ships (seed.simulation, built by scripts/sim_inputs.py)
// and counts what happened.
//
// Deterministic on purpose. The same small random-number generator
// (mulberry32) from the same starting seed, drawing in the same order, means
// the page shows the same numbers on every open, and the app will show those
// same numbers when SeasonSimulator.kt mirrors this file. Keep them in step:
// sim.test.mjs pins the fixture both sides are checked against.

export const RUNS = 20000;
const SEED = 20271;

function mulberry32(seed) {
  let a = seed | 0;
  return () => {
    a = (a + 0x6D2B79F5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/**
 * inputs: seed.simulation. forced: {index: "W" | "L"} for the remaining games
 * the reader has decided. Returns the summary the screens show.
 */
export function simulate(inputs, forced = {}, runs = RUNS) {
  const rnd = mulberry32(SEED);
  const ku = inputs.kansas;
  const rem = inputs.remaining;
  const others = inputs.others;
  const scale = inputs.scale || 1;
  const index = new Map(others.map((o, i) => [o.key, i]));
  const n = others.length + 1;              // the whole conference, Kansas included

  // A game between two rated teams on a neutral floor, which is what the
  // conference tournament is played on.
  const pWin = (a, b) => 1 / (1 + Math.pow(10, -(a - b) / scale));

  const overall = new Array(rem.length + 1).fill(0);   // by wins over the rest
  let titles = 0, shares = 0, top4 = 0, top8 = 0, placeSum = 0, autoBid = 0;
  const seeds = [];
  const confWins = new Int32Array(others.length);
  // Scratch space reused across runs rather than reallocated 20,000 times.
  const order = new Array(n);
  const bracket = new Array(n + 1);

  for (let run = 0; run < runs; run++) {
    for (let i = 0; i < others.length; i++) confWins[i] = others[i].confW;
    let wins = 0, kuConf = ku.confW;

    // Kansas's own remaining games. The draw happens whether or not the
    // reader has forced this one, so forcing a result does not shift the
    // random numbers the rest of the season gets.
    for (let m = 0; m < rem.length; m++) {
      const draw = rnd();
      const f = forced[m];
      const won = f === "W" ? true : f === "L" ? false : draw < rem[m].p;
      if (won) {
        wins++;
        if (rem[m].conference) kuConf++;
      } else if (rem[m].conference) {
        const o = index.get(rem[m].key);
        if (o !== undefined) confWins[o]++;
      }
    }
    // Everyone else's remaining conference games, against an average opponent.
    for (let i = 0; i < others.length; i++) {
      const o = others[i];
      for (let g = 0; g < o.remaining; g++) if (rnd() < o.pVsAverage) confWins[i]++;
    }

    // Seeding. Every team gets a tiebreak draw, Kansas included, so the table
    // is a strict order: the Big 12's real tiebreakers start with head-to-head
    // and this simulation does not keep who beat whom.
    for (let i = 0; i < others.length; i++) {
      order[i] = { w: confWins[i], tie: rnd(), rating: others[i].rating, ku: false };
    }
    order[others.length] = { w: kuConf, tie: rnd(), rating: ku.rating, ku: true };
    order.sort((x, y) => (y.w - x.w) || (y.tie - x.tie));

    let place = 0, ahead = 0;
    for (let i = 0; i < n; i++) {
      bracket[i + 1] = order[i];                 // seeds are 1-based
      if (order[i].ku) place = i + 1;
      else if (order[i].w > kuConf) ahead++;
    }

    // The Big 12 tournament: all 16 in, seeds 1-4 on a double bye, 5-8 on a
    // single one, so the top seed needs four wins and the bottom eight need
    // five. Played on a neutral floor in Kansas City.
    const champion = playBracket(bracket, n, pWin, rnd);

    overall[wins]++;
    placeSum += place;
    if (place === 1) titles++;
    if (ahead === 0) shares++;                   // nobody finished above Kansas
    if (place <= 4) top4++;
    if (place <= 8) top8++;
    if (champion.ku) autoBid++;
    seeds.push(place);
  }

  seeds.sort((a, b) => a - b);
  const seedAt = (q) => seeds[Math.min(seeds.length - 1, Math.floor(q * seeds.length))];
  const winsAt = (q) => {
    let seen = 0;
    for (let k = 0; k < overall.length; k++) {
      seen += overall[k];
      if (seen > q * runs) return k;
    }
    return overall.length - 1;
  };
  const exp = overall.reduce((s, c, k) => s + c * k, 0) / runs;
  return {
    runs,
    games: ku.overallW + ku.overallL + rem.length,
    expectedWins: ku.overallW + exp,
    expectedLosses: ku.overallL + rem.length - exp,
    winsLow: ku.overallW + winsAt(0.1),
    winsMid: ku.overallW + winsAt(0.5),
    winsHigh: ku.overallW + winsAt(0.9),
    title: titles / runs,
    shareOfTitle: shares / runs,
    top4: top4 / runs,
    top8: top8 / runs,
    averagePlace: placeSum / runs,
    seedMid: seedAt(0.5),
    seedLow: seedAt(0.1),
    seedHigh: seedAt(0.9),
    autoBid: autoBid / runs,
  };
}

/**
 * One Big 12 tournament. bracket is 1-based by seed. Returns the winner.
 *
 * Seeds 9-16 play the first round, 5-8 come in for the second, 1-4 for the
 * quarter-finals. 1 meets 4's side in a semi-final and 2 meets 3's, so the top
 * two seeds can only meet in the final.
 */
function playBracket(bracket, n, pWin, rnd) {
  const play = (a, b) => (rnd() < pWin(a.rating, b.rating) ? a : b);
  const at = (s) => bracket[s];
  if (n < 16) {
    // Not the 16-team Big 12 shape (a fixture or a short table). Play whoever
    // is there off in seed order rather than inventing byes.
    let field = bracket.slice(1, n + 1);
    while (field.length > 1) {
      const next = [];
      for (let i = 0; i < field.length; i += 2) {
        next.push(i + 1 < field.length ? play(field[i], field[i + 1]) : field[i]);
      }
      field = next;
    }
    return field[0];
  }
  const r1 = [play(at(9), at(16)), play(at(10), at(15)),
              play(at(11), at(14)), play(at(12), at(13))];
  const r2 = [play(at(8), r1[0]), play(at(7), r1[1]),
              play(at(6), r1[2]), play(at(5), r1[3])];
  const qf = [play(at(1), r2[0]), play(at(2), r2[1]),
              play(at(3), r2[2]), play(at(4), r2[3])];
  const sf = [play(qf[0], qf[3]), play(qf[1], qf[2])];
  return play(sf[0], sf[1]);
}

/**
 * "37%", with the ends said in words rather than as a false 0% or 100%.
 *
 * The twin of chance() in shared/.../ui/GamesScreen.kt and win_model.py, down
 * to >= at the top: 0.995 * 100 + 0.5 is exactly 100.0, so a strict > there
 * prints the one certainty this refuses to claim.
 */
export function chance(p) {
  if (p == null) return "";
  if (p < 0.005) return "under 1%";
  if (p >= 0.995) return "over 99%";
  return Math.floor(p * 100 + 0.5) + "%";
}
