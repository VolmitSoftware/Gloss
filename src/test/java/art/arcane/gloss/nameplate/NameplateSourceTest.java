package art.arcane.gloss.nameplate;

import art.arcane.gloss.condition.EntityRelationshipSnapshot;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class NameplateSourceTest {
    private static final UUID VIEWER = new UUID(0, 1);
    private static final UUID SUBJECT = new UUID(0, 2);

    @Test
    void sourceSelectionUsesCapturedEntityKindAndFeatureState() {
        NameplateSource source = new NameplateSource(() -> true);
        assertTrue(source.wants(subject(false, false, false, false)));
        assertFalse(source.wants(new EntityRelationshipSnapshot(SUBJECT, "mob", false, false, false, false, false)));
        assertFalse(new NameplateSource(() -> false).wants(subject(false, false, false, false)));
    }

    @Test
    void visibilityReadsOnlyCapturedRelationshipFields() {
        NameplateSource source = new NameplateSource(() -> true);
        NameplateDoc.Presentation defaults = presentation("");
        assertTrue(source.visible(VIEWER, subject(false, false, false, false), defaults));
        assertFalse(source.visible(VIEWER, subject(false, true, false, false), defaults));
        assertFalse(source.visible(VIEWER, subject(true, false, false, false), defaults));
        assertFalse(source.visible(VIEWER, subject(false, false, true, false), defaults));
        assertFalse(source.visible(SUBJECT, subject(false, false, false, false), defaults));
    }

    @Test
    void visibilityAndNpcPoliciesCanBeAuthoredIndependently() {
        NameplateSource source = new NameplateSource(() -> true);
        assertTrue(source.visible(VIEWER, subject(true, true, true, false),
            presentation("\"hideInvisible\":false,\"hideSneaking\":false,\"hideSpectator\":false")));
        assertTrue(source.visible(SUBJECT, subject(false, false, false, false), presentation("\"showSelf\":true")));
        assertFalse(source.visible(VIEWER, subject(false, false, false, true), presentation("\"includeNpcs\":false")));
        assertTrue(source.visible(VIEWER, subject(false, false, false, true), presentation("\"includeNpcs\":true")));
    }

    @Test
    void healthSegmentsArePresentationSettingsWithBoundedDefaults() {
        assertEquals(10, presentation("").healthSegments());
        assertEquals(1, presentation("\"healthSegments\":0").healthSegments());
        assertEquals(40, presentation("\"healthSegments\":100").healthSegments());
        assertEquals(24, presentation("\"healthSegments\":24").healthSegments());
    }

    private static EntityRelationshipSnapshot subject(boolean invisible, boolean sneaking, boolean spectator, boolean npc) {
        return new EntityRelationshipSnapshot(SUBJECT, "subject", true, invisible, sneaking, spectator, npc);
    }

    private static NameplateDoc.Presentation presentation(String fields) {
        return NameplateDoc.parse("test", "{\"schemaVersion\":1,\"revision\":1,\"presentation\":{" + fields + "}}").presentation();
    }
}
