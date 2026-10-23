// The scrape itself cannot be exercised here — this container's egress proxy
// refuses athletics sites — so the decision about whether to go out at all is
// tested on its own. The case that matters is the one that was live in
// production for a fortnight: a site that fails must not be retried on every
// run forever.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fetchDecision, noteFailure, retryAt, FRESH_DAYS } from './roster-cache.mjs';

const DAY = 86_400_000;
const NOW = Date.parse('2026-10-22T12:00:00.000Z');
const ago = (days) => new Date(NOW - days * DAY).toISOString();
const roster = (days) => ({
  url: 'https://example.com/roster', fetchedAt: ago(days),
  players: [{ name: 'Jane Doe', height: '6-0' }],
});

test('a roster read this week is reused, not re-fetched', () => {
  assert.deepEqual(fetchDecision(roster(1), NOW), { fetch: false, reason: 'fresh' });
  assert.deepEqual(fetchDecision(roster(6.9), NOW), { fetch: false, reason: 'fresh' });
});

test('a roster older than a week is read again', () => {
  assert.equal(fetchDecision(roster(7.1), NOW).fetch, true);
});

test('a site never fetched is due', () => {
  assert.deepEqual(fetchDecision(undefined, NOW), { fetch: true, reason: 'due' });
  assert.deepEqual(fetchDecision({}, NOW), { fetch: true, reason: 'due' });
});

test('an entry with a timestamp but no players is not treated as fresh', () => {
  // This is what a failed site looks like before it has ever succeeded.
  const entry = { url: 'u', fetchedAt: ago(1), players: [] };
  assert.equal(fetchDecision(entry, NOW).fetch, true);
});

test('a site that just failed is held off, not retried four hours later', () => {
  // The bug: six runs a day, every day, against a page that needs a code fix.
  const failed = noteFailure(undefined, 'u', NOW - 2 * 3_600_000);
  const d = fetchDecision(failed, NOW);
  assert.equal(d.fetch, false);
  assert.equal(d.reason, 'held');
});

test('the hold doubles with each repeat and stops at a week', () => {
  const at = Date.parse(ago(0));
  const after = (n) => retryAt({ failedAt: new Date(at).toISOString(), failures: n }) - at;
  assert.equal(after(1), 1 * DAY);
  assert.equal(after(2), 2 * DAY);
  assert.equal(after(3), 4 * DAY);
  // Capped, so a dead site settles at the same cadence as a healthy one.
  assert.equal(after(4), FRESH_DAYS * DAY);
  assert.equal(after(9), FRESH_DAYS * DAY);
});

test('once the backoff elapses the site is tried again', () => {
  const failed = { url: 'u', failedAt: ago(1.1), failures: 1 };
  assert.equal(fetchDecision(failed, NOW).fetch, true);
});

test('a failure keeps the roster it already had', () => {
  // A page that breaks must not blank the heights it has already supplied.
  const had = roster(8);
  const after = noteFailure(had, 'u', NOW);
  assert.deepEqual(after.players, had.players);
  assert.equal(after.fetchedAt, had.fetchedAt);
  assert.equal(after.failures, 1);
  // ...and it is still held off despite carrying players, because the stored
  // roster is a week stale and the fresh check has already declined it.
  assert.equal(fetchDecision(after, NOW).reason, 'held');
});

test('repeated failures accumulate rather than resetting', () => {
  let e;
  for (let i = 0; i < 3; i++) e = noteFailure(e, 'u', NOW);
  assert.equal(e.failures, 3);
});

test('a success clears the backoff', () => {
  // The scraper writes a bare entry on success; nothing carries failedAt over.
  const fresh = { url: 'u', fetchedAt: ago(0), players: [{ name: 'Jane Doe' }] };
  assert.equal(retryAt(fresh), 0);
  assert.equal(fetchDecision(fresh, NOW).reason, 'fresh');
});

test('a fresh roster is re-read when the parser has learned more', () => {
  // Otherwise a fix to the parser takes a week to reach the data it fixes:
  // the page has not changed, so the timestamp alone says nothing is wrong.
  const entry = { ...roster(1), parserVersion: 1 };
  assert.deepEqual(fetchDecision(entry, NOW, 2), { fetch: true, reason: 'stale-parser' });
  // An entry stored before the field existed counts as version 1.
  assert.equal(fetchDecision(roster(1), NOW, 2).reason, 'stale-parser');
});

test('a roster already read by the current parser stays fresh', () => {
  const entry = { ...roster(1), parserVersion: 2 };
  assert.equal(fetchDecision(entry, NOW, 2).reason, 'fresh');
  // A caller that does not care about the parser is unaffected.
  assert.equal(fetchDecision(roster(1), NOW).reason, 'fresh');
});

test('the parser version does not override the failure backoff', () => {
  // A site that cannot be parsed at all must not be retried every run just
  // because the parser moved — that is the loop this all exists to stop.
  const failed = noteFailure({ ...roster(8), parserVersion: 1 }, 'u', NOW);
  assert.equal(fetchDecision(failed, NOW, 2).reason, 'held');
});

test('a fresh roster the new parser cannot read is held, not retried hourly', () => {
  // The case the test above missed by using a roster already a week old: while
  // the stored copy is still FRESH, the parser-version bust is the reason we
  // want the page, and it sat in front of the backoff. Six runs a day, each
  // failing, for as long as the page stayed unreadable.
  let e = { ...roster(1), parserVersion: 1 };
  const fetches = [];
  for (let run = 1; run <= 6; run++) {
    const at = NOW + run * 4 * 3_600_000;
    const d = fetchDecision(e, at, 2);
    fetches.push(d.reason);
    if (d.fetch) e = noteFailure(e, 'u', at);
  }
  // One attempt, then held for the rest of the day.
  assert.deepEqual(fetches, ['stale-parser', 'held', 'held', 'held', 'held', 'held']);
  assert.equal(e.failures, 1);
});

test('a corrupt timestamp does not wedge a site shut', () => {
  // Better to fetch once too often than to never look at a site again.
  assert.equal(retryAt({ failedAt: 'not a date', failures: 2 }), 0);
  assert.equal(fetchDecision({ failedAt: 'not a date', failures: 2 }, NOW).fetch, true);
});
