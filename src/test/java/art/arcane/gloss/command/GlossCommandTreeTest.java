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
            List.of("group"),
            List.of("group", "list"),
            List.of("group", "info"),
            List.of("emoji"),
            List.of("animations"),
            List.of("status"),
            List.of("reload"),
            List.of("version")
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
            boolean group = path.size() == 1
                    && (path.get(0).equals("hologram") || path.get(0).equals("board") || path.get(0).equals("group"));
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
            String resolved = GlossLocalization.english().directorText(textKey, MessageArgs.empty());
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
