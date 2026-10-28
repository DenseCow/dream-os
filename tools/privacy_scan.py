"""Privacy scan for the published repository and the release APK. Exits 1 on any hit that isn't allow-listed.

  python -I tools/privacy_scan.py repo <dir>      scan a folder (the repo, or a fresh clone of it)
  python -I tools/privacy_scan.py apk  <file>     scan an APK / IPA, nested archives included

What it looks for:
  personal   this PC's user name and home folder, the maintainer's mail names (PERSONAL_WORDS, from
             tools/sources.local or the environment), Windows/WSL/OneDrive user paths, any address @gmail etc.
  secret     the VALUE of every key and password in the files listed in SECRET_FILES (the source project's
             local.properties, the signing properties). Values are never printed, only which key matched.
  network    private LAN addresses (10/8, 172.16/12, 192.168/16)
  token      GitHub / AWS / Google / Slack / OpenAI style tokens, private key blocks, Apple team ids in config
  credential `api_key = "..."`, `secret: "..."`, `token=...` style literals and long random-looking strings
  email      any e-mail address
  metadata   EXIF / XMP / text chunks in PNG and JPEG files
Every hit is printed. Hits that a person reviewed and judged harmless live in tools/privacy-allow.txt
(rule, path glob, a piece of the line). Text files in third-party vendored folders skip the credential and
e-mail heuristics (they are upstream code) but never the personal and secret checks.
"""
import fnmatch
import io
import os
import pathlib
import re
import sys
import zipfile

HERE = pathlib.Path(__file__).resolve().parent
TEXT_LIMIT = 8 * 1024 * 1024

VENDOR_GLOBS = [
    "ios/app/Vendor/Python/Stdlib/python/*",
    "ios/app/Vendor/Python/Python.xcframework/*",
]


def load_env_file():
    """tools/sources.local is shell syntax (KEY="value" with $HOME); read the simple KEY=VALUE lines."""
    env = {}
    f = HERE / "sources.local"
    if f.exists():
        for line in f.read_text(encoding="utf-8").splitlines():
            m = re.match(r'^([A-Z0-9_]+)="?([^"$`]*(?:\$HOME[^"`]*)?)"?\s*$', line.strip())
            if m:
                env[m.group(1)] = m.group(2).replace("$HOME", str(pathlib.Path.home()).replace("\\", "/"))
    env.update({k: v for k, v in os.environ.items() if k in ("SECRET_FILES", "PERSONAL_WORDS")})
    return env


def to_posix(p):
    p = p.strip()
    m = re.match(r"^/([a-zA-Z])/(.*)$", p)
    return f"{m.group(1)}:/{m.group(2)}" if m and os.name == "nt" else p


ENV = load_env_file()

# --- personal words: this machine's user name + anything listed in PERSONAL_WORDS (comma separated) -----------
personal = set()
for v in (os.environ.get("USERNAME"), os.environ.get("USER"), pathlib.Path.home().name):
    if v and len(v) >= 3 and v.lower() not in ("root", "user", "runner", "admin"):
        personal.add(v.lower())
for w in ENV.get("PERSONAL_WORDS", "").split(","):
    if len(w.strip()) >= 3:
        personal.add(w.strip().lower())

# --- secret values (never printed) -------------------------------------------------------------------------------
secrets = {}  # value -> label
for f in filter(None, ENV.get("SECRET_FILES", "").split(";")):
    p = pathlib.Path(to_posix(f))
    if not p.is_file():
        continue
    for line in p.read_text(encoding="utf-8", errors="replace").splitlines():
        m = re.match(r"^\s*([A-Za-z0-9_.]+)\s*=\s*(.+?)\s*$", line)
        # Not secrets: tool paths, and the key alias (a short generic name that is also in the public certificate).
        if not m or m.group(1) in ("sdk.dir", "python.build", "storeFile", "release.signing", "keyAlias"):
            continue
        val = m.group(2).replace("\\:", ":")
        if len(val) >= 6:
            secrets[val] = f"{p.name}:{m.group(1)}"

PERSONAL_RE = [
    ("personal", re.compile(r"[A-Za-z]:[\\/]+Users[\\/]+(?!you\b|<|\$|%|Public\b)[A-Za-z0-9._-]+", re.I)),
    ("personal", re.compile(r"/(?:mnt/)?c/Users/(?!you\b|<|\$)[A-Za-z0-9._-]+", re.I)),
    ("personal", re.compile(r"OneDrive[\\/]", re.I)),
] + [("personal", re.compile(re.escape(w), re.I)) for w in sorted(personal)]
# Personal mail addresses: checked everywhere except upstream vendored code (its authors' public addresses).
MAIL_RE = re.compile(r"\b[\w.+-]+(?:@| at )(?:gmail|outlook|hotmail|icloud|yahoo|proton(?:mail)?|me)\.(?:com|me)\b", re.I)

NETWORK_RE = re.compile(
    r"(?<![\d.])(?:10\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])|192\.168)\.\d{1,3}\.\d{1,3}(?![\d.])")

TOKEN_RE = [
    re.compile(r"\bgh[opsur]_[A-Za-z0-9]{20,}"),
    re.compile(r"\bgithub_pat_[A-Za-z0-9_]{20,}"),
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b"),
    re.compile(r"\bxox[abprs]-[A-Za-z0-9-]{10,}"),
    re.compile(r"\bsk-(?:ant-)?[A-Za-z0-9_-]{20,}"),
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----"),
    re.compile(r"DEVELOPMENT_TEAM\s*=\s*[A-Z0-9]{10}|" + "Team" + r"Identifier|com\.apple\.developer\.team-identifier"),
]

CRED_RE = [
    re.compile(r"""(?i)(?:api[_-]?key|apikey|secret|token|passw(?:or)?d|client[_-]?id|bearer)["']?\s*[:=]\s*["']"""
               r"""(?![$\\{<(])([^"'\s]{8,})["']"""),
    re.compile(r"(?i)(?:api_key|apikey|access_token|client_secret)=(?![$\\{<(&])([A-Za-z0-9_-]{16,})"),
]
LONG_HEX = re.compile(r"(?<![A-Za-z0-9])[A-Fa-f0-9]{32,}(?![A-Za-z0-9])")
LONG_B64 = re.compile(r"(?<![A-Za-z0-9+/_-])[A-Za-z0-9+/_-]{40,}={0,2}(?![A-Za-z0-9+/_-])")
# Lower-case domains with a real top-level domain (Kotlin's `this@Name.field` is not an address).
EMAIL_RE = re.compile(r"\b[A-Za-z0-9._%+-]+@[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|org|net|io|dev|me|co|uk|de|app|tv|edu|info)\b")
EMAIL_OK = re.compile(r"@(?:users\.noreply\.github\.com|noreply\.github\.com|anthropic\.com|example\.(?:com|org|net))$", re.I)


def random_looking(s):
    """True for strings that look like keys: mixed letters and digits, not words or paths."""
    if "/" in s and s.count("/") > 2:
        return False
    has_d = any(c.isdigit() for c in s)
    has_l = any(c.islower() for c in s)
    has_u = any(c.isupper() for c in s)
    return has_d and (has_l or has_u) and len(set(s)) > 10


# --- allow-list --------------------------------------------------------------------------------------------------
ALLOW = []
af = HERE / "privacy-allow.txt"
if af.exists():
    for line in af.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) >= 3:
            ALLOW.append((parts[0].strip(), parts[1].strip(), parts[2]))
used_allow = set()


def allowed(rule, path, snippet):
    for i, (r, g, piece) in enumerate(ALLOW):
        if r == rule and fnmatch.fnmatch(path, g) and piece in snippet:
            used_allow.add(i)
            return True
    return False


hits = []


def hit(rule, path, lineno, snippet, redact=None):
    shown = re.sub(r"[^\x20-\x7e]", ".", snippet.strip())[:180]
    if redact:
        shown = f"<value of {redact}>"
    if rule != "secret" and allowed(rule, path, snippet):
        return
    hits.append(f"{rule:10} {path}:{lineno}: {shown}")


def is_vendor(path):
    return any(fnmatch.fnmatch(path, g) for g in VENDOR_GLOBS)


def scan_text(path, text, heuristics=True):
    lines = text.splitlines()
    low_secrets = [(v, l) for v, l in secrets.items()]
    for n, line in enumerate(lines, 1):
        for rule, rx in PERSONAL_RE:
            if rx.search(line):
                hit(rule, path, n, line)
        for v, label in low_secrets:
            if v in line:
                hit("secret", path, n, line, redact=label)
        if MAIL_RE.search(line) and not is_vendor(path):
            hit("personal", path, n, line)
        if NETWORK_RE.search(line):
            hit("network", path, n, line)
        for rx in TOKEN_RE:
            if rx.search(line):
                hit("token", path, n, line)
        if not heuristics:
            continue
        for rx in CRED_RE:
            for m in rx.finditer(line):
                if random_looking(m.group(1)) or len(m.group(1)) >= 20:
                    hit("credential", path, n, line)
        for rx in (LONG_HEX, LONG_B64):
            for m in rx.finditer(line):
                if random_looking(m.group(0)):
                    hit("credential", path, n, line)
                    break
        for m in EMAIL_RE.finditer(line):
            if not EMAIL_OK.search(m.group(0)):
                hit("email", path, n, line)


def scan_bytes(path, data):
    """Binary files: personal words and secret values (ASCII and UTF-16), private keys, tokens, addresses."""
    for rule, rx in PERSONAL_RE:
        brx = re.compile(rx.pattern.encode(), rx.flags & re.I)
        for m in brx.finditer(data):
            hit(rule, path, 0, data[max(0, m.start() - 40):m.end() + 40].decode("latin-1"))
            break
        if rx.pattern.isalnum():
            u16 = re.compile(re.escape(rx.pattern.encode("utf-16-le")), re.I)
            if u16.search(data):
                hit(rule, path, 0, f"(UTF-16) {rx.pattern}")
    for v, label in secrets.items():
        if v.encode() in data or v.encode("utf-16-le") in data:
            hit("secret", path, 0, "", redact=label)
    for m in re.finditer(NETWORK_RE.pattern.encode(), data):
        hit("network", path, 0, data[max(0, m.start() - 30):m.end() + 30].decode("latin-1"))
    for rx in TOKEN_RE:
        for m in re.finditer(rx.pattern.encode(), data):
            hit("token", path, 0, data[max(0, m.start() - 20):m.end() + 20].decode("latin-1"))


def png_meta(data):
    found, i = [], 8
    while i + 8 <= len(data):
        ln = int.from_bytes(data[i:i + 4], "big")
        typ = data[i + 4:i + 8]
        if typ in (b"tEXt", b"iTXt", b"zTXt", b"eXIf", b"tIME"):
            found.append(typ.decode() + ": " + data[i + 8:i + 8 + min(ln, 60)].decode("latin-1"))
        i += 12 + ln
        if typ == b"IEND":
            break
    return found


def jpeg_meta(data):
    found, i = [], 2
    while i + 4 <= len(data) and data[i] == 0xFF:
        marker = data[i + 1]
        if marker in (0xD9, 0xDA):
            break
        ln = int.from_bytes(data[i + 2:i + 4], "big")
        seg = data[i + 4:i + 2 + ln]
        if marker == 0xE1:
            found.append("APP1 " + seg[:30].decode("latin-1"))
        elif marker == 0xED:
            found.append("APP13 (IPTC/Photoshop)")
        elif marker == 0xFE:
            found.append("COM " + seg[:60].decode("latin-1"))
        i += 2 + ln
    return found


def scan_file(path, data):
    low = path.lower()
    if low.endswith(".png"):
        for m in png_meta(data):
            hit("metadata", path, 0, m)
    elif low.endswith((".jpg", ".jpeg")):
        for m in jpeg_meta(data):
            hit("metadata", path, 0, m)
    if b"\0" in data[:4096] or len(data) > TEXT_LIMIT:
        scan_bytes(path, data)
        if data[:4] == b"PK\x03\x04":
            scan_zip(path, data)
        return
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        scan_bytes(path, data)
        return
    scan_text(path, text, heuristics=not is_vendor(path))


def scan_zip(path, data):
    try:
        z = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile:
        return
    for info in z.infolist():
        if info.is_dir():
            continue
        inner = z.read(info)
        name = f"{path}!{info.filename}"
        if inner[:4] == b"PK\x03\x04":
            scan_bytes(name, inner)
            scan_zip(name, inner)
        else:
            scan_bytes(name, inner)


def main():
    if len(sys.argv) != 3 or sys.argv[1] not in ("repo", "apk"):
        print(__doc__)
        return 2
    mode, target = sys.argv[1], pathlib.Path(sys.argv[2])
    if not secrets:
        print("privacy-scan: NOTE: no SECRET_FILES found; secret-value check skipped")
    if mode == "apk":
        scan_file(target.name, target.read_bytes())
    else:
        for p in sorted(target.rglob("*")):
            rel = p.relative_to(target).as_posix()
            if p.is_dir() or rel.startswith(".git/") or "/.git/" in rel:
                continue
            if rel in ("tools/sources.local",):
                continue
            scan_file(rel, p.read_bytes())
    print(f"privacy-scan: {mode} {target.name}: {len(secrets)} secret values, {len(personal)} personal words checked")
    for h in hits:
        print("  HIT " + h)
    stale = [ALLOW[i] for i in range(len(ALLOW)) if i not in used_allow]
    if mode == "repo" and stale:
        print(f"privacy-scan: {len(stale)} allow-list entries matched nothing (fine, or prune them)")
    if hits:
        print(f"privacy-scan: FAILED with {len(hits)} hit(s)")
        return 1
    print("privacy-scan: clean")
    return 0


if __name__ == "__main__":
    sys.exit(main())
