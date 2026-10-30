# Vendor configuration interpretation — design note

Status as of 2026-10-28: **the wire contract for interpretation snapshots exists** (versions 1.0.0 and 1.1.0, under `contracts/interpretation/1.0.0/` and `contracts/interpretation/1.1.0/`, described in `contracts/interpretation/README.md`; 1.1.0 adds read/write/event access on dependencies and an explicit state marker). The first interpreter is built: Ignition 8.3.10 REST `tags/export` JSON offline input, interpretation snapshot contract 1.1.0, namespace `ignition83`, rule version 1.2.0, interpreter 0.3.0, backed by 129 passing tests with enforced profile limits and the published `dev-1` gateway export.

## The decision

**One reader per input format.** A vendor configuration — a Kepware project export, a PIO
configuration set — is interpreted once, here, and published as an *interpretation snapshot*.
Tyr (mapping authoring) and Bifrost (reconciliation) consume that snapshot. Neither parses the
vendor file itself.

Mimir (Mímir) is the place because it is already the read side of the plant: it browses a live OPC-UA type
space and derives a definition from what runs. A vendor's tag configuration is the same kind of
input — something the plant already has — and reading it in two places would let two
interpretations of one file disagree without anyone being told.

The snapshot is coupled to its consumers the way every other Yggdrasil contract is: by published
data. Mimir owns the schema; consumers bundle a copy. There is no shared code.

## What goes in, what comes out

**In:** a set of files handed over offline — configuration, backups, permitted supporting material.
Nothing is fetched from a connection string.

**Out:** a snapshot that records

- the source bundle: every artifact's SHA-256, safe relative path, how and when it was obtained, and
  the product/version/driver *claims* with their basis;
- the interpreter profile: interpreter version, the product range and rules it applies, its limits
  and known gaps;
- native nodes and reference edges, with the original locator of each, and the resolution state of
  every reference (resolved, missing, ambiguous, external, unsupported);
- claims, each tagged `observed-in-artifact` or `derived-by-rule` — those two only. A reviewer's
  declaration or a virtual test result is the consumer's to record, never the snapshot's;
- unresolved items, kept as opaque payload plus location rather than guessed.

Raw bytes are not shipped by default — the manifest and hashes are. A copy with secrets removed can
travel as a separate artifact, and it is never described as the original.

## What it does not do

- Connect to a device, browse, read or write, run SQL, or execute a vendor script, macro or plugin.
- Promote a native tag name, path or address to a semantic identifier. Meaning is assigned by a
  person, downstream.
- Treat a snapshot as canonical. A definition is canonical only once Bifrost has governed it.
- Claim the file is what is running. A configuration someone hands over may not be the live one.

## What the consumers can and cannot check

A consumer verifies the snapshot's pins and closure: that it refers to exact bytes under a declared
profile. It does not verify that the interpretation is right — that would mean a second parser,
which is what this decision rules out.

For reconciliation that cost is smaller than it looks. If a governed definition was adopted from an
earlier snapshot by the same interpreter, a misreading is present on both sides and cancels in the
question *did it change*. This is a tendency, not a guarantee — a misreading that hides the change itself is not caught. It does not cancel in *is the governed definition what the plant means*,
which is a reviewer's question, not a parser's.

## Configuration over time

Status: planned, not built.

The interpretation snapshot describes one configuration at one moment. However, governing what happens after adoption needs a sequence of snapshots over time rather than a single point in time.

The first step needs no interpreter: keep each configuration copy raw and immutable, recorded alongside its SHA-256 hash and the exact details of when and how it was obtained. Differences between consecutive copies and candidates for rollback can already be derived from that information alone, without requiring an interpreter.

Copies come from exports people already make before editing. A resident collector next to the collection tool would be a live read, which is covered by the note's last section ("If a live read is ever built") — not planned, and which app would run it is not decided. If one is ever built it only reads, never writes to the collection tool, and must not affect it if it fails; reading alone does not prove there is no effect (for example file locks or I/O load), which has to be checked per product.

A provided copy is not proof that it is the configuration currently running.

In the same way, the product version in a source bundle is what the provider stated, not something read from the export, because the Ignition `tags/export` JSON has no version field. An unstated version, or one outside the profile, is refused rather than guessed.

Ignition interpretation exists and follows the same single-reader rule as the rest of this note. Snapshot history, collection, diffing, and rollback workflows remain planned. History management remains separate.

## Products

| Product | Version | Input | Evidence in hand |
|---|---|---|---|
| Ignition | 8.3.10 | REST `tags/export` JSON | Implementation, mapping and key specifications, published dev-1 gateway export and key, local Docker lab, 129 passing tests, and blind evaluation across 10 third-party exports. |
| Kepware | — | — | none — product, version, driver and export format not yet obtained |
| BEAN PIO | — | — | public product description only |
| BEAN TAG MANAGER | — | — | product name only; its relation to the public TM Core / TM Connector is unconfirmed |

Resource-directory exports are supporting evidence, not an additional supported interpreter input format.

Support will be stated per product, version, driver and input format, each with its own evidence
and tests. A synthetic "Kepware-like" fixture passing is not Kepware support.

## Tests that belong here

The parser-side cases move here from the consumer's plan: unresolved, missing and ambiguous
references; evaluation cycles versus harmless structural ones; opaque or unknown containers;
deterministic re-interpretation of the same bytes; hostile archives (path traversal, excessive
depth or size). Expected results are written independently of the interpreter under test.

## Ignition 8.3.10 — evidence and measured accuracy

The Ignition 8.3.10 interpreter (interpreter 0.3.0, rule version 1.2.0, namespace `ignition83`) maps offline REST `tags/export` JSON exports into interpretation snapshot contract 1.1.0. It covers folders, UDT inheritance, overrides, parameters, tag sources (OPC, memory, expression, reference, derived, and database), dependencies with read/write/event access, alarm configuration, event-script targets, and expression-state classification. By design, expressions, scripts, and SQL are parsed and classified as static configuration without runtime execution, maintaining a strictly design-time, read-only boundary. 129 tests pass across the suite.

The public `dev-1` scenario was authored from scratch and exported from a real 8.3.10 gateway. Its export and answer key are published in `fixtures/ignition/8.3.10/dev-1`, covering UDT inheritance, overrides, parameters, OPC/memory/expression/reference/derived/database tags, alarm configuration, and event scripts. The interpreter matches the key in every category.

The local test lab (`ignition-lab/`) runs an Ignition Docker gateway under a trial licence. `lab.py` authenticates using a session login and CSRF token to import and export tag configurations.

Blind evaluation was conducted on a third, unseen set of 10 public third-party exports from 8 authors, each round-tripped through Ignition 8.3.10 across 332 imports with 0 failures. Answer keys were written from SPEC 1.2.0 alone by OpenAI Codex and cross-checked row by row by Anthropic Claude Fable (0 key errors; 314 rows = 5.0% where the spec allows two readings were kept). The interpreter author saw only counts. Two earlier sets drove specification amendments (1.1.0 and 1.2.0) and were retired, so every reported number comes from data the interpreter had not seen. Evaluation exports remain private because most source licences do not permit redistribution.

| Category | Expected | Matched | Extra | Recall | Precision |
|---|---|---|---|---|---|
| Nodes | 972 | 972 | 0 | 100% | 100% |
| Edges | 1,761 | 1,761 | 9 | 100% | 99.5% |
| Claims | 2,533 | 2,529 | 40 | 99.8% | 98.4% |
| Unresolved | 986 | 902 | 5 | 91.5% | 99.4% |

Six of ten files are 100% in every category; the remaining mismatches sit in four files, three of which contain the two-reading rows. Exact normalized rows are scored separately, and unresolved rows coalesce by owner and code.

Profile limits are enforced: an export larger than 16 MiB, more than 100,000 nodes, folder/instance nesting deeper than 64, or UDT inheritance deeper than 64 is refused with `ignition.limit-exceeded`. Boundary tests verify each limit.

The CLI records the export file's modification time as the acquisition time.

## If a live read is ever built

Reading a running product's configuration — a Kepware Configuration API `GET`, for example — belongs
here for the same reason the parse does. It is read-only, and it is not planned yet.
