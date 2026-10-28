"""Rewrites machine-specific lines in the exported trees (run by tools/sync.sh; never edits the source projects).

Each rule is (file, pattern, replacement). The patterns are generic on purpose (any user folder, any address), so
this file holds nothing personal itself. A rule that no longer matches upstream is reported, not fatal: the privacy
scan that runs next is what fails the sync if anything personal is left.
Usage: python -I tools/scrub.py <repo root>
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1])

USER_DIR = r"/(?:mnt/)?c/Users/[^/\s\"']+"           # /c/Users/<name> or /mnt/c/Users/<name>
IPV4 = r"\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}"
HERE = '$(cd "$(dirname "$0")/.." && pwd)'
TOOLS = '$(cd "$(dirname "$0")" && pwd)'


def R(p):
    return re.compile(p, re.M)


RULES = [
    # Fire Stick: deploy.sh takes the stick's address and finds adb / gradle on PATH (or $ADB / $GRADLE).
    ("firestick/tools/deploy.sh", R(r"tools/deploy\.sh \[stick-ip\]\s+\(default " + IPV4 + r"\)"),
     "tools/deploy.sh <fire-stick-ip>   (Settings > My Fire TV > About > Network)"),
    ("firestick/tools/deploy.sh", R(r'S="\$\{1:-' + IPV4 + r'\}:5555"'),
     'S="${1:?usage: tools/deploy.sh <fire-stick-ip>}:5555"'),
    ("firestick/tools/deploy.sh", R(r"^ADB=\$\(ls " + USER_DIR + r"/[^\n]*adb\.exe\)$"), 'ADB="${ADB:-adb}"'),
    ("firestick/tools/deploy.sh", R(USER_DIR + r"/Android/gradle-[\d.]+/bin/gradle"), '"${GRADLE:-gradle}"'),
    ("firestick/tools/release.sh", R(r'GRADLE="\$\{GRADLE:-' + USER_DIR + r'/Android/gradle-[\d.]+/bin/gradle\}"'),
     'GRADLE="${GRADLE:-gradle}"'),
    # Offline debrid tests: paths relative to the project and to the Gradle cache of whoever runs them.
    ("firestick/tools/debrid-tests/run.sh", R(r"^L=" + USER_DIR + r"/[^\n]*/app/src/main/java/com/firehome/launcher$"),
     'L="$(cd "$D/../.." && pwd)/app/src/main/java/com/firehome/launcher"'),
    ("firestick/tools/debrid-tests/run.sh", R(r"^C=" + USER_DIR + r"/\.gradle/caches/modules-2/files-2\.1$"),
     'C="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"'),
    # Rulebook: drop the maintainer's own install and note-keeping routine.
    ("firestick/docs/STANDARDS.md", R(r"^- \*\*Installs:\*\*.*\n(?:  .*\n)*"), ""),
    ("firestick/docs/STANDARDS.md", R(r"^- \*\*Bugs and ideas:\*\*.*\n"), "- **Bugs and ideas:** GitHub issues.\n"),
    ("firestick/docs/STANDARDS.md", R(r"deferred bits added to `docs/backlog\.md`"), "deferred bits filed as issues"),
    ("firestick/docs/STANDARDS.md", R(r"\(see backlog\)"), "(a future idea)"),
    # iPhone tools: paths relative to the checkout instead of one PC's synced folder.
    ("ios/tools/build.sh", R(r'^src="' + USER_DIR + r'/[^"\n]*/app/"$'), 'src="' + HERE + '/app/"'),
    ("ios/tools/build.sh", R(r"Source of truth stays in \w+ \(app/\)"), "Source of truth stays in app/"),
    ("ios/tools/install.sh", R(r'^tools="' + USER_DIR + r'/[^"\n]*/tools"$'), 'tools="' + TOOLS + '"'),
    ("ios/tools/install.sh", R(r'^src="' + USER_DIR + r'/[^"\n]*/app/"$'), 'src="' + HERE + '/app/"'),
    ("ios/tools/install.sh", R(r" \(main session only; workers use build\.sh\)"), ""),
    ("ios/tools/install.sh", R(r" \(docs/SETUP\.md\)"), " (BUILDING.md)"),
    ("ios/tools/ipa.sh", R(r'^src="' + USER_DIR + r'/[^"\n]*/app/"$'), 'src="' + HERE + '/app/"'),
    ("ios/tools/ipa.sh", R(r'^tools="' + USER_DIR + r'/[^"\n]*/tools"$'), 'tools="' + TOOLS + '"'),
    ("ios/tools/ipa.sh", R(r'^out="' + USER_DIR + r'/[^"\n]*/builds"$'), 'out="${OUT:-' + HERE + '/builds}"'),
    ("ios/tools/ipa.sh", R(r'^dst="\$HOME/dreamos-ios/main"$'), 'dst="$HOME/dreamos-ios/${BUILD_NAME:-ipa}"'),
    ("ios/tools/ipa.sh", R(r"with the owner's Apple ID"), "with your Apple ID"),
    ("ios/tools/ipa.sh", R(r"Output: \S+/builds/Dream\.ipa \([^)]*\)\."), "Output: builds/Dream.ipa (or $OUT/Dream.ipa)."),
    ("ios/tools/gen-secrets.sh", R(r'^props="\$\{1:-' + USER_DIR + r'/[^}\n]*local\.properties\}"$'),
     'props="${1:-$here/secrets.properties}"'),
    ("ios/tools/gen-secrets.sh", R(r"from the Fire TV app's local\.properties\."),
     "from secrets.properties\n# (copy secrets.properties.example; every key is optional)."),
]

missed = 0
for rel, pat, new in RULES:
    p = ROOT / rel
    if not p.exists():
        print(f"scrub: {rel} not found (rule skipped)")
        continue
    text = p.read_text(encoding="utf-8")
    text2, n = pat.subn(lambda _m: new, text)
    if n == 0:
        missed += 1
        print(f"scrub: no match in {rel} for {pat.pattern[:70]!r}")
    p.write_text(text2, encoding="utf-8", newline="\n")
print(f"scrub: {len(RULES) - missed}/{len(RULES)} rules applied")
