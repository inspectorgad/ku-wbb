"""Text normalisation shared by the seed build.

These three functions decide what counts as the same player and the same
school across sources that spell neither consistently, which makes them the
quietest place in the pipeline for a bug to live: a normaliser that is subtly
wrong does not raise, it just stops matching, and the result reads as missing
data rather than as a fault.

They live here rather than in update-seed.py so they can be tested. That script
runs its whole build at import time and its name carries a hyphen, so there is
no way to reach a function inside it from a test.
"""
import re
import unicodedata

# UTF-8 bytes read back through a single-byte codec, in the order worth trying.
#
# Latin-1 is the usual culprit and is tried first. It cannot be the only one:
# the NCAA feed delivered "Isa HÃ€mÃ€lÃ€inen" (Hämäläinen), where byte 0xA4
# came back as €, and € has no Latin-1 byte at all — so the repair raised
# UnicodeEncodeError and the name was returned corrupted.
#
# ISO-8859-15 must precede cp1252. Both can encode €, but to different bytes:
# 0xA4 in ISO-8859-15, which is the one that rebuilds ä, against 0x80 in
# cp1252, which rebuilds À instead. cp1252 would therefore produce
# "HÀmÀlÀinen" — plausible enough to pass the check below, and wrong. It is
# still worth keeping last, for the smart quotes and dashes that only it holds.
_MOJIBAKE_CODECS = ("latin-1", "iso-8859-15", "cp1252")


def fix_mojibake(text):
    """Repair UTF-8 read as a single-byte codec ("AnaÃ«lle" -> "Anaëlle").

    The NCAA feed is inconsistent between games, so the same player can arrive
    corrupted in one box score and clean in another; without this they become
    two different players in every cross-game aggregation.

    Anything that cannot be repaired is passed through untouched. A name shown
    with the wrong letters is a blemish; a name mangled further by a bad guess
    would stop matching the same player's other rows, which is worse.
    """
    if not text or not any(c in text for c in "ÃÂÅ"):
        return text
    for codec in _MOJIBAKE_CODECS:
        try:
            repaired = text.encode(codec).decode("utf-8")
        except (UnicodeEncodeError, UnicodeDecodeError):
            continue
        # Only accept a repair that removes the tell-tale sequences.
        if "Ã" not in repaired:
            return repaired
    return text


def norm_name(name):
    """Canonical key for matching one player across two sources.

    A roster page and a box score rarely agree on punctuation or case —
    "Anaëlle Dutat" against "ANAELLE DUTAT", "D'Shara Booker" against
    "DShara Booker" — so accents are folded, everything but letters dropped,
    and the result lower-cased. Deliberately not fuzzy beyond that: a near
    match would attach one player's height to another.
    """
    folded = unicodedata.normalize("NFKD", name or "")
    folded = "".join(c for c in folded if not unicodedata.combining(c))
    return re.sub(r"[^a-z]", "", folded.lower())


def norm_team(name):
    """Canonical key for cross-source name matching ('Iowa State'/'Iowa St.').

    kuathletics writes a schedule opponent as "South Dakota State" while the
    NCAA box score for the same game says "South Dakota St." — without this the
    fixture and its result key differently and the season shows two games.
    """
    n = re.sub(r"\s*\(\d+\)\s*$", "", name or "").lower()  # strip poll votes
    n = n.replace(".", "")
    n = re.sub(r"\bstate\b", "st", n)
    n = re.sub(r"\buniversity\b", "", n)
    return re.sub(r"\s+", " ", n).strip()
