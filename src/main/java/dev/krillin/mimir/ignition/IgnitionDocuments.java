package dev.krillin.mimir.ignition;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.krillin.mimir.interpretation.InterpretationDigest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Wraps one interpreted Ignition export into the three pinned 1.1.0 documents: source bundle, interpretation
 * profile and interpretation snapshot, each with its digest.
 */
public final class IgnitionDocuments {

    public static final String ARTIFACT_ID = "export";
    public static final String INTERPRETER_VERSION = "0.3.0";
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    public record Set(ObjectNode bundle, ObjectNode profile, ObjectNode snapshot) {}

    private IgnitionDocuments() {}

    /**
     * @param name         lower-case id stem for the three documents, e.g. {@code lab/dev-1}
     * @param exportPath   the export's path inside the bundle, relative
     * @param obtainedAt   when the export was taken, RFC 3339 UTC
     */
    public static Set build(String name, byte[] export, String exportPath, String obtainedAt) {
        ObjectNode bundle = doc("source-bundle", name + "/bundle");
        ObjectNode bb = bundle.putObject("body");
        bb.put("origin", "synthetic");
        bb.put("captureScope", "unknown");
        ObjectNode artifact = bb.putArray("artifacts").addObject();
        artifact.put("artifactId", ARTIFACT_ID);
        artifact.put("path", exportPath);
        artifact.put("sha256", InterpretationDigest.sha256(export));
        artifact.put("sizeBytes", export.length);
        artifact.put("role", "configuration");
        ObjectNode obtained = artifact.putObject("obtained");
        obtained.put("at", obtainedAt);
        obtained.put("method", "export");
        ArrayNode product = bb.putArray("productClaims");
        product.addObject().put("subject", "product").put("value", "ignition").put("basis", "stated-by-provider");
        product.addObject().put("subject", "version").put("value", "8.3.10").put("basis", "stated-by-provider");
        product.addObject().put("subject", "format").put("value", "tags/export json").put("basis", "stated-by-provider");
        seal(bundle);

        ObjectNode profile = doc("interpretation-profile", "ignition83/profile");
        ObjectNode pb = profile.putObject("body");
        ObjectNode interp = pb.putObject("interpreter");
        interp.put("name", "mimir-ignition83");
        interp.put("version", INTERPRETER_VERSION);
        interp.put("implementationDigest", implementationDigest());
        ObjectNode supports = pb.putArray("supports").addObject();
        supports.put("product", "ignition");
        supports.putArray("versions").add("8.3.10");
        supports.putArray("inputFormats").add("tags/export json");
        pb.put("unknownVersionPolicy", "reject");
        ArrayNode rules = pb.putArray("rules");
        for (String r : IgnitionInterpreter.RULES) {
            rules.addObject().put("ruleId", IgnitionInterpreter.NAMESPACE + "." + r).put("version", IgnitionInterpreter.RULE_VERSION);
        }
        ObjectNode limits = pb.putObject("limits");
        limits.put("maxArtifacts", 1);
        limits.put("maxTotalBytes", 16 * 1024 * 1024);
        limits.put("maxReferenceDepth", 64);
        limits.put("maxNodes", 100000);
        ArrayNode gaps = pb.putArray("knownGaps");
        gaps.add("expressions, scripts and SQL are read, never run; functions outside the profile leave state unknown");
        gaps.add("an unqualified script path or another provider's path is left external-required");
        gaps.add("history, alarm pipelines, tag groups and other gateway resources are named, not read");
        seal(profile);

        ObjectNode parts = IgnitionInterpreter.interpret(ARTIFACT_ID, export);
        ObjectNode snapshot = doc("interpretation-snapshot", name + "/snapshot");
        ObjectNode sb = snapshot.putObject("body");
        sb.set("bundleRef", ref(bundle));
        sb.set("profileRef", ref(profile));
        sb.putArray("inputManifest").addObject().put("artifactId", ARTIFACT_ID).put("sha256", InterpretationDigest.sha256(export));
        sb.putArray("context");
        sb.set("nativeNodes", parts.get("nativeNodes"));
        sb.set("referenceEdges", parts.get("referenceEdges"));
        sb.set("claims", parts.get("claims"));
        sb.set("unresolved", parts.get("unresolved"));
        ObjectNode coverage = sb.putObject("coverage");
        coverage.put("capture", "unknown");
        coverage.put("artifactsRead", 1);
        coverage.put("artifactsSkipped", 0);
        sb.putArray("diagnostics");
        seal(snapshot);
        return new Set(bundle, profile, snapshot);
    }

    private static ObjectNode doc(String kind, String id) {
        ObjectNode d = F.objectNode();
        d.put("kind", kind);
        d.put("contractVersion", "1.1.0");
        d.put("id", id);
        d.put("version", "1.0.0");
        return d;
    }

    private static void seal(ObjectNode d) {
        d.put("digest", InterpretationDigest.compute(d));
    }

    private static ObjectNode ref(ObjectNode d) {
        ObjectNode r = F.objectNode();
        for (String k : new String[] {"kind", "id", "version", "digest"}) r.set(k, d.get(k));
        return r;
    }

    /** SHA-256 of the interpreter's compiled class, so a changed interpreter carries a changed profile. */
    private static String implementationDigest() {
        try (InputStream in = IgnitionInterpreter.class.getResourceAsStream("IgnitionInterpreter.class")) {
            return InterpretationDigest.sha256(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
