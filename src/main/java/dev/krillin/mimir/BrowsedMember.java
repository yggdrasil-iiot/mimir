package dev.krillin.mimir;

/**
 * One browsed member Variable. {@code nodeId} and {@code dataTypeNodeId} are written with the
 * namespace URI ({@code nsu=...;s=...}), not the server's namespace index, which another server or
 * a restart may renumber; namespace 0 stays implicit ({@code i=11}).
 */
public record BrowsedMember(String name, int dataTypeId, Double low, Double high,
                            String nodeId, String dataTypeNodeId) {
}
