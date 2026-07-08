Mímir — the northbound **modeler** of the Yggdrasil IIoT governance portfolio: it browses an OPC-UA type space and *derives/proposes* a canonical, AAS-aligned UDT definition for Bifrost's governance gates to admit. It is a design-time producer and a separate app, coupled to the rest only by a published data/wire contract — **zero shared code** (its own value types, no Bifrost dependency).

## Role in the governance spine

```
Mímir (model) ──▶ Bifrost (govern) ──▶ Muninn (feed the UNS)
```

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
```

`--omit <member>` deliberately drops a member (a test hook for exercising Bifrost's breaking-change rejection — `member.removed`), never used on the faithful derive path.

## Acceptance gate

`scripts/run-mimir-gate.sh` boots the Bifrost sim's OPC-UA server, has Mímir derive `MixerType` live, and runs Bifrost's `gates schema` jar to prove the derived definition is admitted + promoted — and that a breaking re-derive is rejected. Zero shared code: the two repos compose only via the built jar + the derived JSON bytes.

## Honest scope

- Design-time, low-frequency (once per type), not a runtime data path.
- The OPC-UA DataType is reconstituted to the exact string vocabulary Bifrost's gate expects (`11→"Double"`, `1→"Boolean"`); `semanticId` is synthesized from a local-IRI convention (the type node carries none).
- `range=null` (an unconstrained numeric) is indistinguishable from any other unconstrained numeric on the type node — deny/constraint semantics live on the spec side, not the equipment type.

## Dependencies

Eclipse Milo 1.0.0 (OPC-UA client) · Jackson 2.17.

## License

[Apache License 2.0](LICENSE).
