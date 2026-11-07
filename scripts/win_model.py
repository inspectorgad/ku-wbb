"""A chance of winning for games not yet played.

    P(win) = 1 / (1 + 10 ^ (-(z_kansas + venue - z_opponent) / scale))

z is team strength in standard deviations above an average Division I team.
scripts/win-model.json holds the constants and says where each came from.

Separate from update-seed.py so it can be tested on its own, the way
seed_text and ask_pack are.
"""
from statistics import NormalDist

NORM = NormalDist()


def blom_z(rank, teams):
    """NET rank -> strength, in standard deviations, higher being stronger.

    A rank is ordinal: 1st to 10th is a far bigger step than 200th to 210th,
    and the NCAA publishes no rating behind the NET to read instead - its FAQ
    says the components are machine-learned with "no static formula". So the
    rank is read as a percentile of a normal distribution of team strength,
    which is how the NCAA already talks about it: they put a 90th-percentile
    team at "approximately 36 in NET", and this puts rank 36 of 363 at the
    90.2nd percentile.
    """
    if not rank or rank < 1 or teams < 2:
        return None
    return -NORM.inv_cdf((rank - 0.375) / (teams + 0.25))


def fit_line(pairs):
    """Least squares (intercept, slope), or None when it would not mean much."""
    n = len(pairs)
    if n < 3:
        return None
    mx = sum(x for x, _ in pairs) / n
    my = sum(y for _, y in pairs) / n
    sxx = sum((x - mx) ** 2 for x, _ in pairs)
    if sxx == 0:
        return None
    slope = sum((x - mx) * (y - my) for x, y in pairs) / sxx
    return my - slope * mx, slope


class Model:
    """Ratings and a venue adjustment, ready to answer one game at a time.

    net_ranks: {team key: NET rank} covering every Division I team.
    poll_share: {team key: points as a share of the most any team could score},
        empty before the season's first poll.
    net_weight: 1.0 for this season's NET; less for last season's, which is
        all there is until the NCAA publishes the first one in December.
    """

    def __init__(self, config, net_ranks, poll_share=None, net_weight=1.0):
        self.scale = config.get("scale") or 1.0
        self.venues = {
            "home": config.get("homeAdjustment", 0.0),
            "away": config.get("roadAdjustment", 0.0),
            "neutral": config.get("neutralAdjustment", 0.0),
        }
        self.net_weight = net_weight
        self.poll_share = poll_share or {}
        teams = len(net_ranks)
        self.z_of = {t: blom_z(r, teams) for t, r in net_ranks.items()}
        self.z_of = {t: z for t, z in self.z_of.items() if z is not None}

        fit = config.get("pollFit") or {}
        self.intercept = fit.get("intercept", 0.0)
        self.slope = fit.get("slope", 0.0)
        self.fit_note = "fit from win-model.json"
        # A line fitted to an older season is a stand-in. Once a current NET
        # exists there is something better to fit the poll against.
        if net_weight == 1.0 and self.poll_share:
            pairs = [(s, self.z_of[t]) for t, s in self.poll_share.items() if t in self.z_of]
            if len(pairs) >= config.get("minPollOverlap", 10):
                fresh = fit_line(pairs)
                if fresh:
                    self.intercept, self.slope = fresh
                    self.fit_note = f"refitted to {len(pairs)} ranked teams"

    def rating_of(self, key):
        """(z, source) for a normalised team name, or (None, None)."""
        if key in self.poll_share:
            return self.intercept + self.slope * self.poll_share[key], "poll"
        if key in self.z_of:
            return self.net_weight * self.z_of[key], "net"
        return None, None

    def probability(self, kansas_z, opponent_z, site):
        """Kansas's chance, 0-1, at this site against a team rated this well."""
        gap = kansas_z + self.venues.get(site or "", 0.0) - opponent_z
        return 1 / (1 + 10 ** (-gap / self.scale))


def chance(p):
    """"37%", with the ends said in words rather than as a false 0% or 100%.

    No game is certain, and a number that says one is will be remembered on
    the night it is wrong.
    """
    if p is None:
        return ""
    if p < 0.005:
        return "under 1%"
    if p > 0.995:
        return "over 99%"
    return f"{round(p * 100)}%"
