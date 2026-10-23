#!/usr/bin/env python3
"""Regenerates docs/dashboard.html by injecting the current seed data into
docs/dashboard.template.html. Run after update-seed.py so the published
GitHub Pages dashboard tracks the season automatically."""
import json

SEED_PATH = "app/src/main/assets/seed.json"
TEMPLATE_PATH = "docs/dashboard.template.html"
OUT_PATH = "docs/dashboard.html"

with open(SEED_PATH) as f:
    seed = json.load(f)

data = {
    "players": seed["players"],
    # Every game, played or scheduled — the dashboard's season picker needs
    # upcoming fixtures so a season can be selected before it tips off.
    "games": seed["games"],
}
# Optional keys; older seeds simply omit them. standings and polls arrived
# with the Big 12 section, opponentRosters with the opponent cards — the page
# reads each defensively, so a seed without one renders without that part.
for key in ("standings", "polls", "opponentRosters"):
    if key in seed:
        data[key] = seed[key]

with open(TEMPLATE_PATH) as f:
    template = f.read()

html = template.replace("/*__DATA__*/", json.dumps(data, separators=(",", ":")))

with open(OUT_PATH, "w") as f:
    f.write(html)

print(f"dashboard.html rebuilt: {len(data['games'])} games, {len(data['players'])} players")
