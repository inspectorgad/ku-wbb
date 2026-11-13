#!/usr/bin/env python3
"""Point Ask Claude at one commit's pack ahead of the published one.

A build from a branch carries that branch's screens, but AskEngine.PACK_URLS
names the pack published from main, so without this the reader would be asked
to test new questions against last week's tables and last week's prompt. This
puts the commit's own docs/ask-data.json at the front of the list, leaving the
published ones behind it as the fallback they already are.

Pinned to a commit rather than a branch on purpose: the build keeps reading the
data it was built against however the branch moves on afterwards.

Both the Android build (build-apk.yml) and the iPhone one (ios-testflight.yml)
call this, which is why it is a script and not a line of sed in each. The sed
it replaces worked on the Linux runner and failed on the Mac one, where -i
takes the backup suffix as its argument.

    python3 scripts/pin_ask_pack.py https://raw.githubusercontent.com/o/r/<sha>/docs/ask-data.json
"""
import pathlib
import sys

FILE = pathlib.Path("shared/src/commonMain/kotlin/com/example/data/AskEngine.kt")
# The line the new one goes above: the published pack, which stays as the
# fallback. Matched whole so a half-applied edit cannot pass unnoticed.
PUBLISHED = '"https://inspectorgad.github.io/ku-wbb/ask-data.json",'


def pin(text, url):
    """`text` with `url` listed just above the published pack."""
    out = []
    found = 0
    for line in text.splitlines(keepends=True):
        if line.strip() == PUBLISHED:
            indent = line[: len(line) - len(line.lstrip())]
            out.append(f'{indent}"{url}",\n')
            found += 1
        out.append(line)
    if found != 1:
        raise SystemExit(
            f"{FILE} lists the published pack {found} times, expected exactly 1 — "
            "PACK_URLS has moved, and this build would read the wrong data."
        )
    return "".join(out)


def main(argv):
    if len(argv) != 2:
        raise SystemExit(__doc__)
    url = argv[1]
    if not url.startswith("https://"):
        raise SystemExit(f"not an https URL: {url}")
    FILE.write_text(pin(FILE.read_text(), url))
    # Printed so the build log says, in one place, what this build will read.
    keep = False
    for line in FILE.read_text().splitlines():
        if "val PACK_URLS" in line:
            keep = True
        if keep:
            print(line)
        if keep and line.strip() == ")":
            break


if __name__ == "__main__":
    main(sys.argv)
