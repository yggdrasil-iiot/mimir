package dev.krillin.mimir.interpretation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Checks interpretation-contract documents (1.0.0): schema, digest, and the cross-references a schema
 * cannot express. It checks that a snapshot is well-formed and pinned to exact inputs; it cannot check
 * that the interpretation is right — that would take a second parser, which the single-reader decision
 * rules out.
 */
public final class InterpretationContract {

    public static final String VERSION = "1.0.0";
    private static final String ID_PREFIX = "urn:mimir:interpretation:1.0.0:";
    private static final Set<String> DIGESTED = Set.of("source-bundle", "interpretation-profile", "interpretation-snapshot");
    private static final Set<String> KINDS = Set.of("source-bundle", "interpretation-profile", "interpretation-snapshot",
            "interpret-request", "interpret-result");
    private static final Set<String> CONSUMER_EVIDENCE = Set.of("declared-by-reviewer", "verified-by-virtual-case");

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
            b -> b.schemaMappers(m -> m.mapPrefix(ID_PREFIX, "classpath:contracts/interpretation/1.0.0/")));
    private static final Map<String, JsonSchema> SCHEMAS = new ConcurrentHashMap<>();

    private InterpretationContract() {}

    public static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            String message = e.getOriginalMessage();
            boolean duplicate = message != null && message.startsWith("Duplicate field");
            throw new ContractViolation(duplicate ? "json.duplicate-key" : "json.invalid", message);
        }
    }

    /** Checks one document on its own: kind, version, number rules, schema, digest, internal references. */
    public static void check(JsonNode doc) {
        if (!doc.isObject()) throw new ContractViolation("contract.not-an-object", "");
        String kind = doc.path("kind").asText("");
        if (!KINDS.contains(kind)) throw new ContractViolation("contract.kind-unknown", kind);
        if (!VERSION.equals(doc.path("contractVersion").asText(""))) {
            throw new ContractViolation("contract.version-unsupported", doc.path("contractVersion").toString());
        }
        Jcs.canonicalize(doc);
        if (kind.equals("interpretation-snapshot")) {
            for (JsonNode claim : doc.path("body").path("claims")) {
                if (CONSUMER_EVIDENCE.contains(claim.path("evidenceBasis").asText())) {
                    throw new ContractViolation("snapshot.evidence-basis-not-owned", claim.path("claimId").asText());
                }
            }
        }
        Set<ValidationMessage> errors = schema(kind).validate(doc);
        if (!errors.isEmpty()) {
            throw new ContractViolation("contract.schema", errors.iterator().next().getMessage());
        }
        if (DIGESTED.contains(kind) && !InterpretationDigest.compute(doc).equals(doc.get("digest").asText())) {
            throw new ContractViolation("digest.mismatch", kind + ":" + doc.get("id").asText());
        }
        switch (kind) {
            case "source-bundle" -> checkBundle(doc.get("body"));
            case "interpretation-snapshot" -> checkSnapshot(doc.get("body"));
            default -> { }
        }
    }

    /** Checks a snapshot against the bundle and profile it names. Each document is checked on its own first. */
    public static void checkPinned(JsonNode snapshot, JsonNode bundle, JsonNode profile) {
        check(snapshot);
        check(bundle);
        check(profile);
        JsonNode body = snapshot.get("body");
        requireRef(body.get("bundleRef"), bundle, "pin.bundle-mismatch");
        requireRef(body.get("profileRef"), profile, "pin.profile-mismatch");

        Map<String, String> artifacts = new HashMap<>();
        for (JsonNode a : bundle.get("body").get("artifacts")) {
            artifacts.put(a.get("artifactId").asText(), a.get("sha256").asText());
        }
        for (JsonNode pin : body.get("inputManifest")) {
            String id = pin.get("artifactId").asText();
            if (!artifacts.containsKey(id)) throw new ContractViolation("pin.artifact-unknown", id);
            if (!artifacts.get(id).equals(pin.get("sha256").asText())) throw new ContractViolation("pin.artifact-mismatch", id);
        }
        JsonNode coverage = body.get("coverage");
        if (coverage.get("artifactsRead").asLong() + coverage.get("artifactsSkipped").asLong() > artifacts.size()) {
            throw new ContractViolation("snapshot.coverage-mismatch", "more artifacts counted than the bundle holds");
        }

        Set<String> rules = new HashSet<>();
        for (JsonNode r : profile.get("body").get("rules")) {
            rules.add(r.get("ruleId").asText() + "@" + r.get("version").asText());
        }
        for (JsonNode claim : body.get("claims")) {
            JsonNode rule = claim.get("rule");
            if (rule != null && !rules.contains(rule.get("ruleId").asText() + "@" + rule.get("version").asText())) {
                throw new ContractViolation("pin.rule-unknown", claim.get("claimId").asText());
            }
        }
    }

    /** Checks a request or result against the documents it refers to. */
    public static void checkRequest(JsonNode request, JsonNode bundle, JsonNode profile) {
        check(request);
        check(bundle);
        check(profile);
        requireRef(request.get("bundleRef"), bundle, "pin.bundle-mismatch");
        requireRef(request.get("profileRef"), profile, "pin.profile-mismatch");
        Map<String, String> artifacts = new HashMap<>();
        for (JsonNode a : bundle.get("body").get("artifacts")) {
            artifacts.put(a.get("artifactId").asText(), a.get("sha256").asText());
        }
        for (JsonNode pin : request.get("artifactPins")) {
            String id = pin.get("artifactId").asText();
            if (!artifacts.containsKey(id)) throw new ContractViolation("pin.artifact-unknown", id);
            if (!artifacts.get(id).equals(pin.get("sha256").asText())) throw new ContractViolation("pin.artifact-mismatch", id);
        }
    }

    /** Checks a result against its request and, when accepted, that the snapshot came from exactly what was requested. */
    public static void checkResult(JsonNode result, JsonNode request, JsonNode snapshot) {
        check(result);
        check(request);
        if (!result.get("requestId").asText().equals(request.get("requestId").asText())) {
            throw new ContractViolation("result.request-mismatch", result.get("requestId").asText());
        }
        if (!result.has("snapshotRef")) {
            return;
        }
        check(snapshot);
        requireRef(result.get("snapshotRef"), snapshot, "pin.snapshot-mismatch");
        JsonNode body = snapshot.get("body");
        if (!body.get("bundleRef").equals(request.get("bundleRef"))) {
            throw new ContractViolation("pin.bundle-mismatch", "snapshot and request name different bundles");
        }
        if (!body.get("profileRef").equals(request.get("profileRef"))) {
            throw new ContractViolation("pin.profile-mismatch", "snapshot and request name different profiles");
        }
        Map<String, String> requested = new HashMap<>();
        for (JsonNode pin : request.get("artifactPins")) {
            requested.put(pin.get("artifactId").asText(), pin.get("sha256").asText());
        }
        for (JsonNode pin : body.get("inputManifest")) {
            String id = pin.get("artifactId").asText();
            if (!requested.containsKey(id)) throw new ContractViolation("pin.artifact-unknown", "not requested: " + id);
            if (!requested.get(id).equals(pin.get("sha256").asText())) throw new ContractViolation("pin.artifact-mismatch", id);
        }
    }

    private static void requireRef(JsonNode ref, JsonNode doc, String code) {
        boolean same = ref.get("kind").asText().equals(doc.get("kind").asText())
                && ref.get("id").asText().equals(doc.get("id").asText())
                && ref.get("version").asText().equals(doc.get("version").asText())
                && ref.get("digest").asText().equals(doc.get("digest").asText());
        if (!same) throw new ContractViolation(code, ref.get("id").asText());
    }

    private static void checkBundle(JsonNode body) {
        Set<String> ids = new HashSet<>();
        for (JsonNode a : body.get("artifacts")) {
            if (!ids.add(a.get("artifactId").asText())) {
                throw new ContractViolation("bundle.artifact-duplicate", a.get("artifactId").asText());
            }
        }
        for (JsonNode a : body.get("artifacts")) {
            JsonNode redaction = a.get("redaction");
            if (redaction != null) {
                String from = redaction.get("derivedFrom").asText();
                if (!ids.contains(from) || from.equals(a.get("artifactId").asText())) {
                    throw new ContractViolation("bundle.redaction-source-missing", a.get("artifactId").asText());
                }
            }
        }
        for (JsonNode c : body.get("productClaims")) {
            for (JsonNode id : c.path("artifactIds")) {
                if (!ids.contains(id.asText())) throw new ContractViolation("bundle.claim-artifact-missing", id.asText());
            }
        }
    }

    private static void checkSnapshot(JsonNode body) {
        Set<String> pinned = new HashSet<>();
        for (JsonNode p : body.get("inputManifest")) {
            if (!pinned.add(p.get("artifactId").asText())) {
                throw new ContractViolation("snapshot.id-duplicate", "inputManifest:" + p.get("artifactId").asText());
            }
        }
        if (body.get("coverage").get("artifactsRead").asLong() != pinned.size()) {
            throw new ContractViolation("snapshot.coverage-mismatch", "artifactsRead differs from inputManifest");
        }
        Set<String> nodes = unique(body.get("nativeNodes"), "nodeId");
        Set<String> edges = unique(body.get("referenceEdges"), "edgeId");
        Set<String> claims = unique(body.get("claims"), "claimId");
        unique(body.get("unresolved"), "issueId");

        for (JsonNode n : body.get("nativeNodes")) {
            locator(n.get("locator"), pinned);
            if (n.has("payload")) locator(n.get("payload"), pinned);
        }
        for (JsonNode e : body.get("referenceEdges")) {
            node(e.get("owner").get("nodeId"), nodes);
            if (e.has("targetNodeId")) node(e.get("targetNodeId"), nodes);
            for (JsonNode c : e.path("candidates")) node(c, nodes);
        }
        for (JsonNode c : body.get("claims")) {
            node(c.get("subject").get("nodeId"), nodes);
            for (JsonNode l : c.get("locators")) locator(l, pinned);
            for (JsonNode d : c.path("dependsOn").path("claimIds")) {
                if (!claims.contains(d.asText())) throw new ContractViolation("snapshot.claim-missing", d.asText());
            }
            for (JsonNode d : c.path("dependsOn").path("edgeIds")) {
                if (!edges.contains(d.asText())) throw new ContractViolation("snapshot.edge-missing", d.asText());
            }
        }
        for (JsonNode u : body.get("unresolved")) {
            if (u.has("nodeId")) node(u.get("nodeId"), nodes);
            if (u.has("edgeId") && !edges.contains(u.get("edgeId").asText())) {
                throw new ContractViolation("snapshot.edge-missing", u.get("edgeId").asText());
            }
            if (u.has("locator")) locator(u.get("locator"), pinned);
            for (JsonNode a : u.get("affectedNodeIds")) node(a, nodes);
        }
        for (JsonNode d : body.get("diagnostics")) {
            if (d.has("locator")) locator(d.get("locator"), pinned);
        }
    }

    private static Set<String> unique(JsonNode items, String field) {
        Set<String> seen = new HashSet<>();
        for (JsonNode item : items) {
            if (!seen.add(item.get(field).asText())) {
                throw new ContractViolation("snapshot.id-duplicate", field + ":" + item.get(field).asText());
            }
        }
        return seen;
    }

    private static void node(JsonNode id, Set<String> nodes) {
        if (!nodes.contains(id.asText())) throw new ContractViolation("snapshot.node-missing", id.asText());
    }

    private static void locator(JsonNode locator, Set<String> pinned) {
        if (!pinned.contains(locator.get("artifactId").asText())) {
            throw new ContractViolation("snapshot.locator-artifact-unpinned", locator.get("artifactId").asText());
        }
    }

    private static JsonSchema schema(String kind) {
        return SCHEMAS.computeIfAbsent(kind,
                k -> FACTORY.getSchema(SchemaLocation.of(ID_PREFIX + k + ".schema.json")));
    }
}
