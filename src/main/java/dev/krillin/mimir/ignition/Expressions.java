package dev.krillin.mimir.ignition;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads an Ignition expression without evaluating it: the {@code {...}} references outside quoted strings and
 * the function names it calls. SPEC §5 (references) and §6 (expression state).
 */
final class Expressions {

    /** Functions whose result depends only on their arguments (SPEC §6). */
    static final Set<String> STATELESS = Set.of("if", "coalesce", "isnull", "abs", "ceil", "floor", "round", "min",
            "max", "sqrt", "pow", "len", "lower", "upper", "trim", "toint", "tofloat", "todouble", "tostring",
            "toboolean", "now");
    /** hasChanged keeps the previous value to detect an edge. */
    static final String HAS_CHANGED = "haschanged";

    record Parsed(List<String> refs, Set<String> functions, boolean malformed) {}

    private Expressions() {}

    static Parsed parse(String text) {
        List<String> refs = new ArrayList<>();
        Set<String> functions = new LinkedHashSet<>();
        boolean malformed = false;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                int end = i + 1;
                while (end < n && text.charAt(end) != c) {
                    if (text.charAt(end) == '\\') end++;
                    end++;
                }
                if (end >= n) malformed = true;
                i = end + 1;
            } else if (c == '{') {
                int end = text.indexOf('}', i + 1);
                if (end < 0) {
                    malformed = true;
                    break;
                }
                refs.add(text.substring(i + 1, end).trim());
                i = end + 1;
            } else if (c == '[') {
                malformed = true; // subscript syntax ({a}[{i}]) is not in this profile's table (§6)
                i++;
            } else if (Character.isLetter(c) || c == '_') {
                int end = i;
                while (end < n && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_' || text.charAt(end) == '.')) end++;
                int look = end;
                while (look < n && Character.isWhitespace(text.charAt(look))) look++;
                if (look < n && text.charAt(look) == '(') functions.add(text.substring(i, end).toLowerCase());
                i = end;
            } else {
                i++;
            }
        }
        return new Parsed(refs, functions, malformed);
    }

    /** Scalar text and a boolean for "every function is understood". */
    record State(String state, List<String> kinds) {}

    static State state(Parsed p) {
        if (p.malformed()) return new State("unknown", List.of());
        boolean stateful = false;
        for (String f : p.functions()) {
            if (f.equals(HAS_CHANGED)) {
                stateful = true;
            } else if (!STATELESS.contains(f)) {
                return new State("unknown", List.of());
            }
        }
        return stateful ? new State("stateful", List.of("edge-detect", "previous-value")) : new State("stateless", List.of());
    }
}
