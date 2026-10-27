"""Checks the four Apple secrets the iPhone build will use, without printing them.

The App Store Connect key (ASC_ISSUER_ID, ASC_KEY_ID, ASC_KEY_P8) is used to
look the app up by its bundle ID, which needs a working key, an app record
created with that ID, and the identifier registered. APPLE_TEAM_ID is checked
against the App ID's prefix where that is conclusive (see team_id_matches).

Only pass/fail lines and non-secret facts (the app's name, the App ID's
capabilities) are printed, so the log is safe to read and safe to share.

Run by the "Apple keys check" workflow; needs pyjwt[crypto].
"""
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

import jwt

BUNDLE_ID = "io.github.inspectorgad.kuwbb"
ASC = "https://api.appstoreconnect.apple.com/v1"

results = []


def report(ok, what, detail=""):
    results.append(ok)
    print(f"{'PASS' if ok else 'FAIL'}  {what}" + (f" - {detail}" if detail else ""))


def note(what, detail=""):
    """Something worth seeing that is not pass or fail."""
    print(f"      {what}" + (f" - {detail}" if detail else ""))


def secret(name):
    value = os.environ.get(name, "").strip()
    report(bool(value), f"{name} is set", "" if value else "missing or empty")
    return value


def shape(name, value, pattern, hint):
    ok = bool(re.fullmatch(pattern, value))
    report(ok, f"{name} looks right", "" if ok else hint)
    return ok


def asc_get(token, path, params):
    url = f"{ASC}/{path}?{urllib.parse.urlencode(params)}"
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, json.load(resp)
    except urllib.error.HTTPError as e:
        try:
            body = json.load(e)
        except Exception:
            body = {}
        return e.code, body


def asc_error(body):
    errors = body.get("errors") or [{}]
    return f"{errors[0].get('status', '')} {errors[0].get('code', '')}: {errors[0].get('detail', '')}".strip()


def check_app_store_connect(issuer, key_id, p8, team):
    now = int(time.time())
    try:
        token = jwt.encode(
            {"iss": issuer, "iat": now, "exp": now + 600, "aud": "appstoreconnect-v1"},
            p8, algorithm="ES256", headers={"kid": key_id, "typ": "JWT"},
        )
    except Exception as e:
        report(False, "ASC_KEY_P8 is a usable private key", type(e).__name__)
        return
    report(True, "ASC_KEY_P8 is a usable private key")

    status, body = asc_get(token, "apps", {"filter[bundleId]": BUNDLE_ID})
    if status == 401:
        report(False, "App Store Connect accepts the key",
               "401 - the Issuer ID, Key ID and .p8 do not belong together, or the key was revoked")
        return
    if status != 200:
        report(False, "App Store Connect accepts the key", f"HTTP {status} {asc_error(body)}")
        return
    report(True, "App Store Connect accepts the key")

    apps = body.get("data", [])
    if apps:
        report(True, "the app exists in App Store Connect",
               f'"{apps[0]["attributes"].get("name", "?")}"')
    else:
        report(False, "the app exists in App Store Connect",
               f"no app with bundle ID {BUNDLE_ID} - that is Thing 2, creating the app record")

    status, body = asc_get(token, "bundleIds",
                           {"filter[identifier]": BUNDLE_ID, "include": "bundleIdCapabilities"})
    if status != 200 or not body.get("data"):
        report(False, "the App ID is registered",
               f"HTTP {status} {asc_error(body)}" if status != 200
               else f"{BUNDLE_ID} is not in Certificates, Identifiers & Profiles - that is Thing 1")
        return

    identifier = body["data"][0]
    caps = sorted({c["attributes"].get("capabilityType", "?") for c in body.get("included", [])
                   if c.get("type") == "bundleIdCapabilities"})
    report(True, "the App ID is registered")
    # Informational: this app needs no capabilities. IN_APP_PURCHASE is on by
    # default for every explicit App ID and cannot be turned off, so seeing it
    # here means nothing is wrong.
    note("capabilities", ", ".join(caps) or "none, which is what this app expects")

    team_id_matches(team, identifier)


def team_id_matches(team, identifier):
    """Compare APPLE_TEAM_ID with the App ID's prefix, when that is conclusive.

    An App ID carries a prefix (seedId), which for an identifier registered by
    a modern account is the Team ID. It is not guaranteed to be: Apple let
    older accounts choose a prefix, so a legacy App ID can carry one that is
    not the team's. A mismatch is therefore reported as something to look at
    rather than a failure - a check that fails on a legitimate difference
    sends someone hunting for a problem that is not there.
    """
    seed = (identifier.get("attributes") or {}).get("seedId")
    if not seed:
        note("APPLE_TEAM_ID could not be cross-checked",
             "the App ID carries no prefix to compare it against")
    elif seed == team:
        report(True, "APPLE_TEAM_ID matches the App ID's prefix")
    else:
        note("LOOK: APPLE_TEAM_ID is not the App ID's prefix",
             "usually they are the same, so check APPLE_TEAM_ID is the Team ID from "
             "Membership details and not the Key ID. An App ID registered years ago "
             "can legitimately differ, which is why this is not a failure")


team = secret("APPLE_TEAM_ID")
issuer = secret("ASC_ISSUER_ID")
key_id = secret("ASC_KEY_ID")
p8 = secret("ASC_KEY_P8")

shape("APPLE_TEAM_ID", team, r"[A-Z0-9]{10}", "should be 10 capital letters and digits")
shape("ASC_ISSUER_ID", issuer, r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
      "should look like 69a6de7e-....-....-....-............ (the long one above the keys list)")
shape("ASC_KEY_ID", key_id, r"[A-Z0-9]{10}", "should be 10 capital letters and digits")
report("BEGIN PRIVATE KEY" in p8 and "END PRIVATE KEY" in p8, "ASC_KEY_P8 is a whole .p8 file",
       "" if "BEGIN PRIVATE KEY" in p8 else "paste the whole file, BEGIN and END lines included")
# The Team ID and the Key ID are both ten characters and are routinely swapped.
report(team != key_id or not team, "APPLE_TEAM_ID and ASC_KEY_ID are different values",
       "" if team != key_id else "the same value is in both - one of them is wrong")

if issuer and key_id and p8:
    check_app_store_connect(issuer, key_id, p8, team)

passed = sum(results)
print(f"\n{passed} of {len(results)} checks passed")
sys.exit(0 if all(results) else 1)
