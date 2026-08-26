#!/usr/bin/env python3
"""
Print (and optionally pin) the certificate an APK is signed with.

This exists because a signing mistake is invisible until someone tries to
install the result. Android refuses to install an APK over one signed with a
different key, so a release built with the wrong key looks completely fine —
it installs on a clean phone, passes every test, and only fails for the people
who already have the app, at which point the only fix is uninstalling and
losing their settings.

That is what happened here: the release build was signed with the DEBUG key,
which is generated per machine, so every CI runner produced a different
certificate. Three consecutive published releases had three different ones.

Modern APKs carry no META-INF certificate — signing schemes v2 and v3 live in
the APK Signing Block between the file entries and the central directory — so
this parses that block rather than looking in the zip.

Usage:
    python3 tools/apk-cert.py app.apk
    python3 tools/apk-cert.py app.apk --expect <sha256>
    python3 tools/apk-cert.py app.apk --expect-file tools/release-key.sha256
"""

import argparse
import hashlib
import struct
import sys

V2_SCHEME = 0x7109871A
V3_SCHEME = 0xF05368C0
MAGIC = b"APK Sig Block 42"


def central_directory_offset(blob):
    eocd = blob.rfind(b"PK\x05\x06")
    if eocd < 0:
        raise ValueError("not a zip: no end-of-central-directory record")
    return struct.unpack_from("<I", blob, eocd + 16)[0]


def signing_block(blob):
    cd = central_directory_offset(blob)
    if blob[cd - 16:cd] != MAGIC:
        raise ValueError(
            "no APK Signing Block — the APK is unsigned, or v1-only "
            "(which Android 11+ will not install)"
        )
    size = struct.unpack_from("<Q", blob, cd - 24)[0]
    return blob[cd - 8 - size + 8: cd - 24]


def id_value_pairs(block):
    off = 0
    while off + 12 <= len(block):
        length = struct.unpack_from("<Q", block, off)[0]
        pair_id = struct.unpack_from("<I", block, off + 8)[0]
        yield pair_id, block[off + 12: off + 8 + length]
        off += 8 + length


def length_prefixed(buf):
    off = 0
    while off + 4 <= len(buf):
        length = struct.unpack_from("<I", buf, off)[0]
        yield buf[off + 4: off + 4 + length]
        off += 4 + length


def certificates(path):
    blob = open(path, "rb").read()
    found = []
    for pair_id, value in id_value_pairs(signing_block(blob)):
        if pair_id not in (V2_SCHEME, V3_SCHEME):
            continue
        scheme = "v2" if pair_id == V2_SCHEME else "v3"
        for signer in length_prefixed(next(length_prefixed(value), b"")):
            signed_data = next(length_prefixed(signer), b"")
            fields = length_prefixed(signed_data)
            next(fields, None)                      # digests
            for cert in length_prefixed(next(fields, b"")):
                found.append((scheme, hashlib.sha256(cert).hexdigest()))
    return found


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk")
    ap.add_argument("--expect", help="required certificate SHA-256")
    ap.add_argument("--expect-file", help="file holding the required SHA-256")
    args = ap.parse_args()

    try:
        certs = certificates(args.apk)
    except ValueError as e:
        print(f"FAIL  {args.apk}: {e}", file=sys.stderr)
        return 1
    if not certs:
        print(f"FAIL  {args.apk}: signing block carries no v2/v3 signer", file=sys.stderr)
        return 1

    for scheme, digest in certs:
        print(f"  {scheme}  cert SHA-256 {digest}")

    expected = args.expect
    if not expected and args.expect_file:
        try:
            expected = open(args.expect_file).read().split("#")[0].strip()
        except OSError as e:
            print(f"FAIL  cannot read {args.expect_file}: {e}", file=sys.stderr)
            return 1
    expected = (expected or "").strip().lower().replace(":", "")
    if not expected:
        print("  (no expected fingerprint given — signature present, identity unchecked)")
        return 0

    actual = {d for _, d in certs}
    if expected not in actual:
        print(
            f"FAIL  signed with the wrong key.\n"
            f"      expected {expected}\n"
            f"      got      {', '.join(sorted(actual))}\n"
            f"      An APK signed with a different key CANNOT install over the\n"
            f"      copy already on someone's phone.",
            file=sys.stderr,
        )
        return 1
    print("  ok    matches the pinned release key")
    return 0


if __name__ == "__main__":
    sys.exit(main())
