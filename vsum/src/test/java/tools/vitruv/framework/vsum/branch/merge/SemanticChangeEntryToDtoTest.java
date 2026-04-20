package tools.vitruv.framework.vsum.branch.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.vitruv.framework.vsum.branch.storage.ChangeOrigin;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeEntry;
import tools.vitruv.framework.vsum.branch.storage.SemanticChangeType;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SemanticChangeEntryToDto}.
 *
 * <p>Verifies that a sample {@link SemanticChangeEntry} maps to a {@link ChangeDto}
 * with correct field values (Step 8 verify condition).
 */
class SemanticChangeEntryToDtoTest {

    @Test
    @DisplayName("ORIGINAL entry maps to ChangeDto with isConsequential=false and correct fields")
    void originalEntryMapsToDto() {
        SemanticChangeEntry entry = SemanticChangeEntry.builder()
                .index(0)
                .changeType(SemanticChangeType.ATTRIBUTE_CHANGED)
                .emfType("ReplaceSingleValuedEAttribute")
                .elementUuid("uuid-entity-1")
                .feature("name")
                .from("OldName")
                .to("NewName")
                .origin(ChangeOrigin.ORIGINAL)
                .build();

        SemanticChangeEntryToDto converter = new SemanticChangeEntryToDto();
        List<ChangeDto> dtos = converter.convert("abc1234", List.of(entry));

        assertEquals(1, dtos.size());
        ChangeDto dto = dtos.get(0);
        assertEquals("abc1234", dto.getCommitSha());
        assertEquals("uuid-entity-1", dto.getAffectedElementUuid());
        assertEquals("name", dto.getFeatureName());
        assertEquals("OldName", dto.getOldValue());
        assertEquals("NewName", dto.getNewValue());
        assertEquals("ATTRIBUTE_CHANGED", dto.getChangeType());
        assertFalse(dto.isConsequential(), "ORIGINAL entry must not be marked as consequential");
    }

    @Test
    @DisplayName("CONSEQUENTIAL entry maps to ChangeDto with isConsequential=true")
    void consequentialEntryMapsToDto() {
        SemanticChangeEntry entry = SemanticChangeEntry.builder()
                .index(0)
                .changeType(SemanticChangeType.ATTRIBUTE_CHANGED)
                .emfType("ReplaceSingleValuedEAttribute")
                .elementUuid("uuid-table-1")
                .feature("name")
                .from("OldTable")
                .to("NewTable")
                .origin(ChangeOrigin.CONSEQUENTIAL)
                .build();

        SemanticChangeEntryToDto converter = new SemanticChangeEntryToDto();
        List<ChangeDto> dtos = converter.convert("def5678", List.of(entry));

        ChangeDto dto = dtos.get(0);
        assertTrue(dto.isConsequential(), "CONSEQUENTIAL entry must be marked as consequential");
    }

    @Test
    @DisplayName("CONSEQUENTIAL entry appears in consequential footprint; ORIGINAL does not")
    void consequentialFootprintContainsOnlyConsequentialEntries() {
        SemanticChangeEntry original = SemanticChangeEntry.builder()
                .index(0)
                .changeType(SemanticChangeType.ATTRIBUTE_CHANGED)
                .emfType("Test")
                .elementUuid("uuid-a")
                .feature("name")
                .from("A")
                .to("B")
                .origin(ChangeOrigin.ORIGINAL)
                .build();

        SemanticChangeEntry consequential = SemanticChangeEntry.builder()
                .index(1)
                .changeType(SemanticChangeType.ATTRIBUTE_CHANGED)
                .emfType("Test")
                .elementUuid("uuid-b")
                .feature("title")
                .from("X")
                .to("Y")
                .origin(ChangeOrigin.CONSEQUENTIAL)
                .build();

        SemanticChangeEntryToDto converter = new SemanticChangeEntryToDto();
        converter.convert("aaa1111", List.of(original, consequential));

        Set<String> fp = converter.getConsequentialFootprint("aaa1111");
        assertFalse(fp.contains("uuid-a|name"),
                "ORIGINAL entry must not appear in consequential footprint");
        assertTrue(fp.contains("uuid-b|title"),
                "CONSEQUENTIAL entry must appear in consequential footprint");
    }

    @Test
    @DisplayName("convertAll converts multiple commits at once")
    void convertAllGroupsCommitsSeparately() {
        SemanticChangeEntry entryA = SemanticChangeEntry.builder()
                .index(0).changeType(SemanticChangeType.ATTRIBUTE_CHANGED).emfType("T")
                .elementUuid("uuid-x").feature("f").from("1").to("2")
                .origin(ChangeOrigin.ORIGINAL).build();

        SemanticChangeEntry entryB = SemanticChangeEntry.builder()
                .index(0).changeType(SemanticChangeType.ELEMENT_CREATED).emfType("T")
                .elementUuid("uuid-y").origin(ChangeOrigin.ORIGINAL).build();

        SemanticChangeEntryToDto converter = new SemanticChangeEntryToDto();
        Map<String, List<ChangeDto>> result = converter.convertAll(Map.of(
                "sha1111", List.of(entryA),
                "sha2222", List.of(entryB)));

        assertEquals(2, result.size());
        assertEquals(1, result.get("sha1111").size());
        assertEquals(1, result.get("sha2222").size());
        assertEquals("uuid-x", result.get("sha1111").get(0).getAffectedElementUuid());
        assertEquals("uuid-y", result.get("sha2222").get(0).getAffectedElementUuid());
    }
}
