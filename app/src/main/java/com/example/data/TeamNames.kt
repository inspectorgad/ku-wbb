package com.example.data

/**
 * Canonical key for matching one school's name across sources.
 *
 * No two sources spell a school the same way. The NCAA box score says "South
 * Dakota St." where kuathletics' schedule says "South Dakota State"; a poll
 * line arrives as "#12 TCU" and a standings row can carry the first-place
 * votes, "Baylor (3)". Comparing those strings directly is what made a fixture
 * and its own result look like two different games.
 *
 * So nothing compares team names directly. Everything compares this — the
 * seeder when it keys a game, and the screens when they group meetings with
 * one opponent.
 *
 * Note what is deliberately *not* stripped: "(exh.)". An exhibition is not a
 * meeting, and folding "Washburn (exh.)" into "Washburn" would put a result
 * that counts for nothing into a head-to-head record.
 */
fun normTeam(name: String): String = name
    .replace(Regex("""\s*\(\d+\)\s*$"""), "")   // strip poll votes
    .lowercase()
    .replace(".", "")
    .replace(Regex("""\bstate\b"""), "st")
    .replace(Regex("""\buniversity\b"""), "")
    .replace(Regex("""\s+"""), " ")
    .trim()

/** True when two spellings name the same school. */
fun sameTeam(a: String, b: String): Boolean = normTeam(a) == normTeam(b)
