package dev.krillin.mimir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.krillin.mimir.ignition.AnswerKey;
import dev.krillin.mimir.ignition.IgnitionDocuments;
import dev.krillin.mimir.interpretation.ContractViolation;
import dev.krillin.mimir.interpretation.InterpretationContract;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Ignition subcommands:
 *
 * <pre>
 *   ignition-interpret &lt;export.json&gt; &lt;name&gt; &lt;outDir&gt;   bundle, profile and snapshot, checked against the contract
 *   ignition-score &lt;export.json&gt; &lt;key.json&gt; [--details]  interpret, then compare with an answer key
 * </pre>
 *
 * {@code ignition-score} prints counts only unless {@code --details} is given, so it can measure a held-out
 * key without showing its rows.
 */
final class IgnitionCli {

    private static final ObjectMapper OUT = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private IgnitionCli() {}

    static int run(String[] args) throws Exception {
        try {
            switch (args[0]) {
                case "ignition-interpret" -> {
                    if (args.length != 4) return usage();
                    IgnitionDocuments.Set docs = interpret(Path.of(args[1]), args[2]);
                    Path out = Path.of(args[3]);
                    Files.createDirectories(out);
                    Files.writeString(out.resolve("source-bundle.json"), OUT.writeValueAsString(docs.bundle()) + "\n");
                    Files.writeString(out.resolve("interpretation-profile.json"), OUT.writeValueAsString(docs.profile()) + "\n");
                    Files.writeString(out.resolve("interpretation-snapshot.json"), OUT.writeValueAsString(docs.snapshot()) + "\n");
                    JsonNode body = docs.snapshot().get("body");
                    System.out.println("[MIMIR] ignition " + args[2] + ": nodes=" + body.get("nativeNodes").size()
                            + " edges=" + body.get("referenceEdges").size() + " claims=" + body.get("claims").size()
                            + " unresolved=" + body.get("unresolved").size() + " -> " + out);
                    return 0;
                }
                case "ignition-score" -> {
                    if (args.length < 3) return usage();
                    boolean details = List.of(args).contains("--details");
                    IgnitionDocuments.Set docs = interpret(Path.of(args[1]), "score/run");
                    JsonNode expected = new ObjectMapper().readTree(Files.readString(Path.of(args[2])));
                    AnswerKey.Result r = AnswerKey.compare(expected, AnswerKey.project(docs.snapshot()));
                    for (Map.Entry<String, AnswerKey.Score> e : r.scores().entrySet()) {
                        AnswerKey.Score s = e.getValue();
                        System.out.printf("%-10s expected=%d matched=%d missing=%d extra=%d recall=%s precision=%s%n",
                                e.getKey(), s.expected(), s.matched(), s.missing(), s.extra(), pct(s.recall()), pct(s.precision()));
                    }
                    if (details) {
                        for (String cat : AnswerKey.CATEGORIES) {
                            r.missing().get(cat).forEach(row -> System.out.println("MISSING " + cat + " " + row));
                            r.extra().get(cat).forEach(row -> System.out.println("EXTRA   " + cat + " " + row));
                        }
                    }
                    return 0;
                }
                default -> {
                    return usage();
                }
            }
        } catch (ContractViolation v) {
            // a score run may be measuring a held-out key: report the code, not the detail, which can quote the input
            System.err.println("[MIMIR] refused: " + (args[0].equals("ignition-score") ? v.code() : v.getMessage()));
            return 3;
        } catch (RuntimeException e) {
            if (!args[0].equals("ignition-score")) throw e;
            System.err.println("[MIMIR] failed: " + e.getClass().getName());
            return 4;
        }
    }

    /** Interprets and checks the result against the contract before anything is written or scored. */
    private static IgnitionDocuments.Set interpret(Path export, String name) throws Exception {
        byte[] bytes = Files.readAllBytes(export);
        IgnitionDocuments.Set docs = IgnitionDocuments.build(name, bytes, export.getFileName().toString(), "2026-10-27T00:00:00Z");
        InterpretationContract.checkPinned(reparse(docs.snapshot()), reparse(docs.bundle()), reparse(docs.profile()));
        return docs;
    }

    private static JsonNode reparse(ObjectNode n) throws Exception {
        return InterpretationContract.parse(OUT.writeValueAsString(n));
    }

    private static String pct(Double d) {
        return d == null ? "n/a" : String.format("%.1f%%", d * 100);
    }

    private static int usage() {
        System.err.println("Usage: ignition-interpret <export.json> <name> <outDir> | ignition-score <export.json> <key.json> [--details]");
        return 2;
    }
}
