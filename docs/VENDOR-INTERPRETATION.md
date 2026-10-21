# Vendor configuration interpretation — design note

Status as of 2026-10-21: **the wire contract for interpretation snapshots exists** (version 1.0.0, under `contracts/interpretation/1.0.0/`, described in `contracts/interpretation/README.md`). No interpreter exists, and no product, version or export format is supported. The fixtures are synthetic.

## The decision

**One reader per input format.** A vendor configuration — a Kepware project export, a PIO
configuration set — is interpreted once, here, and published as an *interpretation snapshot*.
Týr (mapping authoring) and Bifrost (reconciliation) consume that snapshot. Neither parses the
vendor file itself.

Mímir is the place because it is already the read side of the plant: it browses a live OPC-UA type
space and derives a definition from what runs. A vendor's tag configuration is the same kind of
input — something the plant already has — and reading it in two places would let two
interpretations of one file disagree without anyone being told.

The snapshot is coupled to its consumers the way every other Yggdrasil contract is: by published
data. Mímir owns the schema; consumers bundle a copy. There is no shared code.

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

Interpretation of each copy, when an interpreter exists, follows the same single-reader rule as the rest of this note.

## Products

| Product | Evidence in hand |
|---|---|
| Kepware | none — product, version, driver and export format not yet obtained |
| BEAN PIO | public product description only |
| BEAN TAG MANAGER | product name only; its relation to the public TM Core / TM Connector is unconfirmed |

Support will be stated per product, version, driver and input format, each with its own evidence
and tests. A synthetic "Kepware-like" fixture passing is not Kepware support.

## Tests that belong here

The parser-side cases move here from the consumer's plan: unresolved, missing and ambiguous
references; evaluation cycles versus harmless structural ones; opaque or unknown containers;
deterministic re-interpretation of the same bytes; hostile archives (path traversal, excessive
depth or size). Expected results are written independently of the interpreter under test.

## If a live read is ever built

Reading a running product's configuration — a Kepware Configuration API `GET`, for example — belongs
here for the same reason the parse does. It is read-only, and it is not planned yet.
