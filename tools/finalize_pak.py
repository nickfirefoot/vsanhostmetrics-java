#!/usr/bin/env python3
"""Stamp the vendor and licence into a factory-built release pak.

    python3 tools/finalize_pak.py <factory-built.pak> <output.pak>

The factory builder writes a fixed vendor ("VCF Content Factory") into
manifest.txt and leaves eula.txt empty for out-of-tree projects. This rewrites
both, in the outer pak and in its inner adapters.zip, with the vendor below
and the text of this repository's LICENSE. Nothing else in the pak changes.
"""
import io
import json
import pathlib
import sys
import zipfile

VENDOR = "Nick Wilson"
LICENSE = pathlib.Path(__file__).resolve().parent.parent / "LICENSE"


def manifest(data: bytes) -> bytes:
    m = json.loads(data)
    m["vendor"] = VENDOR
    return json.dumps(m, indent=4).encode()


def rewrite(data: bytes, licence: bytes, inner: bool) -> bytes:
    src = zipfile.ZipFile(io.BytesIO(data))
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as dst:
        for info in src.infolist():
            body = src.read(info.filename)
            if info.filename == "manifest.txt":
                body = manifest(body)
            elif info.filename == "eula.txt":
                body = licence
            elif info.filename == "adapters.zip" and not inner:
                body = rewrite(body, licence, True)
            entry = zipfile.ZipInfo(info.filename, info.date_time)
            entry.compress_type = info.compress_type
            entry.external_attr = info.external_attr
            dst.writestr(entry, body)
    return out.getvalue()


def main() -> int:
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    licence = LICENSE.read_bytes()
    built = pathlib.Path(sys.argv[1]).read_bytes()
    pathlib.Path(sys.argv[2]).write_bytes(rewrite(built, licence, False))
    check = zipfile.ZipFile(sys.argv[2])
    inner = zipfile.ZipFile(io.BytesIO(check.read("adapters.zip")))
    for name, z in (("outer", check), ("inner", inner)):
        vendor = json.loads(z.read("manifest.txt")).get("vendor")
        if vendor != VENDOR or z.read("eula.txt") != licence:
            sys.exit(f"{name}: vendor or eula not stamped")
    print(f"stamped vendor={VENDOR!r}, eula={len(licence)} bytes: {sys.argv[2]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
