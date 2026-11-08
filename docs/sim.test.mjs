// node --test docs/sim.test.mjs
//
// The fixture is a whole 16-team Big 12, because the thing most likely to be
// wrong is the bracket: byes, who meets whom, and how many wins each seed
// needs. A four-team fixture would exercise none of it.
//
// When SeasonSimulator.kt mirrors sim.js, the pinned numbers below are the
// ones its test has to reproduce, run for run.
import test from 'node:test';
import assert from 'node:assert/strict';
import { simulate, chance, RUNS } from './sim.js';

const other = (team, confW, confL, remaining, rating) => ({
  team, key: team.toLowerCase(), confW, confL, remaining,
  pVsAverage: Number((1 / (1 + Math.pow(10, -(rating - 0.5) / 1.04))).toFixed(4)),
  rating,
});

// A short conference season (8 games, not the real 18) so the fixture stays
// readable, but the real 16-team bracket. Everyone must have played and have
// left the SAME number of games, the way sim_inputs.build computes them:
// others' `remaining` is confTotal - played - (1 if Kansas still visits).
// Getting that wrong is not cosmetic - give Kansas 5 games left while the
// rest have 15 and Kansas finishes last in every run, which quietly turns
// every placement test into a comparison of two zeroes.
export const FIXTURE = {
  season: '2026-27',
  confTotal: 8,
  confTeams: 16,
  scale: 1.04,
  kansas: { confW: 2, confL: 1, overallW: 9, overallL: 2, rating: 0.82 },
  remaining: [
    { date: '2027-01-03', opponent: 'Baylor', site: 'home', conference: true, key: 'baylor', p: 0.55 },
    { date: '2027-01-07', opponent: 'TCU', site: 'away', conference: true, key: 'tcu', p: 0.30 },
    { date: '2027-01-11', opponent: 'Utah', site: 'home', conference: true, key: 'utah', p: 0.70 },
    { date: '2027-01-15', opponent: 'Houston', site: 'away', conference: true, key: 'houston', p: 0.45 },
    { date: '2027-01-20', opponent: 'Cincinnati', site: 'home', conference: true, key: 'cincinnati', p: 0.80 },
  ],
  // 3 played each; 4 left for the five Kansas still visits, 5 for the rest.
  others: [
    other('TCU', 3, 0, 4, 1.60), other('Baylor', 2, 1, 4, 1.20),
    other('Oklahoma State', 2, 1, 5, 1.05), other('West Virginia', 2, 1, 5, 0.95),
    other('Iowa State', 2, 1, 5, 0.80), other('Utah', 1, 2, 4, 0.60),
    other('Kansas State', 1, 2, 5, 0.55), other('Colorado', 1, 2, 5, 0.40),
    other('Arizona', 1, 2, 5, 0.30), other('BYU', 1, 2, 5, 0.20),
    other('Houston', 1, 2, 4, 0.10), other('Arizona State', 0, 3, 5, -0.10),
    other('Cincinnati', 0, 3, 4, -0.30), other('UCF', 0, 3, 5, -0.50),
    other('Texas Tech', 0, 3, 5, -0.70),
  ],
  unrated: ['Big 12 Tournament'],
};

test('the fixture is a whole conference', () => {
  assert.equal(FIXTURE.others.length + 1, 16);
});

test('the same inputs give the same numbers every time', () => {
  assert.deepEqual(simulate(FIXTURE, {}, 4000), simulate(FIXTURE, {}, 4000));
});

test('every chance is a probability and the record adds up', () => {
  const r = simulate(FIXTURE, {}, 4000);
  for (const k of ['title', 'shareOfTitle', 'top4', 'top8', 'autoBid']) {
    assert.ok(r[k] >= 0 && r[k] <= 1, `${k} out of range: ${r[k]}`);
  }
  assert.ok(r.title <= r.top4 && r.top4 <= r.top8, 'nested outcomes out of order');
  assert.ok(r.title <= r.shareOfTitle, 'an outright title is a share as well');
  assert.equal(Math.round(r.expectedWins + r.expectedLosses), r.games);
  assert.equal(r.games, 16);                       // 11 played + 5 left
  assert.ok(r.seedLow <= r.seedMid && r.seedMid <= r.seedHigh);
  assert.ok(r.averagePlace >= 1 && r.averagePlace <= 16);
});

test('a forced win helps and a forced loss hurts', () => {
  const base = simulate(FIXTURE, {}, 4000);
  const win = simulate(FIXTURE, { 1: 'W' }, 4000);
  const loss = simulate(FIXTURE, { 1: 'L' }, 4000);
  assert.ok(win.expectedWins > base.expectedWins && base.expectedWins > loss.expectedWins);
  assert.ok(win.title >= base.title && base.title >= loss.title);
  assert.ok(win.averagePlace < loss.averagePlace, 'winning should finish higher');
});

test('winning out pins the record exactly', () => {
  const all = simulate(FIXTURE, { 0: 'W', 1: 'W', 2: 'W', 3: 'W', 4: 'W' }, 1500);
  assert.equal(all.winsLow, 14);                   // 9 played + 5 forced
  assert.equal(all.winsHigh, 14);
  assert.equal(all.expectedWins, 14);
  assert.equal(all.expectedLosses, 2);
  const none = simulate(FIXTURE, { 0: 'L', 1: 'L', 2: 'L', 3: 'L', 4: 'L' }, 1500);
  assert.equal(none.expectedWins, 9);
  assert.equal(none.expectedLosses, 7);
});

test('the bracket answers to strength, not just to seeding', () => {
  // Kansas far and away the best team in the conference should win the
  // tournament more often than not; the worst should almost never.
  const best = { ...FIXTURE, kansas: { ...FIXTURE.kansas, rating: 4.0 } };
  const worst = { ...FIXTURE, kansas: { ...FIXTURE.kansas, rating: -4.0 } };
  assert.ok(simulate(best, {}, 3000).autoBid > 0.5, 'the best team should usually win it');
  assert.ok(simulate(worst, {}, 3000).autoBid < 0.05, 'the worst team should rarely win it');
});

test('a double bye is worth having', () => {
  // Seeds 1-4 need four wins; 9-16 need five. Two teams of equal strength,
  // one seeded into a double bye, must not come out level.
  const strong = simulate({ ...FIXTURE, kansas: { ...FIXTURE.kansas, confW: 3, confL: 0 } }, {}, 3000);
  const weak = simulate({ ...FIXTURE, kansas: { ...FIXTURE.kansas, confW: 0, confL: 3 } }, {}, 3000);
  assert.ok(strong.top4 > weak.top4);
  assert.ok(strong.autoBid > weak.autoBid, 'a better seed should win it more often');
});

test('the tournament is not confused with the table', () => {
  // The automatic place comes from the bracket, so it must not simply equal
  // the regular-season title - that is the volleyball rule, not this one.
  const r = simulate(FIXTURE, {}, 4000);
  assert.notEqual(r.autoBid, r.title);
  assert.ok(r.autoBid > 0, 'Kansas can win the bracket from any seed');
});

test('an unratable game is left out rather than guessed', () => {
  // sim_inputs drops games the model cannot rate, so "Big 12 Tournament" is
  // named in `unrated` and is not one of the five games played out.
  assert.equal(FIXTURE.remaining.length, 5);
  assert.deepEqual(FIXTURE.unrated, ['Big 12 Tournament']);
});

test('chances read in words at the ends', () => {
  assert.equal(chance(0.001), 'under 1%');
  assert.equal(chance(0.999), 'over 99%');
  assert.equal(chance(0.374), '37%');
  // The twin's boundary: a strict > here would print "100%".
  assert.equal(chance(0.995), 'over 99%');
  assert.equal(chance(0.9949), '99%');
  assert.equal(chance(null), '');
});

test('the shipped run count is what the screens use', () => {
  assert.equal(RUNS, 20000);
});
