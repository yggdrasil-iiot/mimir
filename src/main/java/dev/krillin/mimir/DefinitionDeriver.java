package dev.krillin.mimir;

import java.util.List;

public final class DefinitionDeriver {

    private static final String TYPE_SUFFIX = "Type";

    private DefinitionDeriver() {
    }

    public static UdtDefinition derive(
            List<BrowsedMember> members,
            String typeBrowseName,
            String templateRef,
            String version
    ) {
        String shortType = typeBrowseName.endsWith(TYPE_SUFFIX)
                ? typeBrowseName.substring(0, typeBrowseName.length() - TYPE_SUFFIX.length())
                : typeBrowseName;

        List<Member> derived = members.stream()
                .map(m -> {
                    Range range = (m.low() != null && m.high() != null)
                            ? new Range(m.low(), m.high())
                            : null;
                    String semanticId = "urn:bifrost:sem:" + shortType + "/" + m.name();
                    return new Member(m.name(), UaTypeNames.of(m.dataTypeId()), semanticId, range);
                })
                .toList();

        return new UdtDefinition(templateRef, version, derived, List.of(), null);
    }
}
