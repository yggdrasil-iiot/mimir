# Answer-key format 1.0.0

One JSON document per export:

- `formatVersion`: `"1.0.0"`.
- `specVersion`: `"1.0.0"`.
- `artifact`: relative export filename.
- `sha256`: lowercase SHA-256 of the exact export bytes.
- `nodes`: array of `{locator, kind, state?, stateKinds?}`.
- `edges`: array of `{from, to, relation, access?, resolution}`.
- `claims`: array of `{locator, field, value}`.
- `unresolved`: array of `{locator, code}`.

All arrays are explicit; no templates, wildcard expansion, generated IDs, or inherited shorthand occur in a key. Node locators are unique. Claim identity is `(locator, field)`. An edge is identified by its complete normalized tuple. Unresolved identity is `(locator, code)`.

`state` is absent when the SPEC omits it. `stateKinds` is present only for stateful nodes. `access` is absent only for `contains`. Values are contract scalars: string, safe integer, boolean, or null.

## Snapshot projection

First validate schema, pins, reference closure, rule references, evidence bases/statuses, and state-claim requirements. Invalid snapshots fail conformance; a favorable category score cannot excuse invalid evidence.

Then:

1. Replace node IDs with the nodes' SPEC logical locators from `nativeId`.
2. Project nodes to the specified fields.
3. Project resolved edges using their target's locator.
4. Project nonresolved edges using canonical `targetText`:
   - Known local missing path: canonical provider-relative locator.
   - External connection: `@external/opc/<name>` or `@external/db/<name>`.
   - Different or unidentified provider: `[<provider-name>]<provider-relative-path>`.
   - Unsupported/dynamic text: `@unparsed/<escaped-original-text>`, escaping `%`, `/`, and `#` as specified.
   - Ambiguous path: canonical requested path; candidate validation remains part of conformance.
5. Project asserted claims in the required field projection.
6. Project unresolved items to their owning element and code. Prefer explicit `nodeId`; otherwise use the edge owner; otherwise use the containing element identified by the artifact locator. Provider-envelope issues use the reserved locator `@export`.
7. Coalesce repeated identical dependency tuples and unresolved owner/code pairs. Multiple occurrences still remain traceable in snapshot evidence.

A connection stub and an external edge to its name are separately scored. A snapshot edge incorrectly marked resolved to that stub does not match an `external-required` edge.

## Normalization

- Preserve exported spelling for display. For matching provider tag paths, compare segments case-insensitively using Unicode simple case folding, without compatibility normalization. Case-colliding elements are invalid rather than merged.
- Apply the same tag-path normalization to path-valued claim fields and local edge endpoints. Preserve external OPC server and datasource names exactly; they are not tag paths.
- Canonicalize escaping before comparison. Do not trim tag names, remove significant spaces, change OPC item paths, or rewrite expression/script/query text.
- Decode JSON escapes before comparing strings. Do not equate strings merely because an expression or SQL parser considers them equivalent.
- Normalize numeric property values from exact decimal input, without binary floating-point rounding: remove insignificant trailing fractional zeros, expand exponent notation, and normalize negative zero to zero. A mathematically integral number within the contract's safe range becomes an integer; other numbers become canonical decimal strings. Thus `1500.0` becomes `1500`, and `1e-1` becomes `"0.1"`.
- Numeric normalization applies only to numeric properties/parameters, not datatype-String values, OPC addresses, documentation, or expression text.
- Sort `stateKinds`; category array order has no meaning. Omitted fields differ from null or empty arrays.

## Comparison and denominator

For each category independently, report:

- `matched`: exact normalized rows present on both sides.
- `missing`: expected rows absent from the snapshot projection.
- `extra`: projected rows absent from the key.
- `expected = matched + missing`.
- Recall: `matched / expected`.
- Precision: `matched / (matched + extra)`.

For an empty denominator, report the ratio as null. A wrong kind, state, access, resolution, or claim value produces one missing expected row and one extra actual row; it receives no partial row credit. Optional field-level diagnostics may explain the difference.

Expected `unsupported`, `behavior-unknown`, `external-required`, missing, and ambiguous rows remain in the denominator. Unsupported content is not excluded from evaluation: the required retained node/dependency/claim and gap declaration are scored wherever specified. An interpreter cannot improve recall by declining a difficult export; refusal supplies no matching rows for an otherwise valid, supported export.

Unresolved scoring deliberately measures owner/code coverage. Detailed field locations, reasons, affected nodes, and multiple distinct gaps under the same owner/code are checked by conformance and may be separately audited; they are not represented as additional rows in this format.