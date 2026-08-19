package art.arcane.gloss.command;

import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.director.compat.DirectorEngineFactory;
import art.arcane.volmlib.util.director.runtime.DirectorParameterDescriptor;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeEngine;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeNode;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageCatalog;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TextKey;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

class GlossCommandTreeTest {
    private static final List<List<String>> EXPECTED_PATHS = List.of(
            List.of("hologram"),
            List.of("hologram", "create"),
            List.of("hologram", "delete"),
            List.of("hologram", "addline"),
            List.of("hologram", "setline"),
            List.of("hologram", "removeline"),
            List.of("hologram", "clear"),
            List.of("hologram", "movehere"),
            List.of("hologram", "move"),
            List.of("hologram", "tp"),
            List.of("hologram", "list"),
            List.of("hologram", "info"),
            List.of("hologram", "rendertext"),
            List.of("board"),
            List.of("board", "create"),
            List.of("board", "delete"),
            List.of("board", "title"),
            List.of("board", "addline"),
            List.of("board", "setline"),
            List.of("board", "removeline"),
            List.of("board", "show"),
            List.of("board", "hide"),
            List.of("board", "primary"),
            List.of("board", "permission"),
            List.of("board", "list"),
            List.of("board", "info"),
            List.of("board", "reset"),
            List.of("emoji"),
            List.of("emoji", "list"),
            List.of("emoji", "reset"),
            List.of("animations"),
            List.of("animations", "list"),
            List.of("animations", "reset"),
            List.of("bubbles"),
            List.of("bubbles", "style"),
            List.of("bubbles", "reset"),
            List.of("tablist"),
            List.of("tablist", "reset"),
            List.of("motd"),
            List.of("motd", "reset"),
            List.of("status"),
            List.of("reload"),
            List.of("version"),
            List.of("menu"),
            List.of("menu", "list"),
            List.of("menu", "create"),
            List.of("menu", "open"),
            List.of("menu", "back"),
            List.of("menu", "close"),
            List.of("menu", "move"),
            List.of("menu", "builder"),
            List.of("menu", "edit"),
            List.of("menu", "addrow"),
            List.of("menu", "insertrow"),
            List.of("menu", "setrow"),
            List.of("menu", "removerow"),
            List.of("menu", "offsetrow"),
            List.of("menu", "seticon"),
            List.of("menu", "style"),
            List.of("menu", "image"),
            List.of("menu", "new"),
            List.of("menu", "copy"),
            List.of("panel"),
            List.of("panel", "list"),
            List.of("panel", "reload"),
            List.of("panel", "near"),
            List.of("panel", "info"),
            List.of("panel", "create"),
            List.of("panel", "delete"),
            List.of("panel", "rename"),
            List.of("panel", "copy"),
            List.of("panel", "move"),
            List.of("panel", "here"),
            List.of("panel", "teleport"),
            List.of("panel", "rotate"),
            List.of("panel", "scale"),
            List.of("panel", "align"),
            List.of("panel", "menu"),
            List.of("panel", "addrow"),
            List.of("panel", "insertrow"),
            List.of("panel", "setrow"),
            List.of("panel", "removerow"),
            List.of("panel", "offsetrow"),
            List.of("panel", "seticon"),
            List.of("panel", "style"),
            List.of("panel", "image"),
            List.of("panel", "ranges"),
            List.of("panel", "visibility"),
            List.of("panel", "permissions"),
            List.of("panel", "follow"),
            List.of("panel", "unfollow"),
            List.of("panel", "edit"),
            List.of("panel", "web"),
            List.of("panel", "save"),
            List.of("panel", "cancel"),
            List.of("preview"),
            List.of("preview", "list"),
            List.of("preview", "reset"),
            List.of("preview", "dump"),
            List.of("item"),
            List.of("item", "status"),
            List.of("item", "export"),
            List.of("sync"),
            List.of("sync", "list"),
            List.of("sync", "status"),
            List.of("sync", "revoke"),
            List.of("sync", "pull"),
            List.of("import"),
            List.of("import", "preview"),
            List.of("import", "apply"),
            List.of("import", "holoui"),
            List.of("import", "legacy")
    );
    private static final Set<String> GROUP_ROOTS = Set.of(
            "hologram", "board", "emoji", "animations", "bubbles", "tablist", "motd",
            "menu", "panel", "preview", "item", "sync", "import"
    );

    @Test
    void engineBuildsWithoutRunningServer() {
        DirectorRuntimeNode root = glossRoot();

        Assertions.assertEquals("gloss", root.getDescriptor().getName());
        Assertions.assertFalse(root.getChildren().isEmpty());
    }

    @Test
    void everyExpectedPathExists() {
        DirectorRuntimeNode root = glossRoot();

        for (List<String> path : EXPECTED_PATHS) {
            DirectorRuntimeNode cursor = root;
            for (String token : path) {
                cursor = findExactChild(cursor, token);
                Assertions.assertNotNull(cursor, "Missing Director token '" + token + "' in path " + path);
            }
        }
    }

    @Test
    void everyLeafPathIsInvocable() {
        DirectorRuntimeNode root = glossRoot();

        for (List<String> path : EXPECTED_PATHS) {
            DirectorRuntimeNode cursor = root;
            for (String token : path) {
                cursor = findExactChild(cursor, token);
            }
            boolean group = path.size() == 1 && GROUP_ROOTS.contains(path.get(0));
            Assertions.assertEquals(!group, cursor.isInvocable(), "Wrong invocability for path " + path);
        }
    }

    @Test
    void everyNodeDescriptionKeyResolvesAgainstCatalog() {
        MessageCatalog catalog = GlossMessages.catalog();
        assertNodeLanguage(glossRoot(), catalog);
    }

    @Test
    void everyOptionalParameterCarriesDefaultValue() {
        assertOptionalDefaults(glossRoot());
    }

    @Test
    void everyHelpKeyResolvesToItsEnglishText() {
        MessageCatalog catalog = GlossMessages.catalog();
        for (MessageKey key : catalog.keys()) {
            if (!key.id().startsWith("command.help.")) {
                continue;
            }

            Assertions.assertInstanceOf(TextKey.class, key, key.id());
            TextKey textKey = (TextKey) key;
            String resolved = GlossLocalization.globalDirectorText(textKey, MessageArgs.empty());
            Assertions.assertEquals(textKey.english(), resolved, key.id());
        }
    }

    private static DirectorRuntimeNode glossRoot() {
        DirectorRuntimeEngine engine = DirectorEngineFactory.create(new CommandGloss(null));
        return engine.getRoot();
    }

    private static void assertNodeLanguage(DirectorRuntimeNode node, MessageCatalog catalog) {
        String descriptionKey = node.getDescriptor().getDescriptionKey();
        Assertions.assertFalse(descriptionKey.isBlank(), "Missing descriptionKey on " + node.path());
        Assertions.assertTrue(descriptionKey.startsWith("command.help."), "Bad descriptionKey on " + node.path());
        assertCatalogText(catalog, descriptionKey, node.getDescriptor().getDescription(), node.path());

        for (DirectorParameterDescriptor parameter : node.getDescriptor().getParameters()) {
            if (parameter.isContextual()) {
                continue;
            }

            String parameterKey = parameter.getDescriptionKey();
            Assertions.assertFalse(parameterKey.isBlank(),
                    "Missing descriptionKey on parameter " + parameter.getName() + " of " + node.path());
            Assertions.assertTrue(parameterKey.startsWith("command.help."),
                    "Bad descriptionKey on parameter " + parameter.getName() + " of " + node.path());
            assertCatalogText(catalog, parameterKey, parameter.getDescription(), node.path() + " " + parameter.getName());
        }

        for (DirectorRuntimeNode child : node.getChildren()) {
            assertNodeLanguage(child, catalog);
        }
    }

    private static void assertCatalogText(MessageCatalog catalog, String id, String english, String context) {
        MessageKey key = catalog.key(id);
        Assertions.assertNotNull(key, "Catalog is missing key '" + id + "' for " + context);
        Assertions.assertInstanceOf(TextKey.class, key, id);
        Assertions.assertEquals(((TextKey) key).english(), english,
                "Catalog english drifted from annotation description for '" + id + "' at " + context);
    }

    private static void assertOptionalDefaults(DirectorRuntimeNode node) {
        for (DirectorParameterDescriptor parameter : node.getDescriptor().getParameters()) {
            if (parameter.isContextual() || parameter.isRequired()) {
                continue;
            }

            Assertions.assertFalse(parameter.getDefaultValue() == null || parameter.getDefaultValue().isBlank(),
                    "Optional parameter " + parameter.getName() + " of " + node.path() + " has no defaultValue");
        }

        for (DirectorRuntimeNode child : node.getChildren()) {
            assertOptionalDefaults(child);
        }
    }

    private static DirectorRuntimeNode findExactChild(DirectorRuntimeNode node, String token) {
        for (DirectorRuntimeNode child : node.getChildren()) {
            for (String name : child.allNames()) {
                if (name.equalsIgnoreCase(token)) {
                    return child;
                }
            }
        }

        return null;
    }
}
