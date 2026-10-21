# Interpretation snapshot contract 1.0.0

## Purpose

**This is the file contract through which Mímir publishes what it read from a vendor configuration.** Mímir owns these schemas; consumers (Týr, and Bifrost for reconciliation) bundle a byte-identical copy. There is no shared code. The design and the single-reader decision are in `docs/VENDOR-INTERPRETATION.md`.

## Schemas

**The contract comprises five closed JSON Schema 2020-12 documents that reject unknown members, plus `common.schema.json`:**
- `source-bundle` — the files handed over: each artifact's SHA-256, size, safe relative path, role, how and when obtained; a redacted copy must name the artifact it was derived from and the fields removed; product, version, driver and format claims with their basis (stated by the provider, observed in an artifact, or unknown). Claims, not verified facts.
- `interpretation-profile` — interpreter name, version and implementation digest; the products, versions, drivers and input formats it supports; its rules (id and version); limits; known gaps. `unknownVersionPolicy` is fixed to `reject`: a version outside the supported range is refused, not handled by the newest rules.
- `interpretation-snapshot` — pins the bundle and profile by digest and every artifact read by SHA-256; native nodes with their original locators; reference edges with a resolution state (`resolved`, `missing`, `ambiguous`, `external-required`, `unsupported`) — a resolved edge must name its target, any other state must not, and an ambiguous edge lists at least two candidates; claims; unresolved items; coverage; diagnostics. An opaque node must point at its original text.
- `interpret-request` and `interpret-result` — a request names pinned bytes and a profile; nothing is fetched from a connection. A result is `accepted-for-interpretation` with a snapshot reference, or a refusal (`unsupported-profile`, `invalid-input`, `limit-exceeded`) with at least one diagnostic and no snapshot.

## Evidence ownership

**A snapshot claim carries only `observed-in-artifact` or `derived-by-rule`.** A derived claim must name its rule and an observed one must not. `declared-by-reviewer` and `verified-by-virtual-case` belong to the consumer and are rejected in a snapshot.

## Digest and number rules

**A document's digest is SHA-256 over the UTF-8 bytes of the RFC 8785 (JCS) form of the document without its top-level `digest` and `envelope` members.** The envelope (creation time, request id) is outside the digest, so re-interpreting the same bytes gives the same digest.

**Numbers must be integers within ±(2^53 − 1); a decimal is written as a string (for example a scale of `"0.1"`).** This keeps every implementation's bytes identical without having to agree on how to print a floating-point number. Duplicate keys and lone surrogates are rejected.

## Consumer verification

**A consumer can check structure, digests, and references, but cannot check whether an interpretation is right.** What a consumer can check: schema, digest, cross-references inside a snapshot (every node, edge, claim and locator it names exists; locators point only at artifacts the snapshot read), and the pins against the bundle and profile it names (artifact hashes, rule versions). What it cannot check: that the interpretation is right — that would need a second parser, which the single-reader decision rules out.

## Implementations

**Two implementations verify the canonical serialization.** The Java checker (`dev.krillin.mimir.interpretation`) and an independent, standard-library-only Python script (`scripts/interpretation_digest.py`) are tested against each other. The fixture digests are stamped by the Python script and verified by the Java tests, so a disagreement fails the build.
- `python3 scripts/interpretation_digest.py vectors fixtures/interpretation/1.0.0/jcs-vectors.json`
- `python3 scripts/interpretation_digest.py check fixtures/interpretation`
- `mvn test`

## Fixtures

**Fixtures are under `fixtures/interpretation/1.0.0/`:**
- `jcs-vectors.json` — canonical forms written by hand from RFC 8785, including its key-sorting example, plus inputs that must be rejected.
- `valid/pio-like` and `valid/kepware-like` — two synthetic sets whose artifacts use an invented format — they are not vendor formats, and passing them is not support for any product.
- `invalid/cases.json` — rejection cases written as edits to the valid documents, each naming the violation code a consumer must report, so a consumer can run the same cases.

## Exclusions

**Not included: any interpreter or parser; any real product sample.**
