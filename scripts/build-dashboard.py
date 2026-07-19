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
    # Only completed games; upcoming fixtures have no box score to chart.
    "games": [g for g in seed["games"] if "teamScore" in g],
}

with open(TEMPLATE_PATH) as f:
    template = f.read()

html = template.replace("/*__DATA__*/", json.dumps(data, separators=(",", ":")))

with open(OUT_PATH, "w") as f:
    f.write(html)

print(f"dashboard.html rebuilt: {len(data['games'])} games, {len(data['players'])} players")
