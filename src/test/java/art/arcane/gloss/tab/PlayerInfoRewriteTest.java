package art.arcane.gloss.tab;

import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The grid is the only thing a viewer with a layout sees, so every other entry in an add-player
 * packet is unlisted on the way out. Anything taken away has to be remembered, or a second plugin's
 * fake entries stay gone after the layout does.
 */
class PlayerInfoRewriteTest {

    @Test
    void ourOwnSlotsSurviveAndEverythingElseIsUnlistedAndRecorded() {
        UUID real = UUID.randomUUID();
        UUID npc = UUID.randomUUID();
        List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> entries = new ArrayList<>(List.of(
            entry(real, "Steve"),
            entry(UUID.randomUUID(), TablistLayoutRuntime.SLOT_NAME_PREFIX + "00"),
            entry(npc, "Shopkeeper")));
        Set<UUID> recorded = new LinkedHashSet<>();

        assertTrue(PlayerInfoRewriteListener.unlistRealEntries(entries, recorded::add));

        assertEquals(Set.of(real, npc), recorded);
        assertFalse(entries.get(0).isListed());
        assertTrue(entries.get(1).isListed());
        assertFalse(entries.get(2).isListed());
    }

    @Test
    void apacketOfNothingButOurOwnSlotsIsLeftAlone() {
        List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> entries = new ArrayList<>(List.of(
            entry(UUID.randomUUID(), TablistLayoutRuntime.SLOT_NAME_PREFIX + "00")));
        Set<UUID> recorded = new LinkedHashSet<>();

        assertFalse(PlayerInfoRewriteListener.unlistRealEntries(entries, recorded::add));

        assertEquals(Set.of(), recorded);
    }

    private static WrapperPlayServerPlayerInfoUpdate.PlayerInfo entry(UUID id, String name) {
        WrapperPlayServerPlayerInfoUpdate.PlayerInfo info =
            new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(new UserProfile(id, name));
        info.setListed(true);
        return info;
    }
}
