package dev.krillin.mimir.ignition;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.krillin.mimir.interpretation.ContractViolation;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads one Ignition 8.3 tag export (REST {@code tags/export}, JSON) into the body of a 1.1.0 interpretation
 * snapshot, following the mapping specification {@code ignition83} 1.0.0. Section numbers (§) refer to that
 * specification. The interpreter reads configuration only: it runs no expression, script or query.
 */
public final class IgnitionInterpreter {

    public static final String NAMESPACE = "ignition83";
    public static final String RULE_VERSION = "1.2.0";
    public static final List<String> RULES = List.of("identity", "node-kind", "effective-config", "dependencies",
            "references", "expression-state", "claims", "coverage", "remaining-config");

    static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    /** §7: the claim projection, in the order the claims are written. */
    static final List<String> ATOMIC_FIELDS = List.of("dataType", "valueSource", "engUnit", "engLow", "engHigh",
            "scaleMode", "rawLow", "rawHigh", "scaledLow", "scaledHigh", "clampMode", "readOnly", "historyEnabled",
            "tagGroup", "documentation", "expression", "sourceTagPath", "deriveExpressionGetter",
            "deriveExpressionSetter", "opcServer", "opcItemPath", "datasource", "queryType", "query");
    static final List<String> ALARM_FIELDS = List.of("mode", "priority", "enabled", "setpointA", "setpointB",
            "deadband", "displayPath");
    /** §9: bookkeeping and excluded memory values; anything else outside the projection is unsupported. */
    static final Set<String> ATOMIC_STRUCTURAL = Set.of("name", "tagType", "tags", "value", "defaultValue",
            "eventScripts", "alarms", "typeId", "parameters");
    static final Set<String> ATOMIC_STRUCTURAL_NOT_MEMORY = Set.of("name", "tagType", "tags", "eventScripts", "alarms",
            "typeId", "parameters");
    static final Set<String> GROUP_STRUCTURAL = Set.of("name", "tagType", "tags", "typeId", "parameters");
    /** §7 gaps: properties that name a resource kept elsewhere on the gateway. */
    static final Set<String> NAMED_RESOURCES = Set.of("tagGroup", "historyProvider");
    static final Set<String> ALARM_NAMED_RESOURCES = Set.of("activePipeline", "clearPipeline", "ackPipeline");
    static final Set<String> VALUE_SOURCES = Set.of("memory", "opc", "reference", "derived", "db", "expr");
    static final Pattern SCRIPT_TAG_CALL = Pattern.compile(
            "system\\.tag\\.(read|readBlocking|readAsync|write|writeBlocking|writeAsync|writeSynchronous|writeAll)\\s*\\(");
    static final Set<String> SCRIPT_WRITES = Set.of("write", "writeblocking", "writeasync", "writesynchronous", "writeall");

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    // ---- effective configuration (§3) ------------------------------------------------------------------------

    /** A property value and where it came from; {@code own} when it is written on this element's own object. */
    record Prop(JsonNode value, String pointer, boolean own) {}

    /** The effective view of one element after inheritance and overrides. */
    static final class Cfg {
        String name;
        String tagType;
        String pointer;            // the element's own exported object, or the member object that defined it
        boolean exported;          // the element has an object of its own in the export
        String typeId;
        final LinkedHashMap<String, Prop> props = new LinkedHashMap<>();
        final LinkedHashMap<String, Prop> params = new LinkedHashMap<>();       // key: lower-case name
        final LinkedHashMap<String, String> paramNames = new LinkedHashMap<>(); // lower-case -> written name
        final LinkedHashMap<String, Prop> alarms = new LinkedHashMap<>();       // key: lower-case alarm name
        final LinkedHashMap<String, Prop> events = new LinkedHashMap<>();       // key: eventid
        final LinkedHashMap<String, Cfg> members = new LinkedHashMap<>();       // key: lower-case member name

        Cfg copyInherited() {
            Cfg c = new Cfg();
            c.name = name;
            c.tagType = tagType;
            c.pointer = pointer;
            c.exported = false;
            c.typeId = typeId;
            props.forEach((k, v) -> c.props.put(k, new Prop(v.value(), v.pointer(), false)));
            params.forEach((k, v) -> c.params.put(k, new Prop(v.value(), v.pointer(), false)));
            c.paramNames.putAll(paramNames);
            alarms.forEach((k, v) -> c.alarms.put(k, new Prop(v.value(), v.pointer(), false)));
            events.forEach((k, v) -> c.events.put(k, new Prop(v.value(), v.pointer(), false)));
            members.forEach((k, v) -> c.members.put(k, v.copyInherited()));
            return c;
        }
    }

    static Cfg fromJson(JsonNode obj, String pointer) {
        Cfg c = new Cfg();
        c.name = obj.path("name").asText("");
        c.tagType = obj.path("tagType").asText("");
        c.pointer = pointer;
        c.exported = true;
        if (obj.has("typeId")) c.typeId = obj.get("typeId").asText();
        obj.fields().forEachRemaining(e -> {
            String k = e.getKey();
            String p = pointer + "/" + escapePointer(k);
            switch (k) {
                case "name", "tagType", "tags", "typeId" -> { }
                case "parameters" -> e.getValue().fields().forEachRemaining(pe -> {
                    String lower = pe.getKey().toLowerCase(Locale.ROOT);
                    c.params.put(lower, new Prop(pe.getValue(), p + "/" + escapePointer(pe.getKey()), true));
                    c.paramNames.put(lower, pe.getKey());
                });
                case "alarms" -> {
                    int i = 0;
                    for (JsonNode a : e.getValue()) {
                        c.alarms.put(a.path("name").asText("").toLowerCase(Locale.ROOT), new Prop(a, p + "/" + i, true));
                        i++;
                    }
                }
                case "eventScripts" -> {
                    int i = 0;
                    for (JsonNode s : e.getValue()) {
                        c.events.put(s.path("eventid").asText(""), new Prop(s, p + "/" + i, true));
                        i++;
                    }
                }
                default -> c.props.put(k, new Prop(e.getValue(), p, true));
            }
        });
        int i = 0;
        for (JsonNode m : obj.path("tags")) {
            Cfg mc = fromJson(m, pointer + "/tags/" + i);
            c.members.put(mc.name.toLowerCase(Locale.ROOT), mc);
            i++;
        }
        return c;
    }

    /** {@code over} applied on top of {@code base} (already marked inherited by the caller). §3 order. */
    static Cfg merge(Cfg base, Cfg over) {
        Cfg c = base;
        c.name = over.name;
        c.pointer = over.pointer;
        c.exported = over.exported;
        if (!over.tagType.isEmpty()) c.tagType = over.tagType;
        if (over.typeId != null) c.typeId = over.typeId;
        c.props.putAll(over.props);
        over.params.forEach((k, v) -> {
            Prop old = c.params.get(k);
            JsonNode value = v.value();
            if (old != null && value.isObject() && !value.has("value") && old.value().has("value")) {
                value = old.value(); // a parameter override that carries only its data type keeps the inherited value
            }
            c.params.put(k, new Prop(value, v.pointer(), v.own()));
        });
        c.paramNames.putAll(over.paramNames);
        over.alarms.forEach((k, v) -> {
            Prop old = c.alarms.get(k);
            if (old != null && old.value().isObject() && v.value().isObject()) {
                ObjectNode merged = ((ObjectNode) old.value()).deepCopy();
                merged.setAll((ObjectNode) v.value());
                c.alarms.put(k, new Prop(merged, v.pointer(), v.own()));
            } else {
                c.alarms.put(k, v);
            }
        });
        c.events.putAll(over.events);
        over.members.forEach((k, m) -> {
            Cfg existing = c.members.get(k);
            c.members.put(k, existing == null ? m : merge(existing, m));
        });
        return c;
    }

    // ---- snapshot model ------------------------------------------------------------------------------------

    record Node(String nodeId, String locator, String kind, List<String> path, String pointer, String payload) {}

    /** A dependency still to be resolved once every node exists. */
    record Pending(String edgeId, String ownerId, String field, String relation, String access, String text,
                   Target target, String basis) {}

    /** Where a reference points: a local locator, an external canonical text, or nothing usable. */
    record Target(String resolution, String locator, String canonical, String selector) {
        static Target local(String locator, String selector) {
            return new Target("resolved", locator, locator, selector);
        }

        static Target external(String canonical) {
            return new Target("external-required", null, canonical, null);
        }

        static Target unsupported(String original) {
            return new Target("unsupported", null, "@unparsed/" + escapeSegment(original), null);
        }
    }

    private final String artifactId;
    private final JsonNode root;
    private final Map<String, Cfg> typeDefs = new LinkedHashMap<>();   // key: lower-case path under _types_
    private final Map<String, Cfg> typeMemo = new HashMap<>();
    private final Set<String> resolving = new HashSet<>();

    private final List<Node> nodes = new ArrayList<>();
    private final Map<String, Node> byLocator = new HashMap<>();       // key: folded locator
    private final List<ObjectNode> edges = new ArrayList<>();
    private final List<Pending> pending = new ArrayList<>();
    private final List<ObjectNode> claims = new ArrayList<>();
    private final List<ObjectNode> unresolved = new ArrayList<>();
    private final Map<String, OpState> operations = new LinkedHashMap<>();
    private int nextNode;
    private int nextEdge;
    private int nextClaim;
    private int nextIssue;

    record OpState(Node node, String state, List<String> kinds, String pointer) {}

    private IgnitionInterpreter(String artifactId, JsonNode root) {
        this.artifactId = artifactId;
        this.root = root;
    }

    /** Parses the export strictly; duplicate keys and trailing content are invalid input (§ scope). */
    public static JsonNode parse(byte[] export) {
        try {
            return JSON.readTree(export);
        } catch (JsonProcessingException e) {
            String m = e.getOriginalMessage();
            throw new ContractViolation(m != null && m.startsWith("Duplicate field") ? "json.duplicate-key" : "json.invalid", m);
        } catch (java.io.IOException e) {
            throw new ContractViolation("json.invalid", e.getMessage());
        }
    }

    /**
     * Interprets one export. Returns the snapshot parts (nativeNodes, referenceEdges, claims, unresolved); the
     * caller wraps them into a pinned document.
     */
    public static ObjectNode interpret(String artifactId, byte[] export) {
        IgnitionInterpreter it = new IgnitionInterpreter(artifactId, parse(export));
        return it.run();
    }

    private ObjectNode run() {
        List<JsonNode> top = new ArrayList<>();
        List<String> topPointers = new ArrayList<>();
        if (root.isObject() && root.path("tagType").asText("").equals("Provider")) {
            int i = 0;
            for (JsonNode t : root.path("tags")) {
                top.add(t);
                topPointers.add("/tags/" + i++);
            }
        } else if (root.isObject()) {
            top.add(root);
            topPointers.add("");
        } else if (root.isArray()) {
            int i = 0;
            for (JsonNode t : root) {
                top.add(t);
                topPointers.add("/" + i++);
            }
        } else {
            throw new ContractViolation("ignition.unsupported-input", "the export is neither an object nor an array");
        }
        for (int i = 0; i < top.size(); i++) {
            if (top.get(i).path("name").asText("").equals("_types_")) collectTypes(top.get(i), topPointers.get(i), "");
        }
        for (int i = 0; i < top.size(); i++) {
            JsonNode t = top.get(i);
            Cfg c = fromJson(t, topPointers.get(i));
            emit(c, List.of(c.name), null, Map.of(), t.path("name").asText("").equals("_types_"));
        }
        resolvePending();
        finishOperations();

        ObjectNode out = F.objectNode();
        ArrayNode n = out.putArray("nativeNodes");
        for (Node node : nodes) n.add(nodeJson(node));
        ArrayNode e = out.putArray("referenceEdges");
        edges.forEach(e::add);
        ArrayNode c = out.putArray("claims");
        claims.forEach(c::add);
        ArrayNode u = out.putArray("unresolved");
        unresolved.forEach(u::add);
        return out;
    }

    private void collectTypes(JsonNode folder, String pointer, String prefix) {
        int i = 0;
        for (JsonNode t : folder.path("tags")) {
            String p = pointer + "/tags/" + i++;
            String name = t.path("name").asText("");
            String rel = prefix.isEmpty() ? name : prefix + "/" + name;
            switch (t.path("tagType").asText("")) {
                case "UdtType" -> typeDefs.put(rel.toLowerCase(Locale.ROOT), fromJson(t, p));
                case "Folder" -> collectTypes(t, p, rel);
                default -> { }
            }
        }
    }

    /** The effective definition of a UDT type, its ancestors applied first (§3). Null when it cannot be had. */
    private Cfg effectiveType(String typeId) {
        String key = typeId.toLowerCase(Locale.ROOT);
        if (typeMemo.containsKey(key)) return typeMemo.get(key);
        Cfg own = typeDefs.get(key);
        if (own == null || !resolving.add(key)) return null;
        Cfg result;
        if (own.typeId != null && !own.typeId.isEmpty()) {
            Cfg parent = effectiveType(own.typeId);
            result = parent == null ? own : merge(parent.copyInherited(), own);
        } else {
            result = own;
        }
        resolving.remove(key);
        typeMemo.put(key, result);
        return result;
    }

    // ---- emission (§2) ----------------------------------------------------------------------------------------

    private void emit(Cfg c, List<String> path, Node parent, Map<String, Prop> scope, boolean underTypes) {
        switch (c.tagType) {
            case "Folder" -> {
                Node node = node(path, "group", c.pointer, null);
                contains(parent, node);
                unknownProps(node, c, GROUP_STRUCTURAL);
                for (Cfg m : c.members.values()) emit(m, append(path, m.name), node, scope, underTypes);
            }
            case "UdtType" -> emitType(c, path, parent);
            case "UdtInstance" -> emitInstance(c, path, parent, scope);
            case "AtomicTag" -> emitAtomic(c, path, parent, scope);
            default -> {
                Node node = node(path, "opaque", c.pointer, c.pointer);
                contains(parent, node);
                issue("unsupported", node, null, "tag type '" + c.tagType + "' is not in this profile");
                issue("behavior-unknown", node, null, "an unsupported tag type is not interpreted");
                unknownState(node);
            }
        }
    }

    private void emitType(Cfg own, List<String> path, Node parent) {
        String typePath = String.join("/", path.subList(1, path.size()));
        Cfg eff = effectiveType(typePath);
        Cfg c = eff == null ? own : eff;
        Node node = node(path, "group", own.pointer, null);
        contains(parent, node);
        claim(node, "tagType", F.textNode("UdtType"), own.pointer + "/tagType", true, "node-kind");
        unknownProps(node, c, GROUP_STRUCTURAL);
        if (own.typeId != null && !own.typeId.isEmpty()) {
            inherits(node, own.typeId, own.pointer + "/typeId");
            if (eff == null) issue("unsupported", node, null, "the parent type cannot be resolved without a cycle");
        }
        Map<String, Prop> scope = paramScope(node, c, Map.of());
        for (Cfg m : c.members.values()) emit(m, append(path, m.name), node, scope, true);
    }

    private void emitInstance(Cfg inst, List<String> path, Node parent, Map<String, Prop> outer) {
        Node node = node(path, "group", inst.pointer, null);
        contains(parent, node);
        claim(node, "tagType", F.textNode("UdtInstance"), inst.pointer + "/tagType", inst.exported, "node-kind");
        Cfg c = inst;
        if (inst.typeId == null || inst.typeId.isEmpty()) {
            issue("unsupported", node, null, "a UDT instance without typeId");
        } else {
            Cfg type = inst.typeId.startsWith("[") ? null : effectiveType(inst.typeId);
            inherits(node, inst.typeId, inst.pointer + "/typeId");
            if (type != null) c = merge(type.copyInherited(), inst);
        }
        unknownProps(node, c, GROUP_STRUCTURAL);
        Map<String, Prop> scope = paramScope(node, c, outer);
        for (Cfg m : c.members.values()) emit(m, append(path, m.name), node, scope, false);
    }

    /**
     * Effective parameter values of a UDT scope (§3). A parameter declared without a usable value stays in scope
     * as {@link #UNRESOLVED}: it is still a parameter, not a tag reference, but nothing that uses it is asserted.
     * A value bound to a parameter of the containing scope is evaluated there.
     */
    private Map<String, Prop> paramScope(Node node, Cfg c, Map<String, Prop> outer) {
        Map<String, Prop> scope = new LinkedHashMap<>();
        for (Map.Entry<String, Prop> e : c.params.entrySet()) {
            Prop p = e.getValue();
            JsonNode v = p.value().isObject() ? p.value().get("value") : p.value();
            String written = c.paramNames.getOrDefault(e.getKey(), e.getKey());
            String valuePointer = p.pointer() + (p.value().isObject() ? "/value" : "");
            JsonNode resolved = null;
            boolean derived = !p.own();
            if (v == null) {
                resolved = null;
            } else if (v.isNull()) {
                if (p.own()) issue("unsupported", node, "parameters." + written, "an explicit null parameter value is not asserted");
            } else if (isParameterBinding(v)) {
                String binding = v.get("binding").asText();
                Matcher whole = PARAM.matcher(binding.trim());
                Prop single = whole.matches() ? outer.get(whole.group(1).trim().toLowerCase(Locale.ROOT)) : null;
                if (single != null && !single.value().isMissingNode()) {
                    resolved = single.value();
                } else {
                    Sub s = bind(binding, outer);
                    if (s.status().equals("ok")) resolved = F.textNode(s.text());
                }
                derived = true;
            } else if (v.isContainerNode()) {
                issue("unsupported", node, "parameters." + written, "a parameter value in an unsupported form");
            } else if (v.isTextual() && !outer.isEmpty()) {
                Sub s = fill(v.asText(), outer);
                if (s.status().equals("ok")) {
                    resolved = F.textNode(s.text());
                    if (!s.text().equals(v.asText())) {
                        // §3 (1.2.0): substituted from the containing scope; the written text stays as .raw
                        derived = true;
                        claim(node, "parameters." + written + ".raw", v, valuePointer, p.own(), "claims");
                    }
                }
            } else {
                resolved = v;
            }
            if (resolved == null) {
                scope.put(e.getKey(), new Prop(UNRESOLVED, valuePointer, false));
                continue;
            }
            JsonNode scalar = scalar(resolved);
            scope.put(e.getKey(), new Prop(scalar, valuePointer, !derived));
            claim(node, "parameters." + written, scalar, p.pointer(), !derived, "effective-config");
        }
        return scope;
    }

    private void emitAtomic(Cfg c, List<String> path, Node parent, Map<String, Prop> scope) {
        Prop vsProp = c.props.get("valueSource");
        String vs = vsProp == null || !vsProp.value().isTextual() ? null : vsProp.value().asText();
        String kind = vs == null || VALUE_SOURCES.contains(vs) ? ("expr".equals(vs) ? "operation" : "tag") : "opaque";
        Node node = node(path, kind, c.pointer, "opaque".equals(kind) ? c.pointer : null);
        contains(parent, node);
        if (vs == null) {
            issue("unsupported", node, "valueSource", "no value source is written or inherited");
        } else if (!VALUE_SOURCES.contains(vs)) {
            issue("unsupported", node, "valueSource", "value source '" + vs + "' is not in this profile");
            issue("behavior-unknown", node, null, "an unsupported value source is not interpreted");
            unknownState(node);
        }
        for (String f : ATOMIC_FIELDS) {
            Prop p = c.props.get(f);
            if (p != null) fieldClaim(node, f, p, scope);
        }
        // §7: only a memory tag's value and defaultValue are excluded; on any other source they are unmodelled
        unknownProps(node, c, vs == null || "memory".equals(vs) ? ATOMIC_STRUCTURAL : ATOMIC_STRUCTURAL_NOT_MEMORY);

        Prop ro = c.props.get("readOnly");
        String writeAccess = ro == null ? "write" : ro.value().isBoolean() ? (ro.value().asBoolean() ? null : "write") : "unknown";
        if ("unknown".equals(writeAccess)) issue("unsupported", node, "readOnly", "readOnly is bound; whether it can be written is not known");

        List<String> folder = path.subList(0, path.size() - 1);
        if ("expr".equals(vs)) {
            Sub text = textOf(c.props.get("expression"), scope);
            if (text == null) {
                issue("unsupported", node, "expression", "the expression is missing or in an unsupported form");
                issue("behavior-unknown", node, "expression", "an expression that cannot be read is not classified");
                unknownState(node);
            } else {
                Expressions.Parsed parsed = Expressions.parse(text.text());
                boolean unsupportedRef = false;
                for (String ref : parsed.refs()) {
                    if (isParam(ref, scope)) continue;
                    Target t = refTarget(ref, folder, false);
                    unsupportedRef |= t.resolution().equals("unsupported");
                    depend(node, "expression", "input", "read", ref, t, "expression reference");
                }
                for (String unbraced : parsed.unbraced()) {
                    // §5 (1.2.0): a path written without braces is kept as an unsupported reference on its text
                    unsupportedRef = true;
                    depend(node, "expression", "input", "read", unbraced, Target.unsupported(unbraced), "path written without braces");
                }
                Expressions.State st = Expressions.state(parsed);
                if (unsupportedRef || !text.status().equals("ok")) {
                    // §6: an unsupported argument form, or a parameter without a value, makes the whole expression unknown
                    unknownState(node);
                    issue("behavior-unknown", node, "expression", "the expression has an argument this profile cannot read");
                } else if (st.state().equals("unknown")) {
                    unknownState(node);
                    issue("behavior-unknown", node, "expression", parsed.unknownSyntax()
                            ? "the expression uses syntax this profile does not classify"
                            : "the expression calls a function this profile does not classify");
                } else {
                    operations.put(node.nodeId(), new OpState(node, st.state(), st.kinds(), c.props.get("expression").pointer()));
                }
            }
        } else if ("reference".equals(vs) || "derived".equals(vs)) {
            Prop src = c.props.get("sourceTagPath");
            Sub sub = textOf(src, scope);
            if (sub == null) {
                issue("unsupported", node, "sourceTagPath", "the source tag path is missing or in an unsupported form");
            } else {
                String text = sub.text();
                Target t = sub.status().equals("ok") ? pathTarget(text, folder) : Target.unsupported(text);
                depend(node, "sourceTagPath", "reference", "read", text, t, vs + " tag source");
                boolean setter = "reference".equals(vs) || c.props.containsKey("deriveExpressionSetter");
                if (setter && writeAccess != null) depend(node, "sourceTagPath", "reference", writeAccess, text, t, vs + " tag writes through to its source");
            }
            if ("derived".equals(vs)) {
                for (String f : List.of("deriveExpressionGetter", "deriveExpressionSetter")) {
                    Sub expr = textOf(c.props.get(f), scope);
                    if (expr == null) continue;
                    for (String ref : Expressions.parse(expr.text()).refs()) {
                        if (ref.equalsIgnoreCase("source") || ref.equalsIgnoreCase("value") || isParam(ref, scope)) continue;
                        depend(node, f, "input", "read", ref, refTarget(ref, folder, false), "derived expression reference");
                    }
                }
            }
        } else if ("opc".equals(vs)) {
            Prop server = c.props.get("opcServer");
            Target t = resourceTarget("opc", server, scope);
            if (t == null) {
                issue("unsupported", node, "opcServer", "an OPC tag without a server name");
            } else {
                depend(node, "opcServer", "external", "read", t.canonical(), t, "OPC tag reads its server");
                if (writeAccess != null) depend(node, "opcServer", "external", writeAccess, t.canonical(), t, "OPC tag writes to its server");
            }
        } else if ("db".equals(vs)) {
            Prop ds = c.props.get("datasource");
            Target t = resourceTarget("db", ds, scope);
            if (t == null) {
                issue("unsupported", node, "datasource", "a query tag without a datasource name");
            } else {
                Prop qt = c.props.get("queryType");
                String access = qt != null && qt.value().asText("").equalsIgnoreCase("select") ? "read" : "unknown";
                depend(node, "datasource", "external", access, t.canonical(), t, "query tag uses its datasource");
            }
            issue("behavior-unknown", node, "query", "SQL is never run; what it returns is not known");
        }
        for (Map.Entry<String, Prop> e : c.events.entrySet()) eventScript(node, e.getKey(), e.getValue(), folder);
        for (Prop a : c.alarms.values()) alarm(node, a, folder, scope);
    }

    private void eventScript(Node tag, String eventId, Prop p, List<String> folder) {
        List<String> path = append(tag.path(), "#event/" + eventId);
        Node node = node(path, "opaque", p.pointer(), p.pointer() + "/script");
        contains(tag, node);
        unknownState(node);
        issue("behavior-unknown", node, null, "a tag event script is not executed");
        depend(node, "eventid", "input", "event", eventId, Target.local(tag.locator(), null), "the script runs on this tag's event");
        String script = p.value().path("script").asText("");
        Matcher m = SCRIPT_TAG_CALL.matcher(script);
        while (m.find()) {
            String fn = m.group(1).toLowerCase(Locale.ROOT);
            String access = SCRIPT_WRITES.contains(fn) ? "write" : "read";
            List<String> literals = literalArgument(script, m.end());
            if (literals == null) {
                // §4 gaps: a dynamic target is kept as an unsupported dependency on its original argument text
                String arg = firstArgument(script, m.end());
                depend(node, "script", "reference", access, arg, Target.unsupported(arg),
                        "system.tag." + m.group(1) + " with a target that is not a literal");
                continue;
            }
            for (String target : literals) {
                depend(node, "script", "reference", access, target, scriptTarget(target, folder), "literal target of system.tag." + m.group(1));
            }
        }
    }

    private void alarm(Node tag, Prop p, List<String> folder, Map<String, Prop> scope) {
        JsonNode a = p.value();
        String name = a.path("name").asText("");
        Node node = node(append(tag.path(), "#alarm/" + name), "policy", p.pointer(), null);
        contains(tag, node);
        depend(node, "name", "policy", "read", tag.locator(), Target.local(tag.locator(), null), "an alarm watches its tag");
        boolean unknown = false;
        for (Map.Entry<String, JsonNode> e : iterable(a)) {
            String k = e.getKey();
            String ptr = p.pointer() + "/" + escapePointer(k);
            JsonNode v = e.getValue();
            if (k.equals("name")) continue;
            if (ALARM_NAMED_RESOURCES.contains(k)) {
                issue("external-required", node, k, "names an alarm pipeline kept on the gateway");
                continue;
            }
            if (v.isObject() && v.has("bindType")) {
                String bt = v.path("bindType").asText("");
                if (bt.equalsIgnoreCase("tag") && v.path("value").isTextual()) {
                    String ref = v.get("value").asText();
                    depend(node, k, "input", "read", ref, pathTarget(ref, folder), "alarm property bound to a tag");
                    if (ALARM_FIELDS.contains(k)) {
                        claim(node, k + ".raw", F.textNode(ref), ptr, p.own(), "claims");
                        claim(node, k + ".bindType", F.textNode(bt), ptr, p.own(), "claims");
                    } else {
                        unknown = true; // §9 (1.2.0): the dependency is kept, the property itself is unmodelled
                    }
                } else if (bt.equalsIgnoreCase("parameter") && v.path("binding").isTextual() && ALARM_FIELDS.contains(k)) {
                    fieldClaim(node, k, new Prop(v, ptr, p.own()), scope);
                } else {
                    unknown = true;
                }
                continue;
            }
            if (ALARM_FIELDS.contains(k)) {
                fieldClaim(node, k, new Prop(v, ptr, p.own()), scope);
            } else {
                unknown = true;
            }
        }
        if (unknown) issue("unsupported", node, null, "alarm configuration outside this profile's projection");
    }

    // ---- claims (§7) --------------------------------------------------------------------------------------------

    private void fieldClaim(Node node, String field, Prop p, Map<String, Prop> scope) {
        JsonNode v = p.value();
        boolean observed = p.own();
        if (v.isObject() && v.has("bindType")) {
            String bt = v.path("bindType").asText("");
            JsonNode binding = v.has("binding") ? v.get("binding") : v.get("value");
            if (binding != null && binding.isTextual()) claim(node, field + ".raw", binding, p.pointer(), observed, "claims");
            claim(node, field + ".bindType", F.textNode(bt), p.pointer(), observed, "claims");
            if (bt.equalsIgnoreCase("parameter") && binding != null && binding.isTextual()) {
                Sub resolved = bind(binding.asText(), scope);
                if (resolved.status().equals("missing")) {
                    issue("missing", node, field, "the binding names a parameter that is not declared");
                } else if (resolved.status().equals("unresolvable")) {
                    issue("unsupported", node, field, "the binding names a parameter that has no usable value");
                } else {
                    String resolvedField = field.equals("opcItemPath") ? "opcItemPath.resolved" : field;
                    claim(node, resolvedField, F.textNode(resolved.text()), p.pointer(), false, "effective-config");
                }
            } else {
                issue("unsupported", node, field, "a property bound by '" + bt + "' is not interpreted");
            }
            return;
        }
        if (v.isContainerNode()) {
            issue("unsupported", node, field, "a structured value is not flattened");
            return;
        }
        if (field.equals("opcItemPath")) {
            claim(node, "opcItemPath.raw", scalar(v), p.pointer(), observed, "claims");
            return;
        }
        if (v.isTextual() && PARAMETER_TEXT_FIELDS.contains(field) && !scope.isEmpty()) {
            Sub sub = fill(v.asText(), scope);
            if (!sub.status().equals("ok")) {
                claim(node, field + ".raw", v, p.pointer(), observed, "claims"); // §3: the affected value is not asserted
                return;
            }
            if (!sub.text().equals(v.asText())) {
                claim(node, field + ".raw", v, p.pointer(), observed, "claims");
                claim(node, field, F.textNode(sub.text()), p.pointer(), false, "effective-config");
                return;
            }
        }
        claim(node, field, scalar(v), p.pointer(), observed, observed ? "claims" : "effective-config");
        if (NAMED_RESOURCES.contains(field)) issue("external-required", node, field, "names a resource kept on the gateway");
    }

    private void unknownProps(Node node, Cfg c, Set<String> structural) {
        boolean atomic = structural != GROUP_STRUCTURAL;
        for (Map.Entry<String, Prop> e : c.props.entrySet()) {
            String k = e.getKey();
            if (structural.contains(k) || (atomic && ATOMIC_FIELDS.contains(k))) continue;
            if (NAMED_RESOURCES.contains(k) || k.toLowerCase(Locale.ROOT).contains("permission")) {
                // decided 2026-10-27: a named gateway resource outside the projection gets both codes
                issue("external-required", node, k, "names a resource kept on the gateway");
            }
            issue("unsupported", node, k, "property outside this profile's projection");
        }
    }

    private void claim(Node node, String field, JsonNode value, String pointer, boolean observed, String rule) {
        ObjectNode c = F.objectNode();
        c.put("claimId", "c." + nextClaim++);
        ObjectNode subject = c.putObject("subject");
        subject.put("nodeId", node.nodeId());
        subject.put("field", field);
        c.set("value", value);
        c.put("evidenceBasis", observed ? "observed-in-artifact" : "derived-by-rule");
        c.putArray("locators").add(locator(pointer));
        if (!observed) rule(c, rule);
        c.put("status", "asserted");
        claims.add(c);
    }

    private void rule(ObjectNode claim, String rule) {
        ObjectNode r = claim.putObject("rule");
        r.put("ruleId", NAMESPACE + "." + rule);
        r.put("version", RULE_VERSION);
    }

    // ---- dependencies (§4, §5) ------------------------------------------------------------------------------

    private void contains(Node parent, Node child) {
        if (parent == null) return;
        ObjectNode e = F.objectNode();
        e.put("edgeId", "e." + nextEdge++);
        ObjectNode owner = e.putObject("owner");
        owner.put("nodeId", parent.nodeId());
        owner.put("field", "tags");
        e.put("relation", "contains");
        e.put("targetText", child.locator());
        e.put("resolution", "resolved");
        e.put("targetNodeId", child.nodeId());
        e.put("basis", "nesting in the export");
        edges.add(e);
    }

    private void inherits(Node node, String typeId, String pointer) {
        List<String> typePath = new ArrayList<>(List.of("_types_"));
        typePath.addAll(List.of(typeId.split("/")));
        Target t = typeId.startsWith("[") ? Target.external(typeId) : Target.local(locatorOf(typePath), null);
        depend(node, "typeId", "inherits", "read", typeId, t, "the effective configuration is read from the type");
    }

    private void depend(Node owner, String field, String relation, String access, String text, Target target, String basis) {
        pending.add(new Pending("e." + nextEdge++, owner.nodeId(), field, relation, access, text, target, basis));
    }

    private void resolvePending() {
        for (Pending p : pending) {
            Target t = p.target();
            String resolution = t.resolution();
            String targetNodeId = null;
            String canonical = t.canonical();
            if (resolution.equals("resolved")) {
                Node target = byLocator.get(fold(t.locator()));
                if (target == null && t.locator().contains(".")) target = propertyOwner(t.locator());
                if (target == null) {
                    resolution = "missing";
                } else {
                    targetNodeId = target.nodeId();
                    canonical = target.locator();
                }
            }
            ObjectNode e = F.objectNode();
            e.put("edgeId", p.edgeId());
            ObjectNode owner = e.putObject("owner");
            owner.put("nodeId", p.ownerId());
            owner.put("field", p.field());
            e.put("relation", p.relation());
            e.put("access", p.access());
            e.put("targetText", truncate(canonical, 1000));
            e.put("resolution", resolution);
            if (targetNodeId != null) e.put("targetNodeId", targetNodeId);
            e.put("basis", truncate(p.basis() + ": " + p.text(), 2000));
            edges.add(e);
            if (!resolution.equals("resolved")) {
                ObjectNode u = issueJson(resolution, p.ownerId(), p.field(),
                        resolution.equals("external-required") ? "the target is outside this export" :
                        resolution.equals("missing") ? "the target is not in this export" : "the reference is not in a supported form");
                u.put("edgeId", p.edgeId());
                unresolved.add(u);
            }
        }
    }

    /** A reference to a tag property or array element ({@code Tag.Quality}, {@code Tag[0]}) belongs to its tag. */
    private Node propertyOwner(String locator) {
        int slash = locator.lastIndexOf('/');
        String last = locator.substring(slash + 1);
        int cut = last.indexOf('.');
        int bracket = last.indexOf('[');
        if (bracket >= 0 && (cut < 0 || bracket < cut)) cut = bracket;
        if (cut <= 0) return null;
        return byLocator.get(fold(locator.substring(0, slash + 1) + last.substring(0, cut)));
    }

    /** A reference written in an expression: {@code [.]x}, {@code [~]x}, {@code [provider]x}; bare paths are unsupported. */
    private Target refTarget(String ref, List<String> folder, boolean pathValued) {
        if (ref.startsWith("[")) return pathTarget(ref, folder);
        if (pathValued) return pathTarget(ref, folder);
        return Target.unsupported("{" + ref + "}"); // KEY-FORMAT: the escaped original text, braces included
    }

    /** A path-valued property: bracketed as in expressions, or provider-relative when unqualified (§5). */
    private Target pathTarget(String text, List<String> folder) {
        String rest = text;
        List<String> base;
        if (text.startsWith("[")) {
            int close = text.indexOf(']');
            if (close < 0) return Target.unsupported(text);
            String provider = text.substring(1, close);
            rest = text.substring(close + 1);
            if (provider.equals(".")) {
                base = new ArrayList<>(folder);
            } else if (provider.equals("~")) {
                base = new ArrayList<>();
            } else {
                return Target.external("[" + provider + "]" + rest);
            }
        } else {
            base = new ArrayList<>();
        }
        if (rest.startsWith("/")) rest = rest.substring(1);
        if (rest.isEmpty()) return Target.unsupported(text);
        for (String seg : rest.split("/", -1)) {
            if (seg.equals("..")) {
                if (base.isEmpty()) return Target.unsupported(text);
                base.remove(base.size() - 1);
            } else if (seg.equals(".")) {
                continue;
            } else if (seg.isEmpty()) {
                return Target.unsupported(text);
            } else {
                base.add(seg);
            }
        }
        return Target.local(locatorOf(base), null);
    }

    /** Script paths need a provider; an unqualified one depends on the gateway's default provider (§5). */
    private Target scriptTarget(String text, List<String> folder) {
        if (text.startsWith("[")) return pathTarget(text, folder);
        return Target.external("[]" + text);
    }

    /** The literal string, or list of literal strings, that starts at {@code from}; null when it is anything else. */
    static List<String> literalArgument(String s, int from) {
        int i = skipSpace(s, from);
        List<String> out = new ArrayList<>();
        if (i < s.length() && s.charAt(i) == '[') {
            i++;
            while (true) {
                i = skipSpace(s, i);
                if (i < s.length() && s.charAt(i) == ']') return out.isEmpty() ? null : out;
                int end = stringEnd(s, i);
                if (end < 0) return null;
                out.add(s.substring(i + 1, end));
                i = skipSpace(s, end + 1);
                if (i < s.length() && s.charAt(i) == ',') i++;
                else if (i < s.length() && s.charAt(i) == ']') return out;
                else return null;
            }
        }
        int end = stringEnd(s, i);
        if (end < 0) return null;
        out.add(s.substring(i + 1, end));
        i = skipSpace(s, end + 1);
        return i < s.length() && (s.charAt(i) == ',' || s.charAt(i) == ')') ? out : null;
    }

    private static int skipSpace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static int stringEnd(String s, int i) {
        if (i >= s.length() || (s.charAt(i) != '"' && s.charAt(i) != '\'')) return -1;
        char q = s.charAt(i);
        for (int j = i + 1; j < s.length(); j++) {
            if (s.charAt(j) == '\\') {
                j++;
            } else if (s.charAt(j) == q) {
                return j;
            }
        }
        return -1;
    }

    // ---- expression state (§6) ------------------------------------------------------------------------------

    private void finishOperations() {
        Map<String, List<String>> graph = new HashMap<>();
        for (ObjectNode e : edges) {
            if (!e.path("relation").asText().equals("input") || !e.has("targetNodeId")) continue;
            String from = e.path("owner").path("nodeId").asText();
            String to = e.get("targetNodeId").asText();
            if (operations.containsKey(from) && operations.containsKey(to)) graph.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
        }
        Set<String> inCycle = cycles(graph);
        for (OpState op : operations.values()) {
            String state = op.state();
            List<String> kinds = new ArrayList<>(op.kinds());
            if (inCycle.contains(op.node().nodeId())) {
                state = "stateful";
                kinds.add("feedback");
            }
            String claimId = "c." + nextClaim;
            claim(op.node(), "state", F.textNode(state), op.pointer(), false, "expression-state");
            states.put(op.node().nodeId(), new String[] {state, String.join(",", kinds), claimId});
        }
    }

    private final Map<String, String[]> states = new HashMap<>();

    private static Set<String> cycles(Map<String, List<String>> graph) {
        Set<String> result = new HashSet<>();
        for (String start : graph.keySet()) {
            List<String> stack = new ArrayList<>(List.of(start));
            Set<String> seen = new HashSet<>();
            while (!stack.isEmpty()) {
                String n = stack.remove(stack.size() - 1);
                for (String m : graph.getOrDefault(n, List.of())) {
                    if (m.equals(start)) result.add(start);
                    if (seen.add(m)) stack.add(m);
                }
            }
        }
        return result;
    }

    private void unknownState(Node node) {
        states.put(node.nodeId(), new String[] {"unknown", "", null});
    }

    // ---- nodes and issues -------------------------------------------------------------------------------------

    private Node node(List<String> path, String kind, String pointer, String payload) {
        String locator = locatorOf(path);
        Node n = new Node("n." + nextNode++, locator, kind, path, pointer, payload);
        if (byLocator.putIfAbsent(fold(locator), n) != null) {
            throw new ContractViolation("ignition.locator-collision", locator);
        }
        nodes.add(n);
        return n;
    }

    private Node connection(String kind, String name, String pointer) {
        String locator = "@external/" + kind + "/" + escapeSegment(name);
        Node existing = byLocator.get(fold(locator));
        if (existing != null) return existing;
        Node n = new Node("n." + nextNode++, locator, "connection", null, pointer, null);
        byLocator.put(fold(locator), n);
        nodes.add(n);
        issue("external-required", n, null, "the " + kind + " connection is configured on the gateway, not in this export");
        return n;
    }

    private void issue(String code, Node node, String field, String reason) {
        ObjectNode u = issueJson(code, node.nodeId(), field, reason);
        unresolved.add(u);
    }

    private ObjectNode issueJson(String code, String nodeId, String field, String reason) {
        ObjectNode u = F.objectNode();
        u.put("issueId", "u." + nextIssue++);
        u.put("code", code);
        u.put("nodeId", nodeId);
        if (field != null) u.put("field", truncate(field, 200));
        u.put("reason", reason);
        u.putArray("affectedNodeIds").add(nodeId);
        return u;
    }

    private ObjectNode nodeJson(Node n) {
        ObjectNode o = F.objectNode();
        o.put("nodeId", n.nodeId());
        o.put("namespace", NAMESPACE);
        o.put("nativeId", truncate(n.locator(), 1000));
        if (n.path() != null && n.kind().matches("tag|operation|group")) {
            ArrayNode p = o.putArray("nativePath");
            n.path().forEach(p::add);
        }
        o.put("kind", n.kind());
        o.set("locator", locator(n.pointer()));
        if (n.payload() != null) o.set("payload", locator(n.payload()));
        String[] st = states.get(n.nodeId());
        if (st != null) {
            o.put("state", st[0]);
            if (!st[1].isEmpty()) {
                ArrayNode k = o.putArray("stateKinds");
                for (String s : st[1].split(",")) k.add(s);
            }
            if (st[2] != null) o.put("stateClaimId", st[2]);
        }
        return o;
    }

    private ObjectNode locator(String pointer) {
        ObjectNode l = F.objectNode();
        l.put("artifactId", artifactId);
        ObjectNode at = l.putObject("at");
        at.put("kind", "json-pointer");
        at.put("pointer", pointer);
        return l;
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private boolean isParam(String ref, Map<String, Prop> scope) {
        return scope.containsKey(ref.toLowerCase(Locale.ROOT));
    }

    /**
     * The text of a property with declared {Param} tokens put in; null when it is neither plain text nor a parameter
     * binding. Status {@code ok}, {@code missing} (a binding names an undeclared parameter) or {@code unresolvable}
     * (a declared parameter has no usable value); when not ok the text keeps the tokens.
     */
    private Sub textOf(Prop p, Map<String, Prop> scope) {
        if (p == null) return null;
        JsonNode v = p.value();
        if (isParameterBinding(v)) return bind(v.get("binding").asText(), scope);
        if (!v.isTextual()) return null;
        return fill(v.asText(), scope);
    }

    /** §4: the external resource a property names, as a connection stub, or unsupported when its binding cannot be read. */
    private Target resourceTarget(String kind, Prop p, Map<String, Prop> scope) {
        if (p == null) return null;
        Sub s = textOf(p, scope);
        if (s == null) return null;
        if (!s.status().equals("ok")) return Target.unsupported(s.text());
        return Target.external(connection(kind, s.text(), p.pointer()).locator());
    }

    static boolean isParameterBinding(JsonNode v) {
        return v != null && v.isObject() && v.path("bindType").asText("").equalsIgnoreCase("parameter") && v.path("binding").isTextual();
    }

    /** A parameter declared in scope without a usable value (§3). */
    static final JsonNode UNRESOLVED = MissingNode.getInstance();
    /** Fields whose text may carry {Param} tokens; when substituted they are claimed as field.raw and field (§7). */
    static final Set<String> PARAMETER_TEXT_FIELDS = Set.of("expression", "documentation", "deriveExpressionGetter", "deriveExpressionSetter");

    record Sub(String text, String status) {}

    private static final Pattern PARAM = Pattern.compile("\\{([^{}\\[\\]]+)\\}");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_ ]*");

    /** A parameter binding: every {token} in it names a parameter, so an undeclared one is missing. */
    static Sub bind(String text, Map<String, Prop> scope) {
        return put(text, scope, true);
    }

    /** Expression-like text: only declared parameter tokens are put in; other braces are tag references. */
    static Sub fill(String text, Map<String, Prop> scope) {
        return put(text, scope, false);
    }

    private static Sub put(String text, Map<String, Prop> scope, boolean binding) {
        Matcher m = PARAM.matcher(text);
        StringBuilder sb = new StringBuilder();
        String status = "ok";
        while (m.find()) {
            String token = m.group(1).trim();
            Prop p = scope.get(token.toLowerCase(Locale.ROOT));
            if (p != null && !p.value().isMissingNode()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(p.value().isTextual() ? p.value().asText() : p.value().toString()));
                continue;
            }
            if (p != null) {
                if (status.equals("ok")) status = "unresolvable";
            } else if (binding && !IDENTIFIER.matcher(token).matches()) {
                // §3: a token that is not a plain name (e.g. arithmetic on a parameter) is an unsupported binding form
                if (status.equals("ok")) status = "unresolvable";
            } else if (binding) {
                status = "missing";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
        }
        m.appendTail(sb);
        return new Sub(status.equals("ok") ? sb.toString() : text, status);
    }

    /** The trimmed text of the argument that starts at {@code from}, up to its top-level comma or closing paren. */
    static String firstArgument(String s, int from) {
        int depth = 0;
        char quote = 0;
        int i = from;
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) break;
                depth--;
            } else if (c == ',' && depth == 0) {
                break;
            }
        }
        String arg = s.substring(from, i).trim();
        return arg.isEmpty() ? "?" : arg;
    }

    /** §7 / KEY-FORMAT: numbers become integers when integral, otherwise canonical decimal strings. */
    static JsonNode scalar(JsonNode v) {
        if (v.isNumber()) {
            BigDecimal d = v.decimalValue().stripTrailingZeros();
            if (d.signum() == 0) return F.numberNode(0L);
            if (d.scale() <= 0) {
                BigInteger i = d.toBigIntegerExact();
                if (i.bitLength() <= 53) return F.numberNode(i.longValue());
            }
            return F.textNode(d.toPlainString());
        }
        return v;
    }

    static String locatorOf(List<String> path) {
        List<String> segs = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            String s = path.get(i);
            if (s.startsWith("#")) {
                segs.set(segs.size() - 1, segs.get(segs.size() - 1) + "#" + escapeExtension(s.substring(1)));
            } else {
                segs.add(escapeSegment(s));
            }
        }
        return String.join("/", segs);
    }

    /** "#event/<id>" or "#alarm/<name>": the namespace word stays, the name is escaped. */
    private static String escapeExtension(String s) {
        int slash = s.indexOf('/');
        return s.substring(0, slash + 1) + escapeSegment(s.substring(slash + 1));
    }

    static String escapeSegment(String s) {
        String e = s.replace("%", "%25").replace("/", "%2F").replace("#", "%23");
        return e.startsWith("@") ? "%40" + e.substring(1) : e;
    }

    static String fold(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    static String escapePointer(String key) {
        return key.replace("~", "~0").replace("/", "~1");
    }

    private static List<String> append(List<String> path, String seg) {
        List<String> p = new ArrayList<>(path);
        p.add(seg);
        return p;
    }

    private static Iterable<Map.Entry<String, JsonNode>> iterable(JsonNode o) {
        return o::fields;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
