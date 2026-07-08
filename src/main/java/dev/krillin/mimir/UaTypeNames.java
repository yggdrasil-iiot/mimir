package dev.krillin.mimir;

import java.util.Map;

/**
 * Maps OPC-UA built-in data type node ids to the exact Sparkplug
 * {@code MetricDataType.toString()} literal used by the downstream bifrost gate.
 * An unmapped id throws rather than emitting a guessed/UNKNOWN string, since a
 * wrong-cased or unknown type would silently mis-key the gate's exact
 * {@code String.equals} type comparison.
 */
public final class UaTypeNames {

    private static final Map<Integer, String> NAMES = Map.of(
            1, "Boolean",
            6, "Int32",
            7, "UInt32",
            8, "Int64",
            10, "Float",
            11, "Double",
            12, "String",
            13, "DateTime"
    );

    private UaTypeNames() {
    }

    public static String of(int id) {
        String name = NAMES.get(id);
        if (name == null) {
            throw new IllegalArgumentException("Unmapped OPC-UA data type id: " + id);
        }
        return name;
    }
}
