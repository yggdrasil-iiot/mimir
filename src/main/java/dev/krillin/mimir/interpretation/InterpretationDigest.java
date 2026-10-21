package dev.krillin.mimir.interpretation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A document's digest is SHA-256 over the UTF-8 bytes of the JCS form of the document with its
 * top-level {@code digest} and {@code envelope} members removed. The envelope (creation time,
 * request id) is outside the digest so that re-interpreting the same bytes yields the same digest.
 */
public final class InterpretationDigest {

    private InterpretationDigest() {}

    public static String compute(JsonNode document) {
        if (!document.isObject()) {
            throw new ContractViolation("digest.not-an-object", "");
        }
        ObjectNode scope = ((ObjectNode) document).deepCopy();
        scope.remove("digest");
        scope.remove("envelope");
        return sha256(Jcs.canonicalize(scope).getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
