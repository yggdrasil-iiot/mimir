package dev.krillin.mimir;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.ExtensionObject;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned;
import org.eclipse.milo.opcua.stack.core.types.enumerated.BrowseDirection;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseResult;
import org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId;
import org.eclipse.milo.opcua.stack.core.types.structured.ReferenceDescription;

/**
 * Live shell (Milo 1.0.0): browse an OPC-UA ObjectType's declared member Variables into a list of
 * pure {@link BrowsedMember} facts (name + numeric DataType id + optional EURange low/high). No
 * business logic and no test/omit hooks live here — this browses FAITHFULLY; the {@code --omit}
 * projection and the mapping to a canonical definition are applied by the caller
 * ({@link MimirMain} / {@link DefinitionDeriver}).
 *
 * <p>The Milo structured {@code Range} type is fully-qualified inline so it does not clash with this
 * project's own {@link dev.krillin.mimir.Range}.
 */
public final class OpcUaMixerBrowser {

    // OPC-UA standard NodeIds (numeric, ns=0)
    private static final NodeId HAS_COMPONENT = new NodeId(0, 47);
    private static final NodeId HAS_PROPERTY  = new NodeId(0, 46);

    private static final UInteger NODECLASS_ALL = Unsigned.uint(0xFF);
    private static final UInteger RESULT_ALL    = Unsigned.uint(0x3F);

    private OpcUaMixerBrowser() {
    }

    /** Browse the declared member Variables of {@code typeBrowseName} in namespace {@code nsUri}. */
    public static List<BrowsedMember> browse(OpcUaClient client, String nsUri, String typeBrowseName)
            throws Exception {
        NamespaceTable nt = client.getNamespaceTable();
        UShort ns = nt.getIndex(nsUri);
        if (ns == null) {
            throw new IllegalStateException("namespace URI '" + nsUri + "' not found on server");
        }
        NodeId typeNode = new NodeId(ns, typeBrowseName);

        List<BrowsedMember> members = new ArrayList<>();

        BrowseResult res = client.browse(new BrowseDescription(
                typeNode, BrowseDirection.Forward, HAS_COMPONENT, false, NODECLASS_ALL, RESULT_ALL));
        for (ReferenceDescription r : safeRefs(res)) {
            if (r.getNodeClass() != NodeClass.Variable) continue;
            String name = r.getBrowseName().getName();
            NodeId memberNode = r.getNodeId().toNodeId(nt).orElse(null);
            if (memberNode == null) continue;

            int dataTypeId = readDataTypeId(client, memberNode);
            double[] range = readEuRange(client, nt, memberNode); // null-marker via NaN sentinel below
            Double low = range == null ? null : range[0];
            Double high = range == null ? null : range[1];

            members.add(new BrowsedMember(name, dataTypeId, low, high));
        }
        return members;
    }

    /** Read the member's DataType attribute → numeric NodeId identifier. */
    private static int readDataTypeId(OpcUaClient client, NodeId memberNode) throws Exception {
        ReadValueId rvid = new ReadValueId(
                memberNode, AttributeId.DataType.uid(), null, QualifiedName.NULL_VALUE);
        var resp = client.read(0.0, TimestampsToReturn.Neither, List.of(rvid));
        DataValue dtDv = resp.getResults()[0];
        NodeId dtNode = (NodeId) dtDv.getValue().getValue();
        return ((UInteger) dtNode.getIdentifier()).intValue();
    }

    /**
     * Read the member's EURange HasProperty (if present) and decode it with the STATIC encoding
     * context — the dynamic context returns a generic DynamicStructType that cannot be cast to
     * {@code Range} (verified Milo 1.0.0 gotcha). Returns {@code {low, high}} or {@code null} if the
     * member has no EURange property.
     */
    private static double[] readEuRange(OpcUaClient client, NamespaceTable nt, NodeId memberNode)
            throws Exception {
        BrowseResult props = client.browse(new BrowseDescription(
                memberNode, BrowseDirection.Forward, HAS_PROPERTY, false, NODECLASS_ALL, RESULT_ALL));
        for (ReferenceDescription r : safeRefs(props)) {
            if (!"EURange".equals(r.getBrowseName().getName())) continue;
            NodeId propNode = r.getNodeId().toNodeId(nt).orElse(null);
            if (propNode == null) continue;
            DataValue dv = client.readValue(0.0, TimestampsToReturn.Both, propNode);
            Object v = dv.getValue().getValue();
            if (!(v instanceof ExtensionObject eo)) continue;
            org.eclipse.milo.opcua.stack.core.types.structured.Range range =
                    (org.eclipse.milo.opcua.stack.core.types.structured.Range)
                            eo.decode(client.getStaticEncodingContext());
            Double low = range.getLow();
            Double high = range.getHigh();
            if (low == null || high == null) return null;
            return new double[]{low, high};
        }
        return null;
    }

    private static ReferenceDescription[] safeRefs(BrowseResult res) {
        ReferenceDescription[] refs = res != null ? res.getReferences() : null;
        return refs != null ? refs : new ReferenceDescription[0];
    }
}
