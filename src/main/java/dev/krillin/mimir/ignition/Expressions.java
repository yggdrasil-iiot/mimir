package dev.krillin.mimir.ignition;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads an Ignition expression without evaluating it: the {@code {...}} references outside quoted strings, the
 * function names it calls, and the operator symbols it uses. SPEC §5 (references) and §6 (expression state).
 */
final class Expressions {

    /** Functions whose result depends only on their arguments (SPEC §6). */
    static final Set<String> STATELESS = Set.of("if", "coalesce", "isnull", "abs", "ceil", "floor", "round", "min",
            "max", "sqrt", "pow", "len", "lower", "upper", "trim", "toint", "tofloat", "todouble", "tostring",
            "toboolean", "now");
    /** hasChanged keeps the previous value to detect an edge. */
    static final String HAS_CHANGED = "haschanged";
    /**
     * §6 (1.2.0): the characters of the listed operators — arithmetic, comparison, logical, bitwise and shift —
     * plus grouping and separators. Any other symbol outside strings and references is unknown syntax.
     */
    static final String OPERATOR_CHARS = "+-*/%^&|~<>=!(),.";

    /**
     * @param refs     the text inside each {@code {...}} reference
     * @param unbraced path-shaped text written without braces ({@code [.]x}, {@code [~]x}, {@code [provider]x})
     * @param unknownSyntax subscripts, unlisted operator symbols or unterminated strings and references
     */
    record Parsed(List<String> refs, List<String> unbraced, Set<String> functions, boolean unknownSyntax) {}

    private Expressions() {}

    static Parsed parse(String text) {
        List<String> refs = new ArrayList<>();
        List<String> unbraced = new ArrayList<>();
        Set<String> functions = new LinkedHashSet<>();
        boolean unknown = false;
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
                if (end >= n) unknown = true;
                i = end + 1;
            } else if (c == '{') {
                int end = text.indexOf('}', i + 1);
                if (end < 0) {
                    unknown = true;
                    break;
                }
                refs.add(text.substring(i + 1, end).trim());
                i = end + 1;
            } else if (c == '[') {
                unknown = true; // neither subscripts nor unbraced paths are in the §6 table
                char before = previousNonSpace(text, i);
                if (before == '}' || before == ']' || Character.isLetterOrDigit(before) || before == '_') {
                    i++; // a subscript ({a}[{i}])
                } else {
                    int end = pathEnd(text, i);
                    unbraced.add(text.substring(i, end).trim());
                    i = end;
                }
            } else if (Character.isLetter(c) || c == '_') {
                int end = i;
                while (end < n && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_' || text.charAt(end) == '.')) end++;
                int look = end;
                while (look < n && Character.isWhitespace(text.charAt(look))) look++;
                if (look < n && text.charAt(look) == '(') functions.add(text.substring(i, end).toLowerCase());
                i = end;
            } else {
                if (!Character.isWhitespace(c) && !Character.isDigit(c) && OPERATOR_CHARS.indexOf(c) < 0) unknown = true;
                i++;
            }
        }
        return new Parsed(refs, unbraced, functions, unknown);
    }

    private static char previousNonSpace(String s, int i) {
        for (int j = i - 1; j >= 0; j--) {
            if (!Character.isWhitespace(s.charAt(j))) return s.charAt(j);
        }
        return ' ';
    }

    /** An unbraced path runs to the next top-level comma, closing paren or whitespace. */
    private static int pathEnd(String s, int from) {
        int depth = 0;
        for (int j = from; j < s.length(); j++) {
            char c = s.charAt(j);
            if (c == '[') depth++;
            else if (c == ']') depth = Math.max(0, depth - 1);
            else if (depth == 0 && (c == ',' || c == ')' || Character.isWhitespace(c))) return j;
        }
        return s.length();
    }

    /** Scalar text and a boolean for "every function is understood". */
    record State(String state, List<String> kinds) {}

    static State state(Parsed p) {
        if (p.unknownSyntax()) return new State("unknown", List.of());
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
