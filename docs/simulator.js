// The dashboard's season simulator section. The simulation is sim.js; this
// draws the numbers and the what-if toggles.
//
// It reads window.SIM_INPUTS rather than the page's DATA, because DATA is a
// local of the page's classic inline script and nothing a module can see. The
// inline script publishes the inputs and fires "seasonchange" when the picker
// moves, since the simulation belongs to one season only.
import { simulate, chance, RUNS } from "./sim.js";

const EXPLAIN =
  `The rest of the season played out ${RUNS.toLocaleString()} times. Each remaining game is won or ` +
  "lost at random with the win model's chance for it (the Win chance on the schedule), and the " +
  "numbers here count what happened. Set a game to Win or Loss to see what that would change. " +
  "Big 12: the other teams' remaining opponents are not published game by game, so their games " +
  "other than against Kansas are played against an average Big 12 side, and ties in the table " +
  "are settled by a coin toss rather than the Big 12's tiebreakers. The conference tournament " +
  "takes all 16 teams on a neutral floor in Kansas City, with a double bye for the top four " +
  "seeds and a bye for the next four, and its winner takes the Big 12's automatic place in the " +
  "NCAA tournament. There is no at-large estimate here: that turns on the NET, which the NCAA " +
  "builds by machine learning and publishes no formula for, so it cannot honestly be projected. " +
  "Estimates, not a forecast of what the selection committee will do.";

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const shortDate = (iso) => `${MONTHS[Number(iso.slice(5, 7)) - 1]} ${Number(iso.slice(8, 10))}`;
const siteWord = (s) => (s === "home" ? "vs" : s === "away" ? "at" : "vs");

const el = (tag, cls, text) => {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text !== undefined) n.textContent = text;
  return n;
};

/** The three lines the section shows, kept here so a test can read them. */
export function lines(r) {
  const rec = (w) => `${w}-${r.games - w}`;
  return [
    `Projected record ${rec(r.winsMid)} (likely ${rec(r.winsLow)} to ${rec(r.winsHigh)})`,
    `Big 12: finish around ${ordinal(r.seedMid)} (${ordinal(r.seedLow)} to ${ordinal(r.seedHigh)})` +
      ` · double bye ${chance(r.top4)} · a bye ${chance(r.top8)} · title ${chance(r.title)}`,
    `Big 12 tournament ${chance(r.autoBid)} — the automatic NCAA place`,
  ];
}

export function ordinal(n) {
  const t = n % 100;
  if (t >= 11 && t <= 13) return `${n}th`;
  return `${n}${["th", "st", "nd", "rd"][n % 10] || "th"}`;
}

function init() {
  const wrap = document.getElementById("sim-wrap");
  const section = document.getElementById("simSec");
  if (!wrap || !section) return;
  const inputs = window.SIM_INPUTS;
  if (!inputs || !inputs.remaining || !inputs.remaining.length) return;

  const forced = {};
  const head = el("h3", "sim-head");
  const out = el("div", "sim-lines");
  const reset = el("button", "chip", "Reset to the model");
  reset.hidden = true;
  const list = el("div", "sim-list");

  const render = () => {
    const n = Object.keys(forced).length;
    head.textContent = n
      ? `With ${n} result${n === 1 ? "" : "s"} set by you`
      : "If the rest of the season goes as the model expects";
    out.replaceChildren(...lines(simulate(inputs, forced)).map((t) => el("p", null, t)));
    reset.hidden = !n;
    for (const row of list.children) {
      const i = Number(row.dataset.i);
      for (const b of row.querySelectorAll("button[data-v]")) {
        b.classList.toggle("on", forced[i] === b.dataset.v);
      }
    }
  };

  inputs.remaining.forEach((g, i) => {
    const row = el("div", "sim-row");
    row.dataset.i = String(i);
    row.append(el("span", "sim-when", shortDate(g.date)));
    row.append(el("span", "sim-who", `${siteWord(g.site)} ${g.opponent}`));
    row.append(el("span", "sim-p dim", chance(g.p)));
    for (const v of ["W", "L"]) {
      const b = el("button", "chip", v === "W" ? "Win" : "Loss");
      b.dataset.v = v;
      b.addEventListener("click", () => {
        if (forced[i] === v) delete forced[i];
        else forced[i] = v;
        render();
      });
      row.append(b);
    }
    list.append(row);
  });

  reset.addEventListener("click", () => {
    for (const k of Object.keys(forced)) delete forced[k];
    render();
  });

  const note = el("p", "fineprint", EXPLAIN);
  if (inputs.unrated && inputs.unrated.length) {
    note.textContent += ` Left out, having no rating to play with: ${inputs.unrated.join(", ")}.`;
  }
  wrap.replaceChildren(head, out, reset, list, note);
  render();

  // The simulation is for one season; hide it while another is on screen.
  const show = (s) => { section.hidden = s !== inputs.season; };
  show(window.SIM_SEASON);
  window.addEventListener("seasonchange", (ev) => show(ev.detail));
}

if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", init);
} else {
  init();
}
