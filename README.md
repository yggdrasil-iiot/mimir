[![CI](https://github.com/yggdrasil-iiot/mimir/actions/workflows/ci.yml/badge.svg)](https://github.com/yggdrasil-iiot/mimir/actions/workflows/ci.yml)

Mímir — the northbound **modeler** of the Yggdrasil IIoT governance portfolio, and its **read side of the plant**: whatever the spine learns from what already runs there is read here, once, and published as data. It browses an OPC-UA type space and *derives/proposes* a canonical, AAS-aligned UDT definition for Bifrost's governance gates to admit. It is a design-time producer and a separate app, coupled to the rest only by a published data/wire contract — **zero shared code** (its own value types, no Bifrost dependency).

## Role in the governance spine

```
                ┌─▶ Bifrost (govern) ─▶ Muninn (feed the UNS)    OPC-UA UDT derivation — built
Mímir (read) ───┼─▶ Týr (author mappings) ─▶ Bifrost (govern)    Ignition snapshots — built; Týr reads 1.0.0 only
                └─▶ Bifrost (reconcile governed vs current)      reconciliation — planned
```

One reader per input format: a source the plant already runs is interpreted here and nowhere else, and the other apps consume what Mímir publishes. The first vendor interpreter is built: Ignition 8.3.10 REST `tags/export` JSON, offline input; interpretation snapshot contract 1.1.0, namespace `ignition83`, rule version 1.2.0, interpreter 0.3.0. Týr reads 1.0.0 snapshots only, and Bifrost reconciliation is not built. See [the design note](docs/VENDOR-INTERPRETATION.md).

Mímir is the *production* layer of canonical-model ownership; **Bifrost remains the authority** (it admits, versions, and is the registry-of-record). Mímir only proposes — a derived definition is not canonical until Bifrost's gate governs it. Concretely, Mímir:

1. Milo-client-**browses** an OPC-UA ObjectType (e.g. `MixerType`) — its `HasComponent` members, their DataTypes, and any `EURange` engineering-range properties.
2. **Derives** a canonical `UdtDefinition` (AAS-submodel-aligned: `name`, `type`, `semanticId`, `range` per member) and writes it as compact JSON.
3. Hands that JSON to Bifrost's **`gates schema`** for compatibility governance (admit + promote, or reject a breaking re-derive).

## Build

```bash
mvn install        # Java 17; shades an executable target/mimir.jar
```

## Run

```bash
# Browse a live OPC-UA type node and derive a canonical definition to <outFile>
java -jar target/mimir.jar derive <endpoint> <nsUri> <typeBrowseName> <templateRef> <version> <outFile> \
     [--omit <member>]

# Interpret an Ignition 8.3.10 tag export and write snapshot documents to <outDir>
java -jar target/mimir.jar ignition-interpret <export.json> <name> <outDir>

# Score an Ignition 8.3.10 tag export against an evaluation key
java -jar target/mimir.jar ignition-score <export.json> <key.json> [--details]
```

`--omit <member>` deliberately drops a member (a test hook for exercising Bifrost's breaking-change rejection — `member.removed`), never used on the faithful derive path.

`ignition-interpret` writes `source-bundle.json`, `interpretation-profile.json`, and `interpretation-snapshot.json`. It validates contract and pins before writing.

`ignition-score` validates generated documents, then prints expected/matched/missing/extra and recall/precision per category. `--details` exposes mismatch rows. Mismatches alone do not produce a failing exit status.

## Acceptance gate

`scripts/run-mimir-gate.sh` boots the Bifrost sim's OPC-UA server, has Mímir derive `MixerType` live, and runs Bifrost's `gates schema` jar to prove the derived definition is admitted + promoted — and that a breaking re-derive is rejected. Zero shared code: the two repos compose only via the built jar + the derived JSON bytes.

## Honest scope

- Design-time and low-frequency — once per type for OPC-UA derivation, and once per handed-over configuration snapshot for vendor interpretation — and not a runtime data path.
- The OPC-UA DataType is reconstituted to the exact string vocabulary Bifrost's gate expects (`11→"Double"`, `1→"Boolean"`); `semanticId` is synthesized from a local-IRI convention (the type node carries none). The synthesized local IRI is a placeholder, not a pointer to an entry in the enterprise semantic dictionary; that dictionary sits outside Yggdrasil; linking a `semanticId` to a dictionary entry is not built.
- `range=null` (an unconstrained numeric) is indistinguishable from any other unconstrained numeric on the type node — deny/constraint semantics live on the spec side, not the equipment type.
- Vendor interpretation is built, design-time, offline, one export per invocation.
- Supports folders, UDT inheritance/overrides/parameters, supported tag sources, dependencies/access, basic alarm configuration, static script targets, and bounded expression-state classification.
- No execution of expressions, scripts, or SQL; no live gateway/device verification, semantic assignment, canonical admission, or proof that configuration is running. Detailed evaluation and limitations are in [the design note](docs/VENDOR-INTERPRETATION.md).

## Dependencies

Eclipse Milo 1.0.0 (OPC-UA client) · Jackson 2.17.

## License

[Apache License 2.0](LICENSE).
