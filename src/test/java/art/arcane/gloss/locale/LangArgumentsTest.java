package art.arcane.gloss.locale;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangArgumentsTest {
    @Test
    void orderedPlaceholdersKeepsFirstAppearanceOrderAndHonoursTheEscape() {
        assertEquals(List.of("item", "percent"),
            LangArguments.orderedPlaceholders("Smelting {item} {percent}% {item}"));
        assertEquals(List.of("b"), LangArguments.orderedPlaceholders("{{a}} {b}"));
        assertEquals(List.of(), LangArguments.orderedPlaceholders("unclosed {a"));
    }

    @Test
    void argumentsBindPositionallyOntoTheKeyOwnPlaceholderNames() {
        TextKey key = LangArguments.messageKey("gloss.preview.state.smelting_item");

        MessageArgs arguments = LangArguments.arguments(key,
            List.of(key.id(), "Iron Ore", 42.0D));

        assertEquals(List.of("item", "percent"), new ArrayList<>(arguments.names()));
        assertEquals("Iron Ore", arguments.require("item").value());
        assertEquals("42", arguments.require("percent").value());
    }

    @Test
    void argumentsForAnUnknownKeyFallBackToPositionalNames() {
        TextKey key = LangArguments.messageKey("gloss.not.a.real.key");

        MessageArgs arguments = LangArguments.arguments(key,
            List.of(key.id(), 3.0D, "coal", Boolean.TRUE));

        assertEquals(List.of("arg0", "arg1", "arg2"), new ArrayList<>(arguments.names()));
        assertEquals("3", arguments.require("arg0").value());
        assertEquals("coal", arguments.require("arg1").value());
        assertEquals("true", arguments.require("arg2").value());
    }

    @Test
    void surplusArgumentsAreDroppedForAKnownKey() {
        MessageArgs arguments = LangArguments.arguments(GlossMessages.THEME_TITLE_BARREL,
            List.of(GlossMessages.THEME_TITLE_BARREL.id(), "Barrel"));

        assertTrue(arguments.names().isEmpty());
    }

    @Test
    void untrustedArgumentsCannotSmuggleColourCodes() {
        TextKey key = LangArguments.messageKey("gloss.preview.state.smelting_item");

        MessageArgs arguments = LangArguments.arguments(key, List.of(key.id(), "&cIron Ore"));

        assertEquals("&cIron Ore", arguments.require("item").value());
        assertEquals(art.arcane.volmlib.util.localization.MessageArgumentKind.UNTRUSTED,
            arguments.require("item").kind());
    }
}
