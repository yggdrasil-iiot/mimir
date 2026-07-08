package dev.krillin.mimir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.milo.opcua.sdk.client.OpcUaClient;

/**
 * Mímir CLI entrypoint. Subcommand:
 *
 * <pre>
 *   derive &lt;endpoint&gt; &lt;nsUri&gt; &lt;typeBrowseName&gt; &lt;templateRef&gt; &lt;version&gt; &lt;outFile&gt; [--omit &lt;member&gt;]
 * </pre>
 *
 * Connects to a live OPC-UA server, browses the named ObjectType into pure {@link BrowsedMember}
 * facts, optionally projects out one member ({@code --omit}, applied HERE — never in the browser),
 * derives a canonical {@link UdtDefinition}, and writes it as compact JSON to {@code outFile}.
 */
public final class MimirMain {

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || !"derive".equals(args[0])) {
            usage();
            System.exit(2);
        }
        // positional: derive <endpoint> <nsUri> <typeBrowseName> <templateRef> <version> <outFile>
        if (args.length < 7) {
            usage();
            System.exit(2);
        }
        String endpoint = args[1];
        String nsUri = args[2];
        String typeBrowseName = args[3];
        String templateRef = args[4];
        String version = args[5];
        String outFile = args[6];

        String omit = null;
        for (int i = 7; i < args.length; i++) {
            if ("--omit".equals(args[i]) && i + 1 < args.length) {
                omit = args[++i];
            } else {
                System.err.println("unknown argument: " + args[i]);
                usage();
                System.exit(2);
            }
        }

        OpcUaClient client = null;
        int exitCode = 0;
        try {
            client = OpcUaClient.create(endpoint);
            client.connect();

            List<BrowsedMember> members = OpcUaMixerBrowser.browse(client, nsUri, typeBrowseName);

            if (omit != null) {
                final String omitName = omit;
                members = members.stream().filter(m -> !m.name().equals(omitName)).toList();
            }

            UdtDefinition def = DefinitionDeriver.derive(members, typeBrowseName, templateRef, version);
            String json = DefinitionJson.toJson(def);
            Files.writeString(Path.of(outFile), json);

            System.out.println("[MIMIR] derived " + templateRef + "@" + version
                    + " members=" + def.members().size() + " -> " + outFile);
            for (Member m : def.members()) {
                System.out.println("[MIMIR]   " + m.name() + " : " + m.type()
                        + " range=" + (m.range() == null ? "null" : "[" + m.range().low() + ", " + m.range().high() + "]"));
            }
        } catch (Exception e) {
            System.err.println("[MIMIR] error: " + e.getMessage());
            exitCode = 2;
        } finally {
            if (client != null) {
                try {
                    client.disconnect();
                } catch (Exception ignore) {
                    // best-effort
                }
            }
        }
        System.exit(exitCode);
    }

    private static void usage() {
        System.err.println("Usage: derive <endpoint> <nsUri> <typeBrowseName> <templateRef> <version> <outFile> [--omit <member>]");
    }

    private MimirMain() {
    }
}
