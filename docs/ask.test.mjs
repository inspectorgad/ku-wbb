// The Preview and Recap buttons exist twice — once in the app, once on this
// dashboard — and the whole point of the two question builders is that they
// ask the same thing. Nothing at build time joins them, so a reword on one
// side is invisible until someone reads both files side by side. This does
// that reading: it pulls the dashboard's builders out of docs/ask.js and runs
// them, rebuilds the app's from the Kotlin literals, and compares.
//
// Run with:  node --test docs/*.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const ASK_JS = new URL('./ask.js', import.meta.url);
const ASK_KT = new URL(
  '../shared/src/commonMain/kotlin/com/example/ui/AskScreen.kt', import.meta.url
);
const GAMES_KT = new URL(
  '../shared/src/commonMain/kotlin/com/example/ui/GamesScreen.kt', import.meta.url
);
const TEMPLATE = new URL('./dashboard.template.html', import.meta.url);

const askJs = fs.readFileSync(ASK_JS, 'utf8');
const askKt = fs.readFileSync(ASK_KT, 'utf8');
const gamesKt = fs.readFileSync(GAMES_KT, 'utf8');
const template = fs.readFileSync(TEMPLATE, 'utf8');

/** The dashboard's builder, lifted out of ask.js and made callable here. */
function jsBuilder(name) {
  const re = new RegExp(`window\\.${name} = (\\(date, versus, opponent\\) =>[\\s\\S]*?);\\n`);
  const m = re.exec(askJs);
  assert.ok(m, `window.${name} is not in docs/ask.js`);
  return new Function(`return ${m[1]}`)();
}

/**
 * The app's builder, rebuilt from the Kotlin: the concatenated string
 * literals of a `fun name(...) = "..." + "..."` with its $placeholders filled.
 */
function ktBuilder(name) {
  const head = `fun ${name}(date: String, versus: String, opponent: String) =`;
  const at = askKt.indexOf(head);
  assert.ok(at >= 0, `${name} is not in AskScreen.kt`);
  const pieces = [];
  for (const line of askKt.slice(at + head.length).split('\n').slice(1)) {
    if (!line.startsWith(' ')) break;            // the next top-level declaration
    pieces.push(line);
    if (!line.trimEnd().endsWith('+')) break;    // the end of the concatenation
  }
  const text = pieces.join('\n').match(/"([^"]*)"/g).map(s => s.slice(1, -1)).join('');
  return (date, versus, opponent) =>
    text.replace(/\$(date|versus|opponent)/g, (_, k) => ({ date, versus, opponent }[k]));
}

const CASES = [
  ['a home game', '2026-11-03', 'vs ', 'Omaha'],
  ['a road game', '2027-01-02', 'at ', 'Cincinnati'],
  // siteLabel()/vsAt() give nothing at all when the site is unknown, rather
  // than a wrong "vs". The sentence has to survive that.
  ['a game with no site', '2026-12-20', '', 'Wichita State'],
];

for (const name of ['kuPreviewQuestion', 'kuRecapQuestion']) {
  const kotlinName = name.replace(/^ku(.)/, (_, c) => c.toLowerCase());
  test(`${name} asks word for word what ${kotlinName}() asks in the app`, () => {
    const fromJs = jsBuilder(name);
    const fromKt = ktBuilder(kotlinName);
    for (const [what, date, versus, opponent] of CASES) {
      assert.equal(fromJs(date, versus, opponent), fromKt(date, versus, opponent), what);
    }
  });
}

test('each question names the game, in one sentence, with no gap where a site would be', () => {
  for (const name of ['kuPreviewQuestion', 'kuRecapQuestion']) {
    const q = jsBuilder(name);
    assert.match(q('2026-11-03', 'vs ', 'Omaha'), /\b2026-11-03 game vs Omaha:/);
    assert.match(q('2027-01-02', 'at ', 'Cincinnati'), /\b2027-01-02 game at Cincinnati:/);
    // No trailing space left behind by an unknown site.
    assert.doesNotMatch(q('2026-12-20', '', 'Wichita State'), / {2}/);
    assert.match(q('2026-12-20', '', 'Wichita State'), /\b2026-12-20 game Wichita State:/);
    // One sentence, as the app's are.
    assert.equal(q('2026-11-03', 'vs ', 'Omaha').match(/\./g).length, 1);
  }
});

test('the questions promise only what this app can answer', () => {
  const preview = jsBuilder('kuPreviewQuestion')('2026-11-14', 'vs ', 'Nebraska');
  const recap = jsBuilder('kuRecapQuestion')('2026-11-03', 'vs ', 'Omaha');
  // Volleyball's wording, which this was ported from, asks about sets, the
  // win model's estimate and the performance goals met or missed. This app
  // plays quarters, keeps no goals at all, and does not put the win model in
  // the data Claude can fetch - so asking for any of them invites an answer
  // made up out of nothing.
  for (const q of [preview, recap]) {
    assert.doesNotMatch(q, /\bsets?\b/);
    assert.doesNotMatch(q, /\bgoals?\b/);
    assert.doesNotMatch(q, /win model/);
  }
  assert.match(recap, /\bquarter\b/);
  // Both lean on tables the pack really carries.
  assert.match(recap, /team totals/);
  assert.match(preview, /NET/);
});

test('the app and the dashboard label the same button the same way', () => {
  // The app spells both labels out; the dashboard has room for "Preview" on a
  // schedule row but spells the recap out in the game sheet's banner.
  assert.match(gamesKt, /"Recap this game with Claude"/);
  assert.match(gamesKt, /"Preview this game with Claude"/);
  assert.match(template, /id="gRecap"[^>]*>Recap this game with Claude</);
  assert.match(template, /data-ask-preview="\$\{i\}">Preview</);
});

test('a handed-over question never quietly spends or destroys anything', () => {
  // Three guards, each one a thing a button press must not do on its own:
  // start a second question while one is in hand, ask on a key the reader has
  // not saved, or clear the thread by taking web search on partway through.
  const body = /window\.kuAskAbout = \(q\) => \{([\s\S]*?)\n  \};/.exec(askJs);
  assert.ok(body, 'window.kuAskAbout is not in docs/ask.js');
  assert.match(body[1], /if \(running \|\| asking\) return;/);
  assert.match(body[1], /if \(!apiKey\(\)\)/);
  assert.match(body[1], /webDeclared === false/);
  // The same three in the app, where the engine is asked rather than the DOM,
  // and a fourth the dashboard has no need of: the app's question survives in
  // saved state across a rotation or a killed process, so it is taken once and
  // dropped rather than asked again by a composition that comes back.
  assert.match(askKt, /key == null ->/);
  assert.match(askKt, /AskSession\.running != null ->/);
  assert.match(askKt, /AskSession\.wouldRestart\(webSearch\) ->/);
  assert.match(askKt, /onPrefillTaken\(\)/);
});
