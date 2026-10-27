"""Says where the latest TestFlight builds have got to, without printing secrets.

An upload is not an installable build: Apple processes it afterwards, and a
build can sit in PROCESSING for half an hour or fail outright, in which case
refreshing the TestFlight app forever tells you nothing. This asks App Store
Connect instead.

The states worth knowing:
  PROCESSING              Apple is still working; wait.
  VALID                   processed. Internal testers can install it.
  INVALID / FAILED        Apple rejected it; the reason is printed.
  READY_FOR_BETA_TESTING  it is on testers' phones now.
  MISSING_EXPORT_COMPLIANCE  the encryption question is unanswered, which
                          the Info.plist is meant to prevent.

Run by the "iOS build status" workflow; needs pyjwt[crypto].
"""
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

import jwt

BUNDLE_ID = "io.github.inspectorgad.kuwbb"
ASC = "https://api.appstoreconnect.apple.com/v1"


def token():
    missing = [n for n in ("ASC_ISSUER_ID", "ASC_KEY_ID", "ASC_KEY_P8") if not os.environ.get(n, "").strip()]
    if missing:
        sys.exit(f"missing secrets: {', '.join(missing)}")
    now = int(time.time())
    return jwt.encode(
        {"iss": os.environ["ASC_ISSUER_ID"].strip(), "iat": now, "exp": now + 600,
         "aud": "appstoreconnect-v1"},
        os.environ["ASC_KEY_P8"], algorithm="ES256",
        headers={"kid": os.environ["ASC_KEY_ID"].strip(), "typ": "JWT"},
    )


def get(tok, path, params=None):
    url = f"{ASC}/{path}" + (f"?{urllib.parse.urlencode(params)}" if params else "")
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {tok}"})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, json.load(resp)
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.load(e)
        except Exception:
            return e.code, {}


def fail(status, body):
    errors = body.get("errors") or [{}]
    sys.exit(f"App Store Connect returned {status}: {errors[0].get('detail', '')}".strip())


tok = token()

status, body = get(tok, "apps", {"filter[bundleId]": BUNDLE_ID})
if status != 200:
    fail(status, body)
apps = body.get("data", [])
if not apps:
    sys.exit(f"no app with bundle ID {BUNDLE_ID}")
app_id = apps[0]["id"]
print(f'App: "{apps[0]["attributes"].get("name", "?")}"  ({BUNDLE_ID})\n')

status, body = get(tok, "builds", {
    "filter[app]": app_id, "limit": "10", "sort": "-uploadedDate",
    "include": "buildBetaDetail",
})
if status != 200:
    fail(status, body)

builds = body.get("data", [])
if not builds:
    print("No builds yet. If one was just uploaded, Apple can take a minute to")
    print("register it; try again shortly.")
    raise SystemExit(0)

# buildBetaDetail carries the state that decides whether testers can install.
details = {d["id"]: d.get("attributes", {}) for d in body.get("included", [])
           if d.get("type") == "buildBetaDetails"}

print(f"{'BUILD':>8}  {'UPLOADED':<20} {'PROCESSING':<12} INTERNAL TESTING")
for b in builds:
    a = b.get("attributes", {})
    rel = ((b.get("relationships") or {}).get("buildBetaDetail") or {}).get("data") or {}
    d = details.get(rel.get("id"), {})
    print(f"{a.get('version', '?'):>8}  {(a.get('uploadedDate') or '?')[:19]:<20} "
          f"{a.get('processingState', '?'):<12} {d.get('internalBuildState', '-')}")

def attr(d, key):
    """A named attribute, or the whole set when the name is not what we expect.

    Apple renames fields occasionally, and a script that silently prints
    nothing is worse than one that prints the wrong shape loudly.
    """
    a = d.get("attributes") or {}
    return a[key] if key in a else f"<no '{key}'; has {sorted(a)}>"


# A build can be perfectly VALID and still reach nobody, because internal
# testing goes through a group and a new app has none. This is far and away
# the commonest reason a first build never appears on a phone.
print()
status, body = get(tok, f"apps/{app_id}/betaGroups", {"limit": "20"})
if status != 200:
    print(f"(could not read beta groups: HTTP {status})")
else:
    groups = body.get("data", [])
    internal = [g for g in groups if (g.get("attributes") or {}).get("isInternalGroup")]
    if not groups:
        print("TESTER GROUPS: none at all.")
    else:
        print(f"TESTER GROUPS: {len(groups)} ({len(internal)} internal)")
    for g in groups:
        a = g.get("attributes") or {}
        kind = "internal" if a.get("isInternalGroup") else "external"
        auto = "every build automatically" if a.get("hasAccessToAllBuilds") else "builds added by hand"
        print(f"  - {attr(g, 'name')}  [{kind}, {auto}]")
        st, tb = get(tok, f"betaGroups/{g['id']}/betaTesters", {"limit": "50"})
        testers = tb.get("data", []) if st == 200 else []
        if not testers:
            print(f"      no testers{'' if st == 200 else f' (HTTP {st})'}")
        for t in testers:
            ta = t.get("attributes") or {}
            who = " ".join(x for x in (ta.get("firstName"), ta.get("lastName")) if x) or "(no name)"
            print(f"      {who} <{ta.get('email', '?')}> {ta.get('state', '')}".rstrip())

    if not internal:
        print()
        print("  >> No internal group exists, so the build has nobody to go to.")
        print("     In App Store Connect: your app > TestFlight > Internal Testing,")
        print("     create a group, add yourself, and add build 1.1 to it (or turn")
        print("     on automatic distribution). Owning the account does not make")
        print("     you a tester.")

newest = builds[0].get("attributes", {})
state = newest.get("processingState")
internal = details.get(
    (((builds[0].get("relationships") or {}).get("buildBetaDetail") or {}).get("data") or {}).get("id"), {}
).get("internalBuildState")

print()
if state == "PROCESSING":
    print("Newest build is still processing. This is normal for up to about half")
    print("an hour after an upload; nothing to do but wait.")
elif state == "VALID" and internal == "READY_FOR_BETA_TESTING":
    print("Newest build is ready. It should be in the TestFlight app now - pull")
    print("to refresh if it is not showing yet.")
elif state == "VALID":
    print(f"Newest build processed, but internal testing says {internal}.")
    if internal == "MISSING_EXPORT_COMPLIANCE":
        print("The encryption question is unanswered. ITSAppUsesNonExemptEncryption")
        print("in ios/project.yml is meant to answer it; check it reached Info.plist.")
    else:
        print("In App Store Connect, open TestFlight and see what the build asks for.")
else:
    print(f"Newest build is {state}. Apple rejected it; App Store Connect's")
    print("TestFlight tab shows why, and the upload email will say too.")
