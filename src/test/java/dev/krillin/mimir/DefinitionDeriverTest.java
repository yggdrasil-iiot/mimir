package dev.krillin.mimir;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefinitionDeriverTest {

    @Test
    void derivesUdtDefinitionFromBrowsedMembers() {
        List<BrowsedMember> browsed = List.of(
                new BrowsedMember("Rpm", 11, 0.0, 3000.0),
                new BrowsedMember("Temp", 11, 0.0, 450.0),
                new BrowsedMember("Running", 1, null, null),
                new BrowsedMember("Secret", 11, null, null)
        );

        UdtDefinition result = DefinitionDeriver.derive(browsed, "MixerType", "Line1-Mixer", "1.0.0");

        assertEquals(4, result.members().size());

        Member rpm = result.members().get(0);
        assertEquals("Double", rpm.type());
        assertEquals(new Range(0, 3000), rpm.range());
        assertEquals("urn:bifrost:sem:Mixer/Rpm", rpm.semanticId());

        Member running = result.members().get(2);
        assertEquals("Boolean", running.type());
        assertNull(running.range());

        Member secret = result.members().get(3);
        assertEquals("Secret", secret.name());
        assertNull(secret.range());

        assertEquals("Line1-Mixer", result.templateRef());
        assertEquals("1.0.0", result.version());
    }

    @Test
    void uaTypeNamesMapsKnownIdsToSparkplugMetricDataTypeLiterals() {
        assertEquals("Boolean", UaTypeNames.of(1));
        assertEquals("Int32", UaTypeNames.of(6));
        assertEquals("UInt32", UaTypeNames.of(7));
        assertEquals("Int64", UaTypeNames.of(8));
        assertEquals("Float", UaTypeNames.of(10));
        assertEquals("Double", UaTypeNames.of(11));
        assertEquals("String", UaTypeNames.of(12));
        assertEquals("DateTime", UaTypeNames.of(13));
    }

    @Test
    void uaTypeNamesThrowsOnUnmappedId() {
        assertThrows(IllegalArgumentException.class, () -> UaTypeNames.of(9999));
    }
}
