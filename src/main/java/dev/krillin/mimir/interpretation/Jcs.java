package dev.krillin.mimir.interpretation;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RFC 8785 (JCS) canonical form, restricted to what the interpretation contract allows: numbers must
 * be integers within the I-JSON safe range. A decimal travels as a string, so no implementation ever
 * has to agree on how to print a double.
 *
 * <p>Object members are sorted by UTF-16 code units, which is exactly {@link String#compareTo}.
 */
public final class Jcs {

    static final BigInteger MAX_SAFE = BigInteger.valueOf(9007199254740991L);

    private Jcs() {}

    public static String canonicalize(JsonNode node) {
        StringBuilder out = new StringBuilder();
        write(node, out, "");
        return out.toString();
    }

    private static void write(JsonNode node, StringBuilder out, String at) {
        switch (node.getNodeType()) {
            case OBJECT -> {
                List<String> names = new ArrayList<>();
                node.fieldNames().forEachRemaining(names::add);
                Collections.sort(names);
                out.append('{');
                for (int i = 0; i < names.size(); i++) {
                    if (i > 0) out.append(',');
                    String name = names.get(i);
                    quote(name, out, at);
                    out.append(':');
                    write(node.get(name), out, at + "/" + name);
                }
                out.append('}');
            }
            case ARRAY -> {
                out.append('[');
                for (int i = 0; i < node.size(); i++) {
                    if (i > 0) out.append(',');
                    write(node.get(i), out, at + "/" + i);
                }
                out.append(']');
            }
            case STRING -> quote(node.textValue(), out, at);
            case NUMBER -> {
                if (!node.isIntegralNumber()) {
                    throw new ContractViolation("jcs.non-integer-number", at);
                }
                BigInteger v = node.bigIntegerValue();
                if (v.abs().compareTo(MAX_SAFE) > 0) {
                    throw new ContractViolation("jcs.unsafe-integer", at);
                }
                out.append(v);
            }
            case BOOLEAN -> out.append(node.booleanValue());
            case NULL -> out.append("null");
            default -> throw new ContractViolation("jcs.unsupported-node", at);
        }
    }

    private static void quote(String s, StringBuilder out, String at) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else if (Character.isHighSurrogate(c)) {
                        if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) {
                            throw new ContractViolation("jcs.lone-surrogate", at);
                        }
                        out.append(c).append(s.charAt(++i));
                    } else if (Character.isLowSurrogate(c)) {
                        throw new ContractViolation("jcs.lone-surrogate", at);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
