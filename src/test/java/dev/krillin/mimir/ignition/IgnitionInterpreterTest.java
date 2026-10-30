package dev.krillin.mimir.ignition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import dev.krillin.mimir.interpretation.ContractViolation;
import dev.krillin.mimir.interpretation.InterpretationContract;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IgnitionInterpreterTest {

    static final Path DEV1 = Path.of("fixtures/ignition/8.3.10/dev-1");
    static final ObjectMapper M = new ObjectMapper();

    static IgnitionDocuments.Set docs(byte[] export) {
        return IgnitionDocuments.build("test/run", export, "export.json", "2026-10-27T00:00:00Z", "8.3.10");
    }

    static String versionCode(String version) throws Exception {
        byte[] export = Files.readAllBytes(DEV1.resolve("export.json"));
        return assertThrows(ContractViolation.class,
                () -> IgnitionDocuments.build("test/run", export, "export.json", "2026-10-27T00:00:00Z", version)).code();
    }

    @Test
    void anUnstatedVersionIsRefused() throws Exception {
        assertEquals("ignition.version-unknown", versionCode(null));
        assertEquals("ignition.version-unknown", versionCode(" "));
    }

    @Test
    void aVersionOutsideTheProfileIsRefused() throws Exception {
        assertEquals("ignition.version-unsupported", versionCode("8.1.0"));
        assertEquals("ignition.version-unsupported", versionCode("8.3.10 "));
    }

    @Test
    void theStatedVersionIsRecordedAsTheProviderStatedIt() throws Exception {
        IgnitionDocuments.Set d = docs(Files.readAllBytes(DEV1.resolve("export.json")));
        JsonNode version = null;
        for (JsonNode c : d.bundle().get("body").get("productClaims")) {
            if (c.get("subject").asText().equals("version")) version = c;
        }
        assertEquals("8.3.10", version.get("value").asText());
        assertEquals("stated-by-provider", version.get("basis").asText());
        assertEquals(IgnitionDocuments.SUPPORTED_VERSIONS.size(), d.profile().get("body").get("supports").get(0).get("versions").size());
    }

    static JsonNode reparse(JsonNode n) throws Exception {
        return InterpretationContract.parse(M.writeValueAsString(n));
    }

    static Set<String> rows(byte[] export, String cat) {
        return AnswerKey.rows(AnswerKey.project(docs(export).snapshot()).get(cat), cat);
    }

    /** A provider export holding the given tags, written as Ignition writes them. */
    static byte[] export(String tagsJson) {
        return ("{\"name\":\"\",\"tagType\":\"Provider\",\"tags\":" + tagsJson + "}").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void dev1MatchesItsAnswerKeyInEveryCategory() throws Exception {
        byte[] export = Files.readAllBytes(DEV1.resolve("export.json"));
        JsonNode key = M.readTree(Files.readString(DEV1.resolve("key.json")));
        AnswerKey.Result r = AnswerKey.compare(key, AnswerKey.project(docs(export).snapshot()));
        for (String cat : AnswerKey.CATEGORIES) {
            AnswerKey.Score s = r.scores().get(cat);
            assertEquals(s.expected(), s.matched(), cat + " missing " + r.missing().get(cat));
            assertEquals(0, s.extra(), cat + " extra " + r.extra().get(cat));
        }
    }

    @Test
    void producedDocumentsPassTheContract() throws Exception {
        IgnitionDocuments.Set d = docs(Files.readAllBytes(DEV1.resolve("export.json")));
        assertDoesNotThrow(() -> InterpretationContract.checkPinned(reparse(d.snapshot()), reparse(d.bundle()), reparse(d.profile())));
    }

    @Test
    void sameBytesGiveTheSameSnapshotDigest() throws Exception {
        byte[] export = Files.readAllBytes(DEV1.resolve("export.json"));
        assertEquals(docs(export).snapshot().get("digest"), docs(export).snapshot().get("digest"));
    }

    /** The gateway writes siblings in no fixed order (P0); the interpretation must not depend on it. */
    @Test
    void siblingOrderDoesNotChangeTheKey() throws Exception {
        byte[] export = Files.readAllBytes(DEV1.resolve("export.json"));
        JsonNode reversed = M.readTree(export);
        reverseTags(reversed);
        byte[] other = M.writeValueAsBytes(reversed);
        for (String cat : AnswerKey.CATEGORIES) assertEquals(rows(export, cat), rows(other, cat), cat);
    }

    static void reverseTags(JsonNode n) {
        if (n.has("tags") && n.get("tags").isArray()) {
            ArrayNode tags = (ArrayNode) n.get("tags");
            List<JsonNode> items = new ArrayList<>();
            tags.forEach(items::add);
            tags.removeAll();
            for (int i = items.size() - 1; i >= 0; i--) {
                reverseTags(items.get(i));
                tags.add(items.get(i));
            }
        }
    }

    @Test
    void braceInsideAQuotedStringIsNotAReference() {
        Expressions.Parsed p = Expressions.parse("concat(\"{[.]NotATag}\", {[.]Real})");
        assertEquals(List.of("[.]Real"), p.refs());
    }

    @Test
    void expressionStateFollowsTheFunctionsItCalls() {
        assertEquals("stateless", Expressions.state(Expressions.parse("if({[.]A} > 1, now(), 0)")).state());
        assertEquals(List.of("edge-detect", "previous-value"), Expressions.state(Expressions.parse("hasChanged({[.]A})")).kinds());
        assertEquals("unknown", Expressions.state(Expressions.parse("runScript(\"x\", 0)")).state());
        assertEquals("unknown", Expressions.state(Expressions.parse("dateDiff(now(), {[.]T}, \"sec\")")).state());
    }

    @Test
    void pathsResolveRelativeRootAndOtherProvider() {
        byte[] export = export("""
                [{"name":"A","tagType":"Folder","tags":[
                  {"name":"B","tagType":"Folder","tags":[
                    {"name":"X","tagType":"AtomicTag","valueSource":"expr","dataType":"Int4",
                     "expression":"{[.]../Y} + {[~]A/Y} + {[default]A/Y} + {[.]../../../Z}"}]},
                  {"name":"Y","tagType":"AtomicTag","valueSource":"memory","dataType":"Int4"}]}]""");
        Set<String> edges = rows(export, "edges");
        assertTrue(edges.contains("a/b/x | a/y | input | read | resolved"), edges.toString());
        assertTrue(edges.contains("a/b/x | [default]a/y | input | read | external-required"), edges.toString());
        assertTrue(edges.stream().anyMatch(e -> e.startsWith("a/b/x | @unparsed/") && e.endsWith("unsupported")), edges.toString());
    }

    @Test
    void absentTargetIsMissingAndPropertyReferenceBelongsToItsTag() {
        byte[] export = export("""
                [{"name":"T","tagType":"AtomicTag","valueSource":"memory","dataType":"Int4"},
                 {"name":"E","tagType":"AtomicTag","valueSource":"expr","dataType":"Int4","expression":"{[.]T.Quality} + {[.]Gone}"}]""");
        Set<String> edges = rows(export, "edges");
        assertTrue(edges.contains("e | t | input | read | resolved"), edges.toString());
        assertTrue(edges.contains("e | gone | input | read | missing"), edges.toString());
        assertTrue(rows(export, "unresolved").contains("e | missing"));
    }

    @Test
    void undeclaredParameterLeavesTheItemPathUnresolved() {
        byte[] export = export("""
                [{"name":"_types_","tagType":"Folder","tags":[{"name":"M","tagType":"UdtType",
                   "parameters":{"Dev":{"dataType":"String","value":"S"}},
                   "tags":[{"name":"P","tagType":"AtomicTag","valueSource":"opc","dataType":"Float8","opcServer":"U",
                            "opcItemPath":{"bindType":"parameter","binding":"[{Dev}]{Nope}"}}]}]},
                 {"name":"I","tagType":"UdtInstance","typeId":"M"}]""");
        Set<String> claims = rows(export, "claims");
        assertTrue(claims.contains("i/p | opcItemPath.raw | \"[{Dev}]{Nope}\""), claims.toString());
        assertFalse(claims.stream().anyMatch(c -> c.startsWith("i/p | opcItemPath.resolved")), claims.toString());
        assertTrue(rows(export, "unresolved").contains("i/p | missing"));
    }

    @Test
    void instanceOverridesWinAndInheritedMembersAreMaterialized() {
        byte[] export = export("""
                [{"name":"_types_","tagType":"Folder","tags":[{"name":"M","tagType":"UdtType",
                   "tags":[{"name":"S","tagType":"AtomicTag","valueSource":"memory","dataType":"Float8","engHigh":10.0},
                           {"name":"R","tagType":"AtomicTag","valueSource":"memory","dataType":"Boolean"}]}]},
                 {"name":"I","tagType":"UdtInstance","typeId":"M","tags":[{"name":"S","engHigh":12.5}]}]""");
        Set<String> claims = rows(export, "claims");
        assertTrue(claims.contains("i/s | engHigh | \"12.5\""), claims.toString());
        assertTrue(claims.contains("_types_/m/s | engHigh | 10"), claims.toString());
        assertTrue(rows(export, "nodes").contains("i/r | tag | - | -"));
        assertTrue(rows(export, "edges").contains("i | _types_/m | inherits | read | resolved"));
    }

    @Test
    void referenceTagWritesThroughUnlessReadOnly() {
        byte[] export = export("""
                [{"name":"S","tagType":"AtomicTag","valueSource":"memory","dataType":"Int4"},
                 {"name":"R","tagType":"AtomicTag","valueSource":"reference","sourceTagPath":"[.]S"},
                 {"name":"Q","tagType":"AtomicTag","valueSource":"reference","sourceTagPath":"[.]S","readOnly":true}]""");
        Set<String> edges = rows(export, "edges");
        assertTrue(edges.contains("r | s | reference | write | resolved"));
        assertTrue(edges.contains("q | s | reference | read | resolved"));
        assertFalse(edges.contains("q | s | reference | write | resolved"));
    }

    @Test
    void scriptTargetsAreReadStaticallyAndDynamicOnesAreUnsupported() {
        byte[] export = export("""
                [{"name":"A","tagType":"AtomicTag","valueSource":"memory","dataType":"Int4"},
                 {"name":"B","tagType":"AtomicTag","valueSource":"memory","dataType":"Int4","eventScripts":[{"eventid":"valueChanged",
                   "script":"\\tsystem.tag.writeBlocking([\\"[.]A\\"], [1])\\n\\tp = '[.]A'\\n\\tsystem.tag.readBlocking([p])"}]}]""");
        Set<String> edges = rows(export, "edges");
        assertTrue(edges.contains("b#event/valuechanged | a | reference | write | resolved"), edges.toString());
        assertTrue(edges.contains("b#event/valuechanged | b | input | event | resolved"), edges.toString());
        assertTrue(rows(export, "unresolved").contains("b#event/valuechanged | unsupported"));
    }

    @Test
    void duplicateKeysAndCaseCollidingSiblingsAreRefused() {
        assertThrows(ContractViolation.class, () -> IgnitionInterpreter.interpret("export",
                "{\"name\":\"\",\"name\":\"x\",\"tagType\":\"Provider\",\"tags\":[]}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(ContractViolation.class, () -> IgnitionInterpreter.interpret("export", export("""
                [{"name":"T","tagType":"AtomicTag","valueSource":"memory"},{"name":"t","tagType":"AtomicTag","valueSource":"memory"}]""")));
    }

    static String limitCode(byte[] export) {
        return assertThrows(ContractViolation.class, () -> IgnitionInterpreter.interpret("export", export)).code();
    }

    /** Folders nested {@code depth} deep, the innermost holding one memory tag. */
    static byte[] nestedFolders(int depth) {
        String inner = "{\"name\":\"t\",\"tagType\":\"AtomicTag\",\"valueSource\":\"memory\"}";
        for (int i = depth; i > 0; i--) inner = "{\"name\":\"f" + i + "\",\"tagType\":\"Folder\",\"tags\":[" + inner + "]}";
        return export("[" + inner + "]");
    }

    /** A chain of {@code length} UDT types, each inheriting from the previous one. */
    static byte[] typeChain(int length) {
        StringBuilder types = new StringBuilder();
        for (int i = 0; i < length; i++) {
            if (i > 0) types.append(',');
            types.append("{\"name\":\"T").append(i).append("\",\"tagType\":\"UdtType\",\"typeId\":\"")
                    .append(i == 0 ? "" : "T" + (i - 1)).append("\",\"tags\":[]}");
        }
        return export("[{\"name\":\"_types_\",\"tagType\":\"Folder\",\"tags\":[" + types + "]}]");
    }

    @Test
    void profileLimitsAreRefusalsAtTheirBoundary() {
        assertEquals("ignition.limit-exceeded", limitCode(new byte[IgnitionInterpreter.MAX_TOTAL_BYTES + 1]));

        StringBuilder tags = new StringBuilder("[");
        for (int i = 0; i < IgnitionInterpreter.MAX_NODES; i++) {
            if (i > 0) tags.append(',');
            tags.append("{\"name\":\"m").append(i).append("\",\"tagType\":\"AtomicTag\",\"valueSource\":\"memory\"}");
        }
        byte[] atLimit = export(tags + "]");
        assertDoesNotThrow(() -> IgnitionInterpreter.interpret("export", atLimit));
        assertEquals("ignition.limit-exceeded", limitCode(export(tags + ",{\"name\":\"over\",\"tagType\":\"AtomicTag\",\"valueSource\":\"memory\"}]")));

        int depth = IgnitionInterpreter.MAX_REFERENCE_DEPTH;
        assertDoesNotThrow(() -> IgnitionInterpreter.interpret("export", nestedFolders(depth - 1)));
        assertEquals("ignition.limit-exceeded", limitCode(nestedFolders(depth)));
        assertDoesNotThrow(() -> IgnitionInterpreter.interpret("export", typeChain(depth)));
        assertEquals("ignition.limit-exceeded", limitCode(typeChain(depth + 1)));
    }

    @Test
    void decimalsTravelAsCanonicalStrings() {
        assertEquals("0.1", IgnitionInterpreter.scalar(M.getNodeFactory().numberNode(new java.math.BigDecimal("1e-1"))).asText());
        assertEquals(1500L, IgnitionInterpreter.scalar(M.getNodeFactory().numberNode(new java.math.BigDecimal("1500.0"))).asLong());
        assertTrue(IgnitionInterpreter.scalar(M.getNodeFactory().numberNode(new java.math.BigDecimal("1500.0"))).isIntegralNumber());
    }

    // ---- round-1 gaps, fixed against SPEC rules (types named generically by the reviewer) ----

    static final String MOTOR_TYPE = """
            {"name":"_types_","tagType":"Folder","tags":[
              {"name":"Inner","tagType":"UdtType",
               "tags":[{"name":"P","tagType":"AtomicTag","valueSource":"opc","dataType":"Float8",
                        "opcServer":{"bindType":"parameter","binding":"{Srv}"},
                        "opcItemPath":{"bindType":"parameter","binding":"[{Dev}]P"}}],
               "parameters":{"Dev":{"dataType":"String","value":"X"},"Srv":{"dataType":"String","value":"Ignition OPC UA Server"}}},
              {"name":"Outer","tagType":"UdtType","parameters":{"Line":{"dataType":"String","value":"L1"}},
               "tags":[{"name":"In","tagType":"UdtInstance","typeId":"Inner",
                        "parameters":{"Dev":{"dataType":"String","value":{"bindType":"parameter","binding":"{Line}"}},
                                      "Tag":{"dataType":"String","value":{"bindType":"parameter","binding":"{Line}_A"}}}}]}]}""";

    @Test
    void parameterBoundServerAndNestedBindingResolveThroughScopes() {
        byte[] export = export("[" + MOTOR_TYPE + ",{\"name\":\"O\",\"tagType\":\"UdtInstance\",\"typeId\":\"Outer\","
                + "\"parameters\":{\"Line\":{\"dataType\":\"String\",\"value\":\"L7\"}}}]");
        Set<String> edges = rows(export, "edges");
        assertTrue(edges.contains("o/in/p | @external/opc/Ignition OPC UA Server | external | read | external-required"), edges.toString());
        Set<String> claims = rows(export, "claims");
        assertTrue(claims.contains("o/in/p | opcItemPath.resolved | \"[L7]P\""), claims.toString());
        assertTrue(claims.contains("o/in | parameters.Dev | \"L7\""), claims.toString());
        assertTrue(claims.contains("o/in | parameters.Tag | \"L7_A\""), claims.toString());
        assertFalse(rows(export, "unresolved").contains("o/in/p | missing"));
    }

    @Test
    void derivedExpressionsTakeTheirParameters() {
        byte[] export = export("""
                [{"name":"_types_","tagType":"Folder","tags":[{"name":"D","tagType":"UdtType","parameters":{"K":{"dataType":"Integer","value":10}},
                   "tags":[{"name":"S","tagType":"AtomicTag","valueSource":"memory","dataType":"Float8"},
                           {"name":"V","tagType":"AtomicTag","valueSource":"derived","dataType":"Float8","sourceTagPath":"[.]S",
                            "deriveExpressionGetter":"{source} * {K}","deriveExpressionSetter":"{value} / {K}"}]}]}]""");
        Set<String> claims = rows(export, "claims");
        assertTrue(claims.contains("_types_/d/v | deriveExpressionGetter | \"{source} * 10\""), claims.toString());
        assertTrue(claims.contains("_types_/d/v | deriveExpressionSetter.raw | \"{value} / {K}\""), claims.toString());
    }

    @Test
    void dynamicScriptTargetIsAnUnsupportedDependencyOnItsArgumentText() {
        byte[] export = export("""
                [{"name":"B","tagType":"AtomicTag","valueSource":"memory","dataType":"Int4","eventScripts":[{"eventid":"valueChanged",
                   "script":"\\tp = '[.]A'\\n\\tsystem.tag.writeBlocking([p], [1])"}]}]""");
        assertTrue(rows(export, "edges").contains("b#event/valuechanged | @unparsed/[p] | reference | write | unsupported"),
                rows(export, "edges").toString());
    }

    @Test
    void nullParameterIsWithheldAndItsExpressionIsUnknown() {
        byte[] export = export("""
                [{"name":"_types_","tagType":"Folder","tags":[{"name":"N","tagType":"UdtType","parameters":{"Q":{"dataType":"String","value":null}},
                   "tags":[{"name":"E","tagType":"AtomicTag","valueSource":"expr","dataType":"Int4","expression":"len({Q})"}]}]}]""");
        Set<String> claims = rows(export, "claims");
        assertFalse(claims.stream().anyMatch(c -> c.contains("parameters.Q")), claims.toString());
        assertTrue(claims.contains("_types_/n/e | expression.raw | \"len({Q})\""), claims.toString());
        assertFalse(claims.stream().anyMatch(c -> c.startsWith("_types_/n/e | expression |")), claims.toString());
        assertTrue(rows(export, "nodes").contains("_types_/n/e | operation | unknown | -"));
        assertTrue(rows(export, "unresolved").contains("_types_/n | unsupported"));
        assertFalse(rows(export, "edges").stream().anyMatch(e -> e.contains("@unparsed")), rows(export, "edges").toString());
    }

    @Test
    void namedResourcesGetBothCodesAndNonMemoryValueIsUnmodelled() {
        byte[] export = export("""
                [{"name":"H","tagType":"AtomicTag","valueSource":"memory","dataType":"Int4","value":3,"historyProvider":"Hist"},
                 {"name":"W","tagType":"AtomicTag","valueSource":"memory","dataType":"Int4","writePermissions":{"type":"AllOf"}},
                 {"name":"X","tagType":"AtomicTag","valueSource":"expr","dataType":"Int4","expression":"1","value":3}]""");
        Set<String> u = rows(export, "unresolved");
        assertTrue(u.contains("h | external-required") && u.contains("h | unsupported"), u.toString());
        assertTrue(u.contains("w | external-required") && u.contains("w | unsupported"), u.toString());
        assertTrue(u.contains("x | unsupported"), u.toString());
    }

    @Test
    void unresolvableDatasourceBindingIsUnsupportedNotAsserted() {
        byte[] export = export("""
                [{"name":"_types_","tagType":"Folder","tags":[{"name":"Q","tagType":"UdtType",
                   "parameters":{"DS":{"dataType":"String","value":{"bindType":"parameter","binding":"{Up}"}}},
                   "tags":[{"name":"T","tagType":"AtomicTag","valueSource":"db","dataType":"String","queryType":"Select",
                            "datasource":{"bindType":"parameter","binding":"{DS}"},"query":"SELECT 1"}]}]}]""");
        assertTrue(rows(export, "edges").contains("_types_/q/t | @unparsed/{ds} | external | read | unsupported"), rows(export, "edges").toString());
        assertFalse(rows(export, "claims").stream().anyMatch(c -> c.startsWith("_types_/q/t | datasource |")), rows(export, "claims").toString());
    }

    @Test
    void bareUndeclaredTokenAndSubscriptMakeTheExpressionUnknown() {
        byte[] export = export("""
                [{"name":"A","tagType":"AtomicTag","valueSource":"memory","dataType":"Int4"},
                 {"name":"T","tagType":"AtomicTag","valueSource":"expr","dataType":"String","expression":"{InstanceName}"},
                 {"name":"S","tagType":"AtomicTag","valueSource":"expr","dataType":"Int4","expression":"{[.]A}[{[.]A}]"}]""");
        Set<String> n = rows(export, "nodes");
        assertTrue(n.contains("t | operation | unknown | -") && n.contains("s | operation | unknown | -"), n.toString());
        assertTrue(rows(export, "edges").contains("t | @unparsed/{instancename} | input | read | unsupported"), rows(export, "edges").toString());
    }

    // ---- SPEC 1.2.0 amendments (decided 2026-10-27) ----

    @Test
    void bitwiseOperatorsAreStatelessButUnlistedSymbolsAreUnknown() {
        assertEquals("stateless", Expressions.state(Expressions.parse("({[.]W} & 4) != 0 || ({[.]W} >> 2) = 1")).state());
        assertEquals("unknown", Expressions.state(Expressions.parse("{[.]A} ? 1 : 0")).state());
        assertEquals("unknown", Expressions.state(Expressions.parse("{[.]A} @ 2")).state());
        assertEquals("stateless", Expressions.state(Expressions.parse("upper(\"a?b\") = '#'")).state());
    }

    @Test
    void unbracedPathInAnExpressionIsAnUnsupportedReference() {
        byte[] export = export("""
                [{"name":"A","tagType":"AtomicTag","valueSource":"memory","dataType":"Int4"},
                 {"name":"L","tagType":"AtomicTag","valueSource":"expr","dataType":"Int4","expression":"len([.]*/A.Value)"}]""");
        assertTrue(rows(export, "edges").contains("l | @unparsed/[.]*%2fa.value | input | read | unsupported"), rows(export, "edges").toString());
        assertTrue(rows(export, "nodes").contains("l | operation | unknown | -"));
        assertTrue(rows(export, "unresolved").contains("l | unsupported"));
    }

    @Test
    void alarmBindingOutsideTheProjectionKeepsItsDependencyAndIsUnsupported() {
        byte[] export = export("""
                [{"name":"S","tagType":"AtomicTag","valueSource":"memory","dataType":"Int4"},
                 {"name":"T","tagType":"AtomicTag","valueSource":"memory","dataType":"Int4",
                  "alarms":[{"name":"Hi","mode":"AboveValue","Area":{"bindType":"Tag","value":"[.]S"}}]}]""");
        assertTrue(rows(export, "edges").contains("t#alarm/hi | s | input | read | resolved"), rows(export, "edges").toString());
        assertTrue(rows(export, "unresolved").contains("t#alarm/hi | unsupported"), rows(export, "unresolved").toString());
    }

    @Test
    void plainParameterTextTakesTheContainingScopeAndKeepsItsRaw() {
        byte[] export = export("""
                [{"name":"_types_","tagType":"Folder","tags":[{"name":"O","tagType":"UdtType","parameters":{"Area":{"dataType":"String","value":"North"}},
                   "tags":[{"name":"N","tagType":"UdtInstance","parameters":{"Path":{"dataType":"String","value":"{Area}/Pump"}}}]}]}]""");
        Set<String> claims = rows(export, "claims");
        assertTrue(claims.contains("_types_/o/n | parameters.Path | \"North/Pump\""), claims.toString());
        assertTrue(claims.contains("_types_/o/n | parameters.Path.raw | \"{Area}/Pump\""), claims.toString());
    }

    @Test
    void arithmeticInABindingTokenIsUnsupportedNotMissing() {
        byte[] export = export("""
                [{"name":"_types_","tagType":"Folder","tags":[{"name":"M","tagType":"UdtType","parameters":{"Index":{"dataType":"Integer","value":1}},
                   "tags":[{"name":"P","tagType":"AtomicTag","valueSource":"opc","dataType":"Int4","opcServer":"U",
                            "opcItemPath":{"bindType":"parameter","binding":"ns=1;s=D.R{Index + 1}"}}]}]}]""");
        Set<String> u = rows(export, "unresolved");
        assertTrue(u.contains("_types_/m/p | unsupported"), u.toString());
        assertFalse(u.contains("_types_/m/p | missing"), u.toString());
    }

    @Test
    void scoringCountsMissingAndExtraRows() {
        ObjectNode exp = M.createObjectNode();
        exp.putArray("nodes").addObject().put("locator", "A").put("kind", "tag");
        ObjectNode act = M.createObjectNode();
        ArrayNode n = act.putArray("nodes");
        n.addObject().put("locator", "a").put("kind", "tag");
        n.addObject().put("locator", "b").put("kind", "tag");
        AnswerKey.Score s = AnswerKey.compare(exp, act).scores().get("nodes");
        assertEquals(new AnswerKey.Score(1, 1, 0, 1), s);
    }
}
