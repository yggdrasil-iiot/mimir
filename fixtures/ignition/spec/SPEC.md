# Ignition tag-export mapping specification 1.0.0

## Scope and evidence

This specification targets Ignition 8.3.10 REST `tags/export` JSON and Mímir contract 1.1.0. It describes configuration, dependencies, and identifiable gaps; it does not certify runtime values, device capabilities, or installed gateway resources.

All directory files were read. Only `dev-1/export.json` supplies evidence for the dev-1 answer key. The scenario input and resource files illustrate export behavior but do not repair omissions in that export. No sources outside this directory were consulted.

Each rule below is a versioned interpretation-profile rule, with prefix `ignition83.` and version `1.0.0`. An implementation must declare these rules, supported input/version, limits, and known gaps. An unsupported product version is refused. Duplicate JSON keys, invalid Unicode, and malformed required structure are invalid input; resource-limit failures are refusals, not silently truncated snapshots.

**Reason:** Both independent answer-key authors and interpreter authors need the same bounded, reproducible interpretation.

## 1. Identity and locations — `identity`

Keep provider-relative, `/`-separated tag paths and Ignition's `_types_/…` namespace. The provider envelope is not a node; its immediate children are graph roots. Explicit folders, including `_types_`, are nodes.

“Locator” in the answer key means logical identity. It is distinct from the contract's artifact locator:

- Snapshot `nativeId` is the logical locator defined here.
- Snapshot `nativePath` contains the original tag-name segments for ordinary tag nodes.
- Snapshot `locator` is an artifact pin plus JSON Pointer into the export.
- Materialized inherited members use their exported member object as their artifact locator when present; otherwise use their defining member object. Their logical locator always identifies the materialized member.
- Claim evidence points to the actual contributing property locations, including definitions and overrides.

Use these additional logical locators:

| Element | Locator |
|---|---|
| Event script | `<tag>#event/<eventid>` |
| Alarm | `<tag>#alarm/<alarm-name>` |
| Named OPC connection | `@external/opc/<server-name>` |
| Named database connection | `@external/db/<datasource-name>` |

Within each name segment, escape `%`, `/`, and `#` as `%25`, `%2F`, and `%23`; escape a tag segment's initial `@` as `%40`. Apply the same escaping to event, alarm, and connection names. Spaces and Unicode remain literal. Extension namespaces are logical identities, not claimed Ignition tag paths.

A connection node's artifact locator points to the first corresponding property in JSON Pointer lexical order. Its existence means “this connection name was referenced,” not “this connection configuration was supplied.”

**Reason:** Artifact offsets and generated IDs can change without changing an element's identity.

**Gaps:** Case-colliding sibling names or duplicate effective alarm/event identities are invalid input; do not merge them or choose one.

## 2. Nodes — `node-kind`

| Ignition element | Snapshot mapping |
|---|---|
| Folder | `group` |
| UdtType | `group`; `tagType` claim |
| UdtInstance | `group`; `tagType` claim; `inherits` edge |
| AtomicTag, `memory`, `opc`, `reference`, `derived`, `db` | `tag` |
| AtomicTag, `expr` | `operation` |
| Tag event script | Child `opaque`, `state: unknown`, payload pointing to script text |
| Named OPC/database connection | External `connection` stub |
| Named alarm | Child `policy`; configuration claims |

Retain the proposed kinds. Add database stubs because a named datasource is also an external dependency. Alarm nodes describe configuration; they do not represent live alarm instances.

State is omitted on groups, connections, policies, and ordinary tags. It is mandatory on operations and opaque nodes. Do not label memory tags stateless merely because they have no expression.

Unknown tag types or unrecognized value sources become `opaque` nodes with original payload, unknown state, and both `unsupported` and `behavior-unknown` items.

**Reason:** Node kinds describe native configuration roles without implying successful execution.

**Gaps:** Every event script gets `behavior-unknown`, even when some dependencies are extracted. Unknown alarm modes or bindings retain their payload locations and get `unsupported`; no alarm evaluation is guessed.

## 3. Inheritance and parameters — `effective-config`

Resolve `typeId` in the provider's UDT-definition namespace, including type folders. A qualified provider outside the input provider is external. Do not search unrelated folders for a similarly named type.

Apply configuration in this order:

1. Ancestor type definitions, oldest ancestor first.
2. Descendant type definitions.
3. Instance configuration.
4. Member-specific overrides at the corresponding nested path.

Merge members by native name, recursively. Materialize inherited members even if omitted from `tags`. Name-only exported members retain inherited properties. A member object does not erase unspecified properties. Explicit scalar properties replace inherited scalars; explicit `null` remains null rather than meaning “absent.” Merge parameters by parameter name, alarms by alarm name, and event scripts by `eventid`. Other arrays replace as whole values. Unknown deletion/reset encodings are unsupported rather than interpreted as ordinary values.

Emit one `inherits` edge for each UDT instance or derived UDT type, with `access: read`: the instance's effective configuration is read from its type definition. (Decided 2026-10-27; Codex's draft had `unknown`.) Do not emit member-level `inherits` or `overrides` edges in this profile; effective claims preserve the result and its evidence.

Resolve parameter values in the containing UDT scope: ancestor default, descendant override, then instance override. Nested UDTs establish their own scope, with explicit parameter bindings evaluated from the containing scope. Type-definition members are interpreted using their type's effective defaults, not an arbitrary instance.

Recognize parameter bindings encoded as `{"bindType":"parameter","binding":"…"}`. Resolve declared `{Param}` tokens in those bindings, UDT expressions, documentation, and alarm property bindings. This is scoped binding interpretation, not a global brace replacement. Preserve raw text and distinguish tag references and derived-tag placeholders.

Support literal string, boolean, and numeric parameters. Embedded scalar parameters use canonical text; a whole-property parameter keeps its scalar type. Missing or cyclic parameters prevent the affected effective value from being asserted.

**Reason:** Ignition exports can contain inherited members without their effective properties.

**Gaps:** Missing local types/parameters produce `missing`; ambiguous types produce `ambiguous`; external types produce `external-required`; cyclic inheritance or unsupported binding/reset forms produce `unsupported`. Do not invent affected member values.

## 4. Dependencies and access — `dependencies`

Edges point from the configuration element using a dependency to its target. `write` is an access value, not a relation.

| Dependency | Relation/access |
|---|---|
| Expression tag reference | `input` / `read` |
| Reference tag source | `reference` / `read`, plus `reference` / `write` when locally writable |
| Derived tag `sourceTagPath` | `reference` / `read`; additionally `reference` / `write` for a configured setter when locally writable |
| References in derived getter/setter | `input` / `read` |
| OPC connection | `external` / `read`, plus `external` / `write` when locally writable |
| Database Select query datasource | `external` / `read` |
| Other database query modes | `external` / `unknown`, unless a separately supported mode establishes access |
| Event script triggering tag | Script → tag, `input` / `event` |
| Static script read/write target | `reference` / `read` or `write` |
| Alarm's monitored tag | Alarm → tag, `policy` / `read` |
| Supported alarm binding reference | Alarm → referenced tag, `input` / `read` |
| Hierarchical parent → child | `contains`, with access absent |

“Locally writable” means effective `readOnly` is false. For this version's tag configuration, omitted `readOnly` defaults to false after inheritance. This default affects dependency extraction; it is not an observed property claim. Unknown bindings to `readOnly` yield a potential write edge with `access: unknown` and an unresolved item.

Keep reference write-through edges. A write edge records a configured route, not proof that permissions, downstream tags, or an OPC server allow the write. Do not suppress it because the target appears unwritable or offline.

A derived setter writes its result to `sourceTagPath`; `{value}` is the proposed incoming value and `{source}` denotes the configured source. Neither creates a tag named `value` or `source`.

For event scripts, recognize direct calls to `system.tag.read`, `readBlocking`, `readAsync`, `write`, `writeBlocking`, `writeAsync`, and legacy `writeSynchronous`/`writeAll` with literal target strings or literal lists. Extract possible dependencies even inside conditions. Do not execute Python, follow aliases, evaluate variables, or infer dynamically constructed targets. Normalize callback bodies only through the same static syntax rules.

OPC/database edges remain `external-required`, even though a connection stub exists. They have canonical connection text but no `targetNodeId`; a stub does not supply the external resource.

**Reason:** Dependencies express configured effects while separating local resolution from external availability.

**Gaps:** Each nonresolved dependency produces a corresponding unresolved item. Dynamic script targets get `unsupported`; script behavior remains unknown. SQL and project scripts are never run. SQL behavior gets `behavior-unknown`; the named datasource gets `external-required`.

## 5. Reference parsing — `references`

Parse expressions with a lexer/parser; exclude braces inside quoted strings. Parse path-valued properties and script string arguments as paths, without requiring expression braces.

- `{[.]x}` resolves relative to the owning tag's folder.
- Relative parent navigation is applied segment by segment and cannot escape the provider root.
- `{[~]x}` resolves from the current provider root.
- `{[provider]x}` resolves from that provider's root.
- An explicitly different provider is `external-required`.
- An explicit provider can be treated as the current provider only when its name is supplied in pinned input context. A blank export-envelope name does not establish that name.
- Unqualified path strings in path-valued tag properties are provider-relative. Unqualified script paths requiring a gateway default provider are external unless that context is supplied.
- `{Param}` is a parameter only when declared in scope. In derived expressions, `{source}` and `{value}` take precedence as placeholders. Other bare expression references are unsupported unless their syntax has a supported interpretation.
- Tag-property and array-element references resolve to their owning tag node, retaining the property/index selector in edge `targetText` and `owner.field`. No fabricated child tag is created.

A valid same-provider path absent from the export is `missing`. Malformed or dynamic syntax is `unsupported`; multiple valid candidates are `ambiguous`. These are changes to the proposed blanket “unresolvable → missing” default.

Resolve references within inherited expressions relative to the materialized member, not the original definition.

**Reason:** Absence, external scope, ambiguity, and unsupported syntax are different failures.

## 6. Expression state — `expression-state`

State describes retained evaluation state, not whether the result varies over time.

Analyze the complete expression after supported parameter substitution, without evaluating it:

| Supported syntax/function | Classification |
|---|---|
| Literals, tag/parameter inputs, arithmetic, comparison, boolean operators, conditional syntax | Stateless |
| `if`, `coalesce`, `isNull`, `abs`, `ceil`, `floor`, `round`, `min`, `max`, `sqrt`, `pow`, `len`, `lower`, `upper`, `trim`, `toInt`, `toFloat`, `toDouble`, `toString`, `toBoolean` | Stateless, if all arguments are understood |
| `hasChanged` | Stateful: `edge-detect` and `previous-value` |
| `now` | Stateless clock input, if arguments are understood |
| Any other function, including `runScript`, dynamic `tag`, and unrecognized timer/previous-value functions | Unknown |

Function recognition is by exact supported Ignition function identity and supported call form, not substring matches. An unsupported argument form makes the expression unknown. Ordinary dependencies remain extractable from otherwise unknown expressions.

Union state kinds across understood subexpressions. Unknown syntax or behavior dominates the whole expression: report `unknown`, omit `stateKinds`, and add `behavior-unknown`. Do not assert a partial known state as the whole result.

Change the proposed “now implies timer” default: returning the clock or scheduling reevaluation does not itself establish retained timer state. This initial profile recognizes no functions as `accumulator` or `timer`; unfamiliar functions remain unknown. Previous-value behavior is established here only through `hasChanged`.

Analyze the operation-node dependency graph after resolution. A cycle among operation value dependencies adds `feedback` to otherwise understood participating operations. Structural, inheritance, event, or potential-write cycles do not establish feedback. Unknown expressions remain unknown.

Every known state gets an asserted `derived-by-rule` claim, field `state`, under this rule, and the node's `stateClaimId` must reference it. Stateful nodes carry nonempty, unique `stateKinds`. Unknown nodes have no state claim.

**Reason:** A changing value, scheduler, and retained evaluation history are distinct concepts.

**Gaps:** No state inference is made from project function names, script text patterns, or unspecified function semantics.

## 7. Claims — `claims`

The following is the complete required claim projection for this profile. Emit applicable effective values only when present in the export or inherited from it.

| Subject | Fields |
|---|---|
| UDT type/instance | `tagType`; `parameters.<name>` for effective scalar parameter values |
| Atomic tag, including expression tag | `dataType`, `valueSource`, `engUnit`, `engLow`, `engHigh`, `scaleMode`, `rawLow`, `rawHigh`, `scaledLow`, `scaledHigh`, `clampMode`, `readOnly`, `historyEnabled`, `tagGroup`, `documentation` |
| Dependency/evaluation configuration | `expression`, `sourceTagPath`, `deriveExpressionGetter`, `deriveExpressionSetter`, `opcServer`, `datasource`, `queryType`, `query` |
| OPC item path | `opcItemPath.raw`; for a parameter-binding object also `opcItemPath.bindType` and successfully resolved `opcItemPath.resolved` |
| Alarm policy | Present `mode`, `priority`, `enabled`, `setpointA`, `setpointB`, `deadband`, `displayPath` |
| Known operation state | `state` |

For an ordinary string OPC item path, `opcItemPath.raw` is the string; omit `.resolved`. For a supported binding object, `.raw` is its `binding` text, `.bindType` is `"parameter"`, and `.resolved` is the substituted string.

For other parameter-bound fields, use `<field>.raw` for binding text, `<field>.bindType` for the binding kind, and `<field>` for the resolved effective value. Expressions whose declared parameter tokens are substituted similarly use `expression.raw` and `expression` when different.

Literal claims directly present on their subject are asserted `observed-in-artifact`. Inherited values, resolved parameters, substituted values, and known states are asserted `derived-by-rule`, with rule references and contributing source locators. In particular, a substituted item path is never claimed as observed.

Do not synthesize absent datatype, value-source, engineering, history, or tag-group claims from general defaults. A missing mandatory discriminator that cannot be inherited makes the affected configuration unsupported. Do not turn `engLow`/`engHigh` alone into a scaling formula.

Memory `value` and `defaultValue` remain in source evidence but are excluded from this claim projection: they do not establish current runtime state. Event script text is retained through payload rather than copied into claims.

**Reason:** A fixed field projection is checkable, and evidence labels distinguish exported values from interpretation.

**Gaps:** Named history providers, tag groups, alarm pipelines, security resources, and other external configurations are not inferred from these claims. Present references to such named resources get `external-required`; behavior-affecting properties outside the supported projection get `unsupported`.

## 8. Coverage — `coverage`

Copy `coverage.capture` from the bundle's declared capture scope. A provider envelope does not prove completeness. Use `unknown` when no scope declaration exists; use `declared-complete` only for an explicit declaration scoped to this provider export.

`artifactsRead` counts distinct pinned artifacts read by the interpreter. `artifactsSkipped` counts bundle artifacts not read. A minimal bundle containing only this export therefore has `artifactsRead: 1`, `artifactsSkipped: 0`. The surrounding specification, scenario, resources, and synthetic examples are not implicitly part of that bundle.

For dev-1, with an export-only bundle and no supplied capture declaration, expect `capture: unknown`.

**Reason:** Complete file traversal does not prove a complete provider capture or complete gateway configuration.

## 9. Additional decisions — `remaining-config`

| Feature | Decision and reason |
|---|---|
| Nested UDTs and type folders | Apply the same scoped expansion and path rules; nesting alone is supported. |
| Enabled/disabled members | Preserve nodes and configured dependencies. A disabled configuration is still present; unsupported enabled bindings get `unsupported`. |
| Expression execution mode, rates, tag groups | Preserve supported tag-group claims; scheduling/deadband details outside the claim projection get `unsupported` when present. They do not imply expression state. |
| Scaling | Record the listed native fields; do not evaluate conversions. Unrecognized scaling properties/modes get `unsupported`. |
| Alarm runtime, acknowledgment, shelving, pipelines, associated data | Basic listed policy fields are supported. Other alarm configuration gets `unsupported`; named pipelines/resources get `external-required`. No live alarm state is asserted. |
| Historian configuration | `historyEnabled` is configuration only. Other history behavior/settings get `unsupported`; named storage resources get `external-required`. An enabled flag alone does not identify a missing resource. |
| Permissions, security zones, audit resources | Preserve original locations; unsupported permission semantics get `unsupported`, named external resources get `external-required`. They do not certify write success. |
| SQL tags | Keep tag and query claims, identify datasource access as above, and mark query behavior unknown. Do not infer tables, returned values, or writes from SQL text. |
| Custom properties, datasets, arrays, document values | Retain in artifact. Unmodeled properties get `unsupported`; do not flatten structured values into invented tags. |
| System tags, remote providers, missing default-provider context | External dependencies remain `external-required`; no gateway browsing. |
| Unknown object/binding forms | Preserve payload/location and report `unsupported`; use unknown state where required. |
| Export bookkeeping | `name`, `tagType`, `tags`, `typeId`, parameter datatype wrappers, event identifiers, and binding wrappers are structural inputs. They do not require additional claims beyond those specified. |
| Unknown properties | Produce `unsupported` at the owning element, except excluded memory values and recognized export bookkeeping. No silent semantic interpretation. |

Unresolved items must identify their node, edge, or artifact location and affected nodes. Keep detailed fields/reasons in the snapshot. The answer-key projection coalesces them by logical owner and code as defined next.