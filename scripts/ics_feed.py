"""The season as an iCalendar feed, for phones to subscribe to.

An iPhone cannot install the APK, but it can subscribe to a calendar, and a
subscribed calendar refreshes itself. So the schedule is published as one:
every game of the season, with tip-off, TV and venue, rewritten on every scrape
so a time the conference moves or a broadcast added late reaches the phone
without anybody doing anything.

Times on the athletics schedule are Central, so events are written in
America/Chicago with the zone spelled out in a VTIMEZONE, not converted to UTC
here. A basketball season starts in CDT and spends most of itself in CST — the
clocks go back three weeks after the opener — so a feed written in fixed
offsets would put most of the season an hour out.
"""

import re
from datetime import date, datetime, timedelta

# US Central since 2007: CDT from the second Sunday of March, CST from the first
# Sunday of November, both at 02:00 local.
VTIMEZONE = [
    "BEGIN:VTIMEZONE",
    "TZID:America/Chicago",
    "BEGIN:DAYLIGHT",
    "TZOFFSETFROM:-0600",
    "TZOFFSETTO:-0500",
    "TZNAME:CDT",
    "DTSTART:19700308T020000",
    "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=2SU",
    "END:DAYLIGHT",
    "BEGIN:STANDARD",
    "TZOFFSETFROM:-0500",
    "TZOFFSETTO:-0600",
    "TZNAME:CST",
    "DTSTART:19701101T020000",
    "RRULE:FREQ=YEARLY;BYMONTH=11;BYDAY=1SU",
    "END:STANDARD",
    "END:VTIMEZONE",
]

# Two hours covers a women's college basketball game and its buffer; the
# calendar only needs a block, not a precise finish.
GAME_LENGTH = timedelta(hours=2)


def _escape(text):
    return (text.replace("\\", "\\\\").replace(";", "\\;")
            .replace(",", "\\,").replace("\n", "\\n"))


def _fold(line):
    """RFC 5545 folding: at most 75 octets a line, continuations start with a space."""
    out, cur = [], b""
    for ch in line:
        enc = ch.encode("utf-8")
        if len(cur) + len(enc) > (75 if not out else 74):
            out.append(cur.decode("utf-8"))
            cur = b""
        cur += enc
    out.append(cur.decode("utf-8"))
    return "\r\n ".join(out)


def _uid(g):
    """Stable per game, so an edit updates the event rather than adding one."""
    key = re.sub(r"[^a-z0-9]+", "-", (g.get("opponent") or "").lower()).strip("-")
    return f"{g['date']}-{key}@ku-wbb"


def _summary(g):
    site = {"home": "vs", "away": "at", "neutral": "vs"}.get(g.get("site"), "vs")
    us, them = g.get("teamScore"), g.get("opponentScore")
    if us is not None and them is not None:
        return f"KU {'W' if us > them else 'L'} {us}-{them} {site} {g['opponent']}"
    return f"KU WBB {site} {g['opponent']}"


def _description(g):
    parts = []
    if g.get("event"):
        parts.append(g["event"])
    if g.get("tv"):
        parts.append(f"TV: {g['tv']}")
    if g.get("site") == "neutral":
        parts.append("Neutral site")
    if g.get("periodScores"):
        parts.append(f"Quarters: {g['periodScores']}")
    if not g.get("time") and g.get("teamScore") is None:
        parts.append("Tip time to be announced")
    return "\n".join(parts)


def build_ics(games, stamp):
    """The feed as a string. `stamp` is a UTC datetime, for DTSTAMP only."""
    dtstamp = stamp.strftime("%Y%m%dT%H%M%SZ")
    lines = [
        "BEGIN:VCALENDAR",
        "VERSION:2.0",
        "PRODID:-//ku-wbb//schedule//EN",
        "CALSCALE:GREGORIAN",
        "METHOD:PUBLISH",
        "X-WR-CALNAME:KU Women's Basketball",
        "X-WR-TIMEZONE:America/Chicago",
        # A hint to refresh twice a day; iOS sets its own interval regardless.
        "REFRESH-INTERVAL;VALUE=DURATION:PT12H",
        "X-PUBLISHED-TTL:PT12H",
        *VTIMEZONE,
    ]
    for g in sorted(games, key=lambda g: g["date"]):
        day = date.fromisoformat(g["date"])
        lines += ["BEGIN:VEVENT", f"UID:{_uid(g)}", f"DTSTAMP:{dtstamp}"]
        time = g.get("time") or ""
        if re.fullmatch(r"\d{1,2}:\d{2}", time):
            h, mi = (int(x) for x in time.split(":"))
            start = datetime(day.year, day.month, day.day, h, mi)
            end = start + GAME_LENGTH
            lines += [
                f"DTSTART;TZID=America/Chicago:{start:%Y%m%dT%H%M%S}",
                f"DTEND;TZID=America/Chicago:{end:%Y%m%dT%H%M%S}",
            ]
        else:
            # No tip time yet: an all-day event, so it holds the date without
            # claiming an hour nobody has announced.
            lines += [
                f"DTSTART;VALUE=DATE:{day:%Y%m%d}",
                f"DTEND;VALUE=DATE:{day + timedelta(days=1):%Y%m%d}",
                "TRANSP:TRANSPARENT",
            ]
        lines.append(f"SUMMARY:{_escape(_summary(g))}")
        where = ", ".join(x for x in (g.get("venue"), g.get("city")) if x)
        if where:
            lines.append(f"LOCATION:{_escape(where)}")
        desc = _description(g)
        if desc:
            lines.append(f"DESCRIPTION:{_escape(desc)}")
        lines.append("END:VEVENT")
    lines.append("END:VCALENDAR")
    return "\r\n".join(_fold(line) for line in lines) + "\r\n"


def same_apart_from_stamp(a, b):
    """True when two feeds differ in DTSTAMP at most, so a rewrite would be noise."""
    def strip(s):
        return re.sub(r"^DTSTAMP:.*$", "", s or "", flags=re.M)
    return strip(a) == strip(b)


def main():
    import json
    import os
    from datetime import timezone

    with open("app/src/main/assets/seed.json") as f:
        seed = json.load(f)
    # The current season only: a calendar subscription is about what is coming,
    # and last season's 36 games would bury it.
    seasons = sorted({g["season"] for g in seed.get("games", [])})
    current = seasons[-1] if seasons else None
    games = [g for g in seed.get("games", []) if g["season"] == current]

    out = "docs/ku-wbb.ics"
    feed = build_ics(games, datetime.now(timezone.utc))
    existing = None
    if os.path.exists(out):
        # newline="" or Python translates the feed's CRLF line endings to LF on
        # the way in, and the comparison below then finds every line different
        # from the CRLF feed it is checked against — which defeated the guard
        # entirely and rewrote the file on every single scrape.
        with open(out, newline="") as f:
            existing = f.read()
    # Rewriting only the timestamp would churn a commit on every scrape.
    if same_apart_from_stamp(existing, feed):
        print(f"{out} unchanged (ignoring timestamp); not rewriting")
        return
    with open(out, "w", newline="") as f:
        f.write(feed)
    timed = sum(1 for g in games if g.get("time"))
    print(f"{out} written: {len(games)} events for {current}, {timed} with a tip time")


if __name__ == "__main__":
    main()
