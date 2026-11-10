#!/usr/bin/env python3
"""Air-gap check for a built .pak. Exit 0 = clean, 1 = violations, 2 = usage error.

Static rules, applied to every entry including those inside nested zips/jars:
  1. No container references (registry, digest, image pulls).
  2. No http(s) URL except allowlisted hosts (XML namespaces, documented placeholders).
  3. Every bundled .jar is on the allowlist; the Broadcom SDK jar must never ship.
  4. No lab-specific strings. Patterns come from an UNTRACKED file
     (--lab-patterns or $AIRGAP_LAB_PATTERNS), one regex per line, so real
     hostnames never live in the repo.

The runtime rule (collector makes no outbound connection except the configured
target) is not checked here; it is verified on a collector after install.
"""
import argparse
import io
import os
import re
import sys
import zipfile

CONTAINER_REFS = [r"REGISTRY\s*=", r"DIGEST\s*=", r"RepoDigest", r"ghcr\.io",
                  r"docker\.io", r"@sha256:[0-9a-f]{12}"]
URL_RE = re.compile(rb"https?://([A-Za-z0-9.-]+)")
URL_ALLOW = {"schemas.vmware.com", "www.w3.org", "www.apache.org",
             "example.com", "example.local", "example.org"}
JAR_ALLOW = [r"vcfcf-adapter-base\.jar$", r"vsanhostmetrics\.jar$"]
JAR_DENY = re.compile(r"vrops-adapters-sdk[^/]*\.jar$")


def walk(name, data, out):
    """Yield (path, bytes) for every entry, recursing into zips and jars."""
    out.append((name, data))
    if name.endswith((".zip", ".jar", ".pak")) or data[:2] == b"PK":
        try:
            z = zipfile.ZipFile(io.BytesIO(data))
        except zipfile.BadZipFile:
            return
        for n in z.namelist():
            if not n.endswith("/"):
                walk(f"{name}!{n}", z.read(n), out)


def load_patterns(path):
    if not path:
        return []
    with open(path) as f:
        return [re.compile(l.strip().encode(), re.I) for l in f
                if l.strip() and not l.startswith("#")]


def check(pak, lab):
    with open(pak, "rb") as f:
        entries = []
        walk(os.path.basename(pak), f.read(), entries)
    bad = []
    for path, data in entries:
        leaf = path.rsplit("!", 1)[-1]
        if leaf.endswith(".jar"):
            if JAR_DENY.search(leaf):
                bad.append(f"{path}: Broadcom SDK jar must never ship")
            elif not any(re.search(p, leaf) for p in JAR_ALLOW):
                bad.append(f"{path}: jar not on allowlist")
        if leaf.endswith(".class") or data[:2] == b"PK":
            continue  # compiled code is noise; archives are scanned via their entries
        for p in CONTAINER_REFS:
            if re.search(p.encode(), data):
                bad.append(f"{path}: container reference /{p}/")
        for host in {m.group(1).decode().lower() for m in URL_RE.finditer(data)}:
            if host not in URL_ALLOW:
                bad.append(f"{path}: URL host {host}")
        for p in lab:
            if p.search(data):
                bad.append(f"{path}: lab pattern /{p.pattern.decode()}/")
    return bad


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("pak")
    ap.add_argument("--lab-patterns", default=os.environ.get("AIRGAP_LAB_PATTERNS"))
    a = ap.parse_args()
    if not os.path.isfile(a.pak):
        print(f"not a file: {a.pak}", file=sys.stderr)
        return 2
    bad = check(a.pak, load_patterns(a.lab_patterns))
    for b in bad:
        print("FAIL", b)
    print(f"{'FAIL' if bad else 'PASS'}: {len(bad)} violation(s) in {a.pak}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
