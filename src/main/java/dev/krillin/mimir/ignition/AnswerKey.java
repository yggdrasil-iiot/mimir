package dev.krillin.mimir.ignition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Answer-key format 1.0.0: projects a snapshot onto id-free rows keyed by logical locator and compares two
 * keys category by category (matched / missing / extra). Normalisation follows KEY-FORMAT.
 */
public final class AnswerKey {

    public static final List<String> CATEGORIES = List.of("nodes", "edges", "claims", "unresolved");
    /** Claim fields whose values are tag paths and compare case-insensitively. */
    static final Set<String> PATH_FIELDS = Set.of("sourceTagPath");
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private AnswerKey() {}

    /** The key a snapshot amounts to. */
    public static ObjectNode project(JsonNode snapshot) {
        JsonNode body = snapshot.get("body");
        Map<String, String> locatorOf = new HashMap<>();
        for (JsonNode n : body.get("nativeNodes")) locatorOf.put(n.get("nodeId").asText(), n.get("nativeId").asText());
        Map<String, String> edgeOwner = new HashMap<>();
        ObjectNode key = F.objectNode();
        key.put("formatVersion", "1.0.0");
        key.put("specVersion", "1.0.0");
        ArrayNode nodes = key.putArray("nodes");
        for (JsonNode n : body.get("nativeNodes")) {
            ObjectNode r = nodes.addObject();
            r.put("locator", n.get("nativeId").asText());
            r.put("kind", n.get("kind").asText());
            if (n.has("state")) r.put("state", n.get("state").asText());
            if (n.has("stateKinds")) r.set("stateKinds", n.get("stateKinds"));
        }
        ArrayNode edges = key.putArray("edges");
        for (JsonNode e : body.get("referenceEdges")) {
            String from = locatorOf.get(e.get("owner").get("nodeId").asText());
            edgeOwner.put(e.get("edgeId").asText(), from);
            ObjectNode r = edges.addObject();
            r.put("from", from);
            r.put("to", e.has("targetNodeId") ? locatorOf.get(e.get("targetNodeId").asText()) : e.get("targetText").asText());
            r.put("relation", e.get("relation").asText());
            if (e.has("access")) r.put("access", e.get("access").asText());
            r.put("resolution", e.get("resolution").asText());
        }
        ArrayNode claims = key.putArray("claims");
        for (JsonNode c : body.get("claims")) {
            if (!c.get("status").asText().equals("asserted")) continue;
            ObjectNode r = claims.addObject();
            r.put("locator", locatorOf.get(c.get("subject").get("nodeId").asText()));
            r.put("field", c.get("subject").get("field").asText());
            r.set("value", c.get("value"));
        }
        ArrayNode unresolved = key.putArray("unresolved");
        for (JsonNode u : body.get("unresolved")) {
            String owner = u.has("nodeId") ? locatorOf.get(u.get("nodeId").asText())
                    : u.has("edgeId") ? edgeOwner.get(u.get("edgeId").asText()) : "@export";
            ObjectNode r = unresolved.addObject();
            r.put("locator", owner);
            r.put("code", u.get("code").asText());
        }
        return key;
    }

    public record Score(int expected, int matched, int missing, int extra) {
        public Double recall() {
            return expected == 0 ? null : (double) matched / expected;
        }

        public Double precision() {
            return matched + extra == 0 ? null : (double) matched / (matched + extra);
        }
    }

    public record Result(Map<String, Score> scores, Map<String, List<String>> missing, Map<String, List<String>> extra) {}

    /** Compares an actual key (from a snapshot) with the expected one. Rows are sets; duplicates coalesce. */
    public static Result compare(JsonNode expected, JsonNode actual) {
        Map<String, Score> scores = new LinkedHashMap<>();
        Map<String, List<String>> missing = new LinkedHashMap<>();
        Map<String, List<String>> extra = new LinkedHashMap<>();
        for (String cat : CATEGORIES) {
            Set<String> exp = rows(expected.path(cat), cat);
            Set<String> act = rows(actual.path(cat), cat);
            List<String> miss = new ArrayList<>(new TreeSet<>(exp));
            miss.removeAll(act);
            List<String> ext = new ArrayList<>(new TreeSet<>(act));
            ext.removeAll(exp);
            int matched = exp.size() - miss.size();
            scores.put(cat, new Score(exp.size(), matched, miss.size(), ext.size()));
            missing.put(cat, miss);
            extra.put(cat, ext);
        }
        return new Result(scores, missing, extra);
    }

    static Set<String> rows(JsonNode array, String cat) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode r : array) {
            out.add(switch (cat) {
                case "nodes" -> String.join(" | ", path(r.path("locator").asText()), r.path("kind").asText(),
                        r.path("state").asText("-"), kinds(r.path("stateKinds")));
                case "edges" -> String.join(" | ", path(r.path("from").asText()), endpoint(r.path("to").asText()),
                        r.path("relation").asText(), r.path("access").asText("-"), r.path("resolution").asText());
                case "claims" -> {
                    String field = r.path("field").asText();
                    JsonNode v = IgnitionInterpreter.scalar(r.path("value"));
                    String value = v.isTextual() && PATH_FIELDS.contains(field) ? path(v.asText()) : v.toString();
                    yield String.join(" | ", path(r.path("locator").asText()), field, value);
                }
                default -> String.join(" | ", path(r.path("locator").asText()), r.path("code").asText());
            });
        }
        return out;
    }

    private static String path(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    /** External connection names compare exactly; tag paths and provider-qualified paths fold case. */
    private static String endpoint(String s) {
        return s.startsWith("@external/") ? s : path(s);
    }

    private static String kinds(JsonNode k) {
        TreeSet<String> s = new TreeSet<>();
        k.forEach(x -> s.add(x.asText()));
        return s.isEmpty() ? "-" : String.join(",", s);
    }
}
