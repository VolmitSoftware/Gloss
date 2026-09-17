package art.arcane.gloss.command;

import art.arcane.gloss.locale.GlossLocalization;
import art.arcane.gloss.locale.GlossMessages;
import art.arcane.volmlib.util.director.compat.DirectorEngineFactory;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
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
            List.of("debug"),
            List.of("debug", "dump"),
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
            List.of("hologram", "orient"),
            List.of("hologram", "page"),
            List.of("hologram", "motion"),
            List.of("hologram", "actions"),
            List.of("board"),
            List.of("board", "create"),
            List.of("board", "delete"),
            List.of("board", "title"),
            List.of("board", "addline"),
            List.of("board", "setline"),
            List.of("board", "removeline"),
            List.of("board", "show"),
            List.of("board", "hide"),
            List.of("board", "select"),
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
            List.of("drops"),
            List.of("drops", "reset"),
            List.of("indicators"),
            List.of("indicators", "reset"),
            List.of("status"),
            List.of("menu"),
            List.of("menu", "list"),
            List.of("menu", "create"),
            List.of("menu", "open"),
            List.of("menu", "back"),
            List.of("menu", "close"),
            List.of("menu", "move"),
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
            List.of("panel", "save"),
            List.of("panel", "cancel"),
            List.of("dialog"),
            List.of("dialog", "list"),
            List.of("dialog", "info"),
            List.of("dialog", "open"),
            List.of("dialog", "reset"),
            List.of("inventory"),
            List.of("inventory", "list"),
            List.of("inventory", "info"),
            List.of("inventory", "open"),
            List.of("inventory", "reset"),
            List.of("preview"),
            List.of("preview", "list"),
            List.of("preview", "reset"),
            List.of("preview", "dump"),
            List.of("item"),
            List.of("item", "status"),
            List.of("item", "export"),
            List.of("web"),
            List.of("web", "open"),
            List.of("web", "workspace"),
            List.of("web", "edit"),
            List.of("web", "edit", "menu"),
            List.of("web", "edit", "panel"),
            List.of("web", "edit", "hologram"),
            List.of("web", "edit", "scoreboard"),
            List.of("web", "edit", "emoji"),
            List.of("web", "edit", "animation"),
            List.of("web", "edit", "bubble-style"),
            List.of("web", "edit", "container-preview"),
            List.of("web", "edit", "tablist"),
            List.of("web", "edit", "motd"),
            List.of("web", "edit", "real-drops"),
            List.of("web", "edit", "damage-indicators"),
            List.of("web", "edit", "dialog"),
            List.of("web", "edit", "inventory"),
            List.of("web", "sessions"),
            List.of("web", "sessions", "list"),
            List.of("web", "sessions", "status"),
            List.of("web", "sessions", "revoke"),
            List.of("web", "sessions", "pull"),
            List.of("import"),
            List.of("import", "preview"),
            List.of("import", "apply"),
            List.of("import", "holoui"),
            List.of("import", "legacy"),
            List.of("surface"),
            List.of("surface", "list"),
            List.of("surface", "info"),
            List.of("surface", "reset"),
            List.of("surface", "test"),
            List.of("nametag"),
            List.of("nametag", "list"),
            List.of("nametag", "info"),
            List.of("nametag", "reset"),
            List.of("nametag", "refresh"),
            List.of("hud"),
            List.of("hud", "who"),
            List.of("web", "edit", "surface"),
            List.of("web", "edit", "nametag"),
            List.of("forge"),
            List.of("forge", "build"),
            List.of("forge", "status"),
            List.of("forge", "export"),
            List.of("forge", "serve"),
            List.of("forge", "reset"),
            List.of("web", "edit", "glyph"),
            List.of("web", "edit", "connections"),
            List.of("channel"),
            List.of("channel", "list"),
            List.of("channel", "info"),
            List.of("channel", "reset"),
            List.of("strings"),
            List.of("strings", "list"),
            List.of("strings", "missing"),
            List.of("strings", "reset"),
            List.of("leaderboard"),
            List.of("leaderboard", "list"),
            List.of("leaderboard", "info"),
            List.of("leaderboard", "reset"),
            List.of("leaderboard", "sample"),
            List.of("web", "edit", "channel"),
            List.of("web", "edit", "strings"),
            List.of("web", "edit", "leaderboard"),
            List.of("rig"),
            List.of("rig", "list"),
            List.of("rig", "info"),
            List.of("rig", "place"),
            List.of("rig", "move"),
            List.of("rig", "here"),
            List.of("rig", "rotate"),
            List.of("rig", "scale"),
            List.of("rig", "remove"),
            List.of("rig", "state"),
            List.of("rig", "var"),
            List.of("rig", "import"),
            List.of("rig", "reset"),
            List.of("motion"),
            List.of("motion", "list"),
            List.of("motion", "info"),
            List.of("motion", "reset"),
            List.of("web", "edit", "motion"),
            List.of("web", "edit", "rig"),
            List.of("web", "edit", "rig-instance"),
            List.of("web", "edit", "behavior"),
            List.of("behavior"),
            List.of("behavior", "list"),
            List.of("behavior", "info"),
            List.of("behavior", "fire"),
            List.of("behavior", "reset"),
            List.of("state"),
            List.of("state", "get"),
            List.of("state", "set"),
            List.of("state", "clear"),
            List.of("state", "dump"),
            List.of("do"),
            List.of("expr"),
            List.of("explain"),
            List.of("marker"),
            List.of("marker", "list"),
            List.of("marker", "info"),
            List.of("marker", "create"),
            List.of("marker", "remove"),
            List.of("marker", "here"),
            List.of("waypoint"),
            List.of("waypoint", "set"),
            List.of("waypoint", "remove"),
            List.of("waypoint", "list"),
            List.of("waypoint", "info"),
            List.of("zone"),
            List.of("zone", "list"),
            List.of("zone", "info"),
            List.of("zone", "show"),
            List.of("zone", "hide"),
            List.of("zone", "create"),
            List.of("zone", "remove"),
            List.of("camera"),
            List.of("camera", "test"),
            List.of("camera", "stop"),
            List.of("nameplate"),
            List.of("nameplate", "list"),
            List.of("nameplate", "info"),
            List.of("nameplate", "reset"),
            List.of("nameplate", "refresh"),
            List.of("glow"),
            List.of("glow", "set"),
            List.of("glow", "clear"),
            List.of("web", "edit", "marker"),
            List.of("web", "edit", "waypoint"),
            List.of("web", "edit", "zone"),
            List.of("web", "edit", "nameplate"),
            List.of("pack"),
            List.of("pack", "install"),
            List.of("pack", "update"),
            List.of("pack", "remove"),
            List.of("pack", "list"),
            List.of("pack", "info"),
            List.of("history"),
            List.of("history", "list"),
            List.of("restore"),
            List.of("restore", "document"),
            List.of("check"),
            List.of("check", "workspace"),
            List.of("export"),
            List.of("export", "documents"),
            List.of("export", "bundle")
    );
    private static final Set<List<String>> GROUP_PATHS = Set.of(
            List.of("debug"),
            List.of("hologram"), List.of("board"), List.of("emoji"), List.of("animations"),
            List.of("bubbles"), List.of("tablist"), List.of("motd"), List.of("drops"),
            List.of("indicators"),
            List.of("menu"), List.of("panel"), List.of("preview"), List.of("item"),
            List.of("web"), List.of("web", "edit"), List.of("web", "sessions"), List.of("import"),
            List.of("surface"), List.of("nametag"), List.of("hud"),
            List.of("forge"),
            List.of("channel"), List.of("strings"), List.of("leaderboard"),
            List.of("dialog"), List.of("inventory"),
            List.of("rig"), List.of("motion"),
            List.of("behavior"), List.of("state"),
            List.of("marker"), List.of("waypoint"), List.of("zone"), List.of("camera"),
            List.of("nameplate"), List.of("glow"),
            List.of("pack"), List.of("history"), List.of("restore"), List.of("check"),
            List.of("export")
    );

    @Test
    void engineBuildsWithoutRunningServer() {
        DirectorRuntimeNode root = glossRoot();

        Assertions.assertEquals("gloss", root.getDescriptor().getName());
        Assertions.assertFalse(root.getChildren().isEmpty());
        Assertions.assertNull(findExactChild(root, "reload"));
        Assertions.assertNull(findExactChild(findExactChild(root, "panel"), "reload"));
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
            boolean group = GROUP_PATHS.contains(path);
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

    @Test
    void rootAndSubmenuHelpUseCurrentEntryBudget() {
        DirectorRuntimeEngine engine = DirectorEngineFactory.create(new CommandGloss(null));
        DirectorMiniMenu.DirectorHelpPage root = DirectorMiniMenu.resolveHelp(
                engine, List.of()).orElseThrow();
        DirectorMiniMenu.DirectorHelpPage submenu = DirectorMiniMenu.resolveHelp(
                engine, List.of("panel")).orElseThrow();

        int rootChildren = engine.getRoot().getChildren().size();
        int expectedRootEntries = Math.min(rootChildren, DirectorMiniMenu.MAX_ENTRIES_PER_PAGE);

        // A root wider than one page still renders one banner and one pagination bar; the page
        // indicator lives inside the banner rather than on a line of its own.,
        int rootChrome = 2;
        Assertions.assertEquals(expectedRootEntries, root.entries().size());
        Assertions.assertEquals(expectedRootEntries + rootChrome, DirectorMiniMenu.render(
                root, GlossCommandService.menuTheme(), GlossLocalization.globalDirectorResolver()).size());
        Assertions.assertEquals(DirectorMiniMenu.MAX_ENTRIES_PER_PAGE, submenu.entries().size());
        Assertions.assertEquals(DirectorMiniMenu.MAX_ENTRIES_PER_PAGE + 3, DirectorMiniMenu.render(
                submenu, GlossCommandService.menuTheme(), GlossLocalization.globalDirectorResolver()).size());
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
