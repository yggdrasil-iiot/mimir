package dev.krillin.mimir;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IgnitionCliTest {

    static final String EXPORT = "fixtures/ignition/8.3.10/dev-1/export.json";

    @Test
    void interpretWritesNothingWithoutAStatedVersion(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("out");
        assertEquals(3, IgnitionCli.run(new String[] {"ignition-interpret", EXPORT, "lab/dev-1", out.toString()}));
        assertFalse(Files.exists(out));
    }

    @Test
    void interpretWritesNothingForAnUnsupportedVersion(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("out");
        assertEquals(3, IgnitionCli.run(new String[] {"ignition-interpret", EXPORT, "lab/dev-1", out.toString(),
                "--product-version", "8.1.0"}));
        assertFalse(Files.exists(out));
    }

    @Test
    void interpretWritesTheThreeDocumentsForASupportedVersion(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("out");
        assertEquals(0, IgnitionCli.run(new String[] {"ignition-interpret", "--product-version", "8.3.10", EXPORT,
                "lab/dev-1", out.toString()}));
        for (String f : new String[] {"source-bundle.json", "interpretation-profile.json", "interpretation-snapshot.json"}) {
            assertTrue(Files.exists(out.resolve(f)), f);
        }
    }

    @Test
    void scoreRefusesWithoutAStatedVersion() throws Exception {
        assertEquals(3, IgnitionCli.run(new String[] {"ignition-score", EXPORT, "fixtures/ignition/8.3.10/dev-1/key.json"}));
    }
}
