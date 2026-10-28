"""Removes the builder's home folder from a built Dream.ipa (run by tools/release.sh before the privacy scan).

A Linux (xtool) build leaves one LC_RPATH entry pointing at the Swift toolchain inside the builder's home folder
(/home/<name>/.local/share/swiftly/...). iOS never uses it (the Swift runtime ships with iOS), so the path is
overwritten in place with /usr/lib/swift, NUL-padded to the same length: the Mach-O layout is unchanged.
SideStore / AltStore re-sign the app when they install it.
Usage: python -I tools/ipa_scrub.py <in.ipa> <out.ipa>
"""
import re
import sys
import zipfile

HOME_PATH = re.compile(rb"/home/[^\x00/]+/[^\x00]*\x00")
REPLACEMENT = b"/usr/lib/swift"


def scrub(data):
    count = 0

    def sub(m):
        nonlocal count
        old = m.group(0)
        if len(old) <= len(REPLACEMENT):
            return old
        count += 1
        return REPLACEMENT + b"\x00" * (len(old) - len(REPLACEMENT))

    return HOME_PATH.sub(sub, data), count


def main(src, dst):
    total = 0
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
        for info in zin.infolist():
            data = zin.read(info)
            if not info.is_dir() and data[:4] in (b"\xcf\xfa\xed\xfe", b"\xca\xfe\xba\xbe"):  # Mach-O / fat
                data, n = scrub(data)
                if n:
                    print(f"ipa-scrub: {info.filename}: {n} home-folder path(s) replaced")
                total += n
            zout.writestr(info, data)
    print(f"ipa-scrub: done ({total} replaced)")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)
    main(sys.argv[1], sys.argv[2])
