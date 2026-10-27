package dev.krillin.mimir.interpretation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InterpretationContractTest {

    static final Path ROOT = Path.of("fixtures/interpretation");

    static JsonNode load(String version, String base, String file) throws Exception {
        return InterpretationContract.parse(Files.readString(ROOT.resolve(version).resolve("valid").resolve(base).resolve(file)));
    }

    @ParameterizedTest
    @CsvSource({"1.0.0,pio-like", "1.0.0,kepware-like", "1.1.0,pio-like", "1.1.0,kepware-like"})
    void validSnapshotPassesAgainstItsBundleAndProfile(String version, String base) throws Exception {
        assertDoesNotThrow(() -> InterpretationContract.checkPinned(
                load(version, base, "interpretation-snapshot.json"),
                load(version, base, "source-bundle.json"),
                load(version, base, "interpretation-profile.json")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.0.0", "1.1.0"})
    void validRequestAndResultPass(String version) throws Exception {
        JsonNode bundle = load(version, "pio-like", "source-bundle.json");
        JsonNode profile = load(version, "pio-like", "interpretation-profile.json");
        JsonNode request = load(version, "pio-like", "interpret-request.json");
        assertDoesNotThrow(() -> InterpretationContract.checkRequest(request, bundle, profile));
        assertDoesNotThrow(() -> InterpretationContract.checkResult(
                load(version, "pio-like", "interpret-result.json"), request, load(version, "pio-like", "interpretation-snapshot.json")));
    }

    /** The fixture digests were stamped by scripts/interpretation_digest.py; this is the second implementation agreeing. */
    @ParameterizedTest
    @CsvSource({"1.0.0,pio-like", "1.0.0,kepware-like", "1.1.0,pio-like", "1.1.0,kepware-like"})
    void javaDigestEqualsThePythonStampedDigest(String version, String base) throws Exception {
        for (String file : List.of("source-bundle.json", "interpretation-profile.json", "interpretation-snapshot.json")) {
            JsonNode doc = load(version, base, file);
            assertEquals(doc.get("digest").asText(), InterpretationDigest.compute(doc), base + "/" + file);
        }
    }

    @ParameterizedTest
    @CsvSource({"1.0.0,pio-like", "1.0.0,kepware-like", "1.1.0,pio-like", "1.1.0,kepware-like"})
    void bundleHashesAreTheBytesOnDisk(String version, String base) throws Exception {
        for (JsonNode a : load(version, base, "source-bundle.json").get("body").get("artifacts")) {
            byte[] bytes = Files.readAllBytes(ROOT.resolve(version).resolve("valid").resolve(base).resolve(a.get("path").asText()));
            assertEquals(a.get("sha256").asText(), InterpretationDigest.sha256(bytes), a.get("artifactId").asText());
            assertEquals(a.get("sizeBytes").asLong(), bytes.length, a.get("artifactId").asText());
        }
    }

    @TestFactory
    List<DynamicTest> invalidCases() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        int expected = 0;
        for (String version : List.of("1.0.0", "1.1.0")) {
            JsonNode cases = InterpretationContract.parse(
                    Files.readString(ROOT.resolve(version).resolve("invalid/cases.json"))).get("cases");
            expected += cases.size();
            for (JsonNode c : cases) {
                tests.add(DynamicTest.dynamicTest(version + " " + c.get("name").asText(), () -> runCase(version, c)));
            }
        }
        assertEquals(expected, tests.size());
        return tests;
    }

    private static void runCase(String version, JsonNode c) throws Exception {
        String base = c.get("base").asText();
        ObjectNode doc = (ObjectNode) load(version, base, c.get("file").asText());
        for (JsonNode edit : c.get("edits")) {
            apply(doc, edit);
        }
        if (c.get("restamp").asBoolean()) {
            doc.set("digest", TextNode.valueOf(InterpretationDigest.compute(doc)));
        }
        JsonNode companions = c.path("companions");
        String mode = c.get("mode").asText();
        JsonNode first = switch (mode) {
            case "pinned", "request" -> companion(companions, "bundle", version, base, "source-bundle.json");
            case "result" -> companion(companions, "request", version, base, "interpret-request.json");
            default -> null;
        };
        JsonNode second = switch (mode) {
            case "pinned", "request" -> companion(companions, "profile", version, base, "interpretation-profile.json");
            case "result" -> companion(companions, "snapshot", version, base, "interpretation-snapshot.json");
            default -> null;
        };
        Runnable check = switch (mode) {
            case "single" -> () -> InterpretationContract.check(doc);
            case "pinned" -> () -> InterpretationContract.checkPinned(doc, first, second);
            case "request" -> () -> InterpretationContract.checkRequest(doc, first, second);
            case "result" -> () -> InterpretationContract.checkResult(doc, first, second);
            default -> throw new IllegalArgumentException(mode);
        };
        if (c.get("expect").isNull()) {
            assertDoesNotThrow(check::run);
        } else {
            ContractViolation v = assertThrows(ContractViolation.class, check::run);
            assertEquals(c.get("expect").asText(), v.code(), v.getMessage());
        }
    }

    /** The document a case is checked against: the same version and base by default, or another one and/or edited. */
    private static JsonNode companion(JsonNode companions, String role, String version, String base, String file) throws Exception {
        JsonNode spec = companions.path(role);
        if (spec.isMissingNode()) {
            return load(version, base, file);
        }
        ObjectNode doc = (ObjectNode) load(spec.path("version").asText(version), spec.path("base").asText(base), file);
        for (JsonNode edit : spec.path("edits")) {
            apply(doc, edit);
        }
        if (spec.path("restamp").asBoolean(false)) {
            doc.set("digest", TextNode.valueOf(InterpretationDigest.compute(doc)));
        }
        return doc;
    }

    /** JSON-pointer set/remove; enough for the cases file, not a general JSON Patch. */
    private static void apply(ObjectNode doc, JsonNode edit) {
        String path = edit.get("path").asText();
        int cut = path.lastIndexOf('/');
        JsonNode parent = cut == 0 ? doc : doc.at(path.substring(0, cut));
        String last = path.substring(cut + 1);
        boolean set = edit.get("op").asText().equals("set");
        if (parent instanceof ObjectNode o) {
            if (set) o.set(last, edit.get("value").deepCopy());
            else if (o.remove(last) == null) throw new IllegalStateException("nothing at " + path);
        } else if (parent instanceof ArrayNode a) {
            int i = Integer.parseInt(last);
            if (set) a.set(i, edit.get("value").deepCopy());
            else a.remove(i);
        } else {
            throw new IllegalStateException("no container at " + path);
        }
    }
}
