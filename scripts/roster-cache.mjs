// When the opponent roster scrape should ask a school's site again.
//
// Rosters change about twice a year, and the scrape runs every four hours, so
// almost every run should fetch nothing at all. Two separate holds keep it
// that way, and the second one is the reason this file exists.
//
// A roster that was read successfully is left alone for a week.
//
// A site that FAILED used to be held off by nothing. The cache entry was
// written only on success, so a page that had changed shape was re-fetched on
// every one of the six daily runs for as long as it stayed broken — Arizona
// spent a fortnight doing that, rewriting its saved-page file into a commit
// each time, which is how it was noticed. A failure now pushes the next
// attempt further out each time it repeats: a day, then two, then four, to a
// ceiling of the same week a success buys. A transient blip still clears on
// the next day's run, while a site that is simply gone costs one page load a
// week rather than forty-two.
const DAY = 86_400_000;

/** How long a roster that parsed is trusted before being read again. */
export const FRESH_DAYS = 7;

/**
 * The moment a site that failed may be tried again, or 0 if it has not failed.
 *
 * The backoff doubles per consecutive failure and is capped at [FRESH_DAYS],
 * so a permanently broken site settles into the same cadence as a healthy one
 * rather than growing unboundedly far apart.
 */
export function retryAt(entry) {
  if (!entry || !entry.failedAt) return 0;
  const failures = Math.max(1, entry.failures || 1);
  const days = Math.min(2 ** (failures - 1), FRESH_DAYS);
  const at = Date.parse(entry.failedAt);
  return Number.isNaN(at) ? 0 : at + days * DAY;
}

/**
 * Whether to fetch this site on this run.
 *
 * `reason` is for the run log, which is the only place a held-off site is
 * visible — a silently skipped one looks identical to a site nobody mapped.
 *   fresh  the stored roster parsed recently enough to reuse
 *   held   the last attempt failed and the backoff has not elapsed
 *   due    go and fetch it
 */
export function fetchDecision(entry, now, parserVersion) {
  // The backoff is checked FIRST, before any reason to want the page. A
  // failure means the last attempt did not work, and that is true whatever
  // made us ask — so every other branch below is downstream of it.
  //
  // This was the other way round at first, and the parser-version bust below
  // then sat in front of the backoff: a site whose stored roster was still
  // fresh but unreadable by the new parser was fetched on every run, failing
  // each time, which is precisely the loop this file exists to stop. The
  // ordering is the whole fix, so it is tested directly.
  const until = retryAt(entry);
  if (until > now) return { fetch: false, reason: 'held', until };

  const fetchedAt = Date.parse(entry && entry.fetchedAt);
  if (entry && entry.players && entry.players.length
      && fetchedAt > now - FRESH_DAYS * DAY) {
    // A fresh roster is reused, unless the parser has since learned to read
    // more off the page. The page has not changed, so nothing in the timestamp
    // says the stored players are now missing fields that could be had, and
    // without this a fix to the parser waits out the week before reaching the
    // data it fixes.
    if (parserVersion !== undefined && (entry.parserVersion || 1) < parserVersion) {
      return { fetch: true, reason: 'stale-parser' };
    }
    return { fetch: false, reason: 'fresh' };
  }
  return { fetch: true, reason: 'due' };
}

/**
 * The entry to store after an attempt failed.
 *
 * Any roster already held is carried forward untouched: a page that breaks
 * must not blank the heights it has already supplied, which is why the failure
 * is recorded alongside the players rather than in place of them.
 */
export function noteFailure(entry, url, at) {
  return {
    ...(entry || {}),
    url,
    failedAt: new Date(at).toISOString(),
    failures: ((entry && entry.failures) || 0) + 1,
  };
}
