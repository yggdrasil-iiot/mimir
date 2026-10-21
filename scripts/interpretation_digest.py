#!/usr/bin/env python3
"""Second, independent implementation of the interpretation-contract digest (stdlib only).

The Java checker in this repository and this script are written separately on purpose: the fixture
digests are stamped by this script and verified by the Java tests, so a disagreement between the two
implementations fails the build instead of passing silently.

    python3 scripts/interpretation_digest.py digest  <document.json>
    python3 scripts/interpretation_digest.py stamp   <document.json>     # rewrite its "digest"
    python3 scripts/interpretation_digest.py check   <directory>          # every digested document
    python3 scripts/interpretation_digest.py vectors <jcs-vectors.json>

Digest = SHA-256 of the UTF-8 bytes of the RFC 8785 (JCS) form of the document without its top-level
"digest" and "envelope" members. Numbers must be integers within +/-(2**53 - 1).
"""
import hashlib
import json
import pathlib
import sys

DIGESTED = {"source-bundle", "interpretation-profile", "interpretation-snapshot"}
MAX_SAFE = 2**53 - 1


class Violation(Exception):
    def __init__(self, code, at=""):
        super().__init__(f"{code} at {at}" if at else code)
        self.code = code


def _no_duplicates(pairs):
    seen = {}
    for k, v in pairs:
        if k in seen:
            raise Violation("json.duplicate-key", k)
        seen[k] = v
    return seen


def _reject_float(text):
    raise Violation("jcs.non-integer-number", text)


def _reject_constant(text):
    raise Violation("jcs.non-integer-number", text)


def parse(text):
    return json.loads(text, object_pairs_hook=_no_duplicates,
                      parse_float=_reject_float, parse_constant=_reject_constant)


_ESC = {'"': '\\"', "\\": "\\\\", "\b": "\\b", "\f": "\\f", "\n": "\\n", "\r": "\\r", "\t": "\\t"}


def _quote(s, at):
    out = ['"']
    for ch in s:
        cp = ord(ch)
        if ch in _ESC:
            out.append(_ESC[ch])
        elif cp < 0x20:
            out.append("\\u%04x" % cp)
        elif 0xD800 <= cp <= 0xDFFF:
            raise Violation("jcs.lone-surrogate", at)
        else:
            out.append(ch)
    out.append('"')
    return "".join(out)


def _utf16_key(s):
    return s.encode("utf-16-be", "surrogatepass")


def canonicalize(value, at=""):
    if value is None:
        return "null"
    if value is True:
        return "true"
    if value is False:
        return "false"
    if isinstance(value, int):
        if abs(value) > MAX_SAFE:
            raise Violation("jcs.unsafe-integer", at)
        return str(value)
    if isinstance(value, str):
        return _quote(value, at)
    if isinstance(value, list):
        return "[" + ",".join(canonicalize(v, f"{at}/{i}") for i, v in enumerate(value)) + "]"
    if isinstance(value, dict):
        keys = sorted(value, key=_utf16_key)
        return "{" + ",".join(_quote(k, at) + ":" + canonicalize(value[k], f"{at}/{k}") for k in keys) + "}"
    raise Violation("jcs.unsupported-node", at)


def digest(document):
    scope = {k: v for k, v in document.items() if k not in ("digest", "envelope")}
    return hashlib.sha256(canonicalize(scope).encode("utf-8")).hexdigest()


def _load(path):
    return parse(pathlib.Path(path).read_bytes().decode("utf-8"))


def main(argv):
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    mode, target = argv[1], argv[2]
    if mode == "digest":
        print(digest(_load(target)))
        return 0
    if mode == "stamp":
        p = pathlib.Path(target)
        raw = p.read_bytes().decode("utf-8")
        doc = parse(raw)
        new = digest(doc)
        old = doc.get("digest")
        if old is None or len(old) != 64:
            raise SystemExit(f"{p}: needs a 64-character placeholder \"digest\" to replace")
        if old != new:
            if raw.count(old) != 1:
                raise SystemExit(f"{p}: digest value is not unique in the file")
            p.write_bytes(raw.replace(old, new).encode("utf-8"))
        print(f"{new}  {p}")
        return 0
    if mode == "check":
        bad = 0
        checked = 0
        for p in sorted(pathlib.Path(target).rglob("*.json")):
            loose = json.loads(p.read_bytes().decode("utf-8"))
            if not isinstance(loose, dict) or loose.get("kind") not in DIGESTED:
                continue
            doc = _load(p)
            checked += 1
            got = digest(doc)
            if got != doc.get("digest"):
                bad += 1
                print(f"MISMATCH {p}: file says {doc.get('digest')}, computed {got}")
        print(f"checked {checked} documents, {bad} mismatched")
        return 1 if bad or not checked else 0
    if mode == "vectors":
        cases = _load(target)
        bad = 0
        for case in cases["canonical"]:
            got = canonicalize(parse(case["input"]))
            if got != case["canonical"]:
                bad += 1
                print(f"FAIL {case['name']}: {got!a}")
        for case in cases["rejected"]:
            try:
                canonicalize(parse(case["input"]))
                bad += 1
                print(f"FAIL {case['name']}: accepted")
            except Violation as v:
                if v.code != case["code"]:
                    bad += 1
                    print(f"FAIL {case['name']}: {v.code} != {case['code']}")
        total = len(cases["canonical"]) + len(cases["rejected"])
        print(f"{total} vectors, {bad} failed")
        return 1 if bad else 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
