package art.arcane.gloss.importer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GholoCmiHologramImportTest {
  private static final UUID WORLD_UUID = UUID.fromString("00000000-0000-0000-0000-000000000802");

  @Rule
  public final TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void gholoDatabaseExportsAnimationsSymbolsAndInteractionsImport() throws Exception {
    Path plugins = temp.newFolder("plugins").toPath();
    Path root = plugins.resolve("GHolo");
    Files.createDirectories(root.resolve("data"));
    Files.createDirectories(root.resolve("holos"));
    Files.createDirectories(root.resolve("interactions"));
    Files.writeString(root.resolve("config.yml"), """
        Options:
          Symbols:
            "[X]": "\\u2588"
        """);
    Files.writeString(root.resolve("animations.yml"), """
        Animations:
          pulse:
            ticks: 2
            content: ['A', "B's"]
          Outer:
            ticks: 4
            content: ['x%pulse%y', 'z']
        """);
    Files.writeString(root.resolve("data/data.yml"), "Database:\n  type: sqlite\n");
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("data/data.db"));
         Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE gholo_holo (uuid TEXT, id TEXT, location TEXT, data TEXT)");
      statement.execute("CREATE TABLE gholo_holo_row (position INTEGER, holo_uuid TEXT, content TEXT, "
          + "`offset` TEXT, data TEXT)");
      statement.execute("CREATE TABLE gholo_interaction (uuid TEXT, id TEXT, location TEXT, data TEXT)");
      statement.execute("CREATE TABLE gholo_interaction_action (position INTEGER, interaction_uuid TEXT, "
          + "type TEXT, parameter TEXT)");
      statement.execute("INSERT INTO gholo_holo VALUES ('h1', 'spawn', "
          + "'{\"world\":\"world\",\"x\":1.0,\"y\":64.0,\"z\":2.0}', "
          + "'{\"range\":48.0,\"background_color\":\"#11223344\"}')");
      statement.execute("INSERT INTO gholo_holo_row VALUES (1, 'h1', 'item:minecraft:diamond', '{}', NULL)");
      statement.execute("INSERT INTO gholo_holo_row VALUES (0, 'h1', '[X] %outer% %player_name%', "
          + "'{\"y\":0.5}', '{}')");
      statement.execute("INSERT INTO gholo_interaction VALUES ('i1', 'door', "
          + "'{\"world\":\"world\",\"x\":3.0,\"y\":64.0,\"z\":4.0}', "
          + "'{\"permission\":\"door.use\",\"size\":{\"width\":2.0,\"height\":3.0}}')");
      try (PreparedStatement insert = connection.prepareStatement(
          "INSERT INTO gholo_interaction_action VALUES (?, 'i1', ?, ?)")) {
        String[][] actions = {
            {"message", "<green>Hi"}, {"server_command", "say hi"}, {"teleport", "world:5:65:6:90:0"},
            {"teleport", "world:~:~1:~"}, {"sound", "x"}};
        for (int index = 0; index < actions.length; index++) {
          insert.setInt(1, index);
          insert.setString(2, actions[index][0]);
          insert.setString(3, actions[index][1]);
          insert.executeUpdate();
        }
      }
    }
    Files.writeString(root.resolve("holos/spawn.yml"), """
        Holo:
          location: {world: world, x: 0, y: 64, z: 0}
          rows:
            - content: Exported copy
        """);
    Files.writeString(root.resolve("interactions/gate.yml"), """
        Interaction:
          location: {world: world, x: 7, y: 64, z: 8}
          actions:
            - type: connect
              parameter: lobby
        """);

    LegacyHologramScanner.LegacyScanResult scan = new LegacyHologramScanner().scan(
        LegacyImportSource.GHOLO, root, plugins);

    assertEquals(List.of("spawn", "interaction-door", "interaction-gate"),
        scan.drafts().stream().map(LegacyHologramDraft::legacyId).toList());
    assertTrue(scan.issues().toString(), scan.issues().stream()
        .anyMatch(issue -> issue.message().contains("export file repeats spawn")));
    LegacyHologramDraft spawn = scan.drafts().getFirst();
    assertEquals(48.0D, spawn.viewRange(), 0.0D);
    assertEquals("█ {{ select(['x' + select(['A', 'B\\'s'], floor(time.ms / 100)) + 'y', 'z'], "
        + "floor(time.ms / 200)) }} %player_name%", spawn.rows().get(0).text());
    assertEquals(0.5D, spawn.rows().get(0).y(), 0.0D);
    assertEquals("item:minecraft:diamond", spawn.rows().get(1).text());

    LegacyHologramConverter.ConversionResult conversion = new LegacyHologramConverter().convert(
        LegacyImportSource.GHOLO, scan.drafts(), worlds());
    assertTrue(conversion.issues().toString(), conversion.issues().isEmpty());
    LegacyImportCandidate door = conversion.candidates().get(1);
    assertEquals("imports/gholo/interaction-door", door.boardId());
    assertEquals("door.use", door.board().visibility().viewPermission());
    JsonObject button = component(door, "interaction");
    JsonObject data = button.getAsJsonObject("data");
    assertEquals("button", data.get("type").getAsString());
    assertEquals(2.0D, data.getAsJsonObject("hitbox").get("width").getAsDouble(), 0.0D);
    assertEquals(3.0D, data.getAsJsonObject("hitbox").get("height").getAsDouble(), 0.0D);
    assertEquals(1.5D, button.getAsJsonArray("offset").get(1).getAsDouble(), 0.000001D);
    JsonArray actions = data.getAsJsonArray("actions");
    assertEquals(3, actions.size());
    assertEquals("message", actions.get(0).getAsJsonObject().get("type").getAsString());
    assertEquals("server", actions.get(1).getAsJsonObject().get("source").getAsString());
    assertEquals("say hi", actions.get(1).getAsJsonObject().get("command").getAsString());
    JsonObject teleport = actions.get(2).getAsJsonObject();
    assertEquals("minecraft:overworld", teleport.get("world").getAsString());
    assertEquals(90.0D, teleport.get("yaw").getAsDouble(), 0.0D);
    assertTrue(door.warnings().toString(), door.warnings().stream()
        .anyMatch(warning -> warning.contains("Relative teleport")));
    assertTrue(door.warnings().toString(), door.warnings().stream()
        .anyMatch(warning -> warning.contains("'sound'")));
    JsonObject gate = component(conversion.candidates().get(2), "interaction");
    assertEquals("lobby", gate.getAsJsonObject("data").getAsJsonArray("actions").get(0)
        .getAsJsonObject().get("server").getAsString());
  }

  @Test
  public void gholoMysqlStorageIsReportedAndExportFilesStillImport() throws Exception {
    Path plugins = temp.newFolder("mysql").toPath();
    Path root = plugins.resolve("GHolo");
    Files.createDirectories(root.resolve("data"));
    Files.createDirectories(root.resolve("holos"));
    Files.writeString(root.resolve("data/data.yml"), "Database:\n  type: MySQL\n");
    Files.writeString(root.resolve("holos/info.yml"), """
        Holo:
          location: {world: world, x: 0, y: 64, z: 0}
          rows:
            - content: Info
        """);

    LegacyHologramScanner.LegacyScanResult scan = new LegacyHologramScanner().scan(
        LegacyImportSource.GHOLO, root, plugins);

    assertEquals(1, scan.drafts().size());
    assertEquals(LegacyImportIssue.Severity.ERROR, scan.issues().getFirst().severity());
    assertTrue(scan.issues().getFirst().message().contains("MySQL"));
  }

  @Test
  public void cmiHologramsImportTextIconsStyleAndCommands() throws Exception {
    Path plugins = temp.newFolder("cmi").toPath();
    Path file = plugins.resolve("CMI/Saves/Holograms.yml");
    Files.createDirectories(file.getParent());
    Files.writeString(plugins.resolve("CMI/config.yml"), """
        Holograms:
          Defaults:
            viewRange: 24
        """);
    Files.writeString(file, """
        welcome:
          Loc: world;10,5;70;-4
          Settings:
            Bill: FIXED
            Dir: 10;90;0
            Scale: 2;2
            Perm: true
            Range:
              Visual: 40
          Text:
            BColor: '{#112233}'
            BAlpha: 0
            Shadow: false
            Align: LEFT
          Lines:
          - '{#ff0000}Hello [playerName]'
          - 'ICON:DIAMOND_SWORD-1%enchanted%'
          - '<T>Click</T><C>asPlayer! spawn</C>'
          - '!nextpage!'
          - 'Second page'
          Interaction:
            Enabled: true
            Commands:
            - 'asPlayer! warp spawn'
            - 'click:right! asConsole! give [playerName] diamond 1'
            - 'delay! 5'
        off:
          Loc: world;0;64;0
          Enabled: false
          Lines: ['x']
        legacy:
          Loc: world;1;65;1
          ND:
            Bill: CENTER
            BColor: '{#Black}'
            BAlpha: 120
            ScaleW: 0.5
            ScaleH: 0.5
          Lines:
          - 'Old'
          Interactable: true
          Commands:
          - 'asPlayer! kits'
        """);

    LegacyHologramScanner.LegacyScanResult scan = new LegacyHologramScanner().scan(
        LegacyImportSource.CMI_HOLOGRAMS, file, plugins);

    assertEquals(List.of("legacy", "welcome"),
        scan.drafts().stream().map(LegacyHologramDraft::legacyId).toList());
    assertTrue(scan.issues().stream().anyMatch(issue -> issue.message().contains("disabled")));
    LegacyHologramDraft legacy = scan.drafts().getFirst();
    assertEquals(24.0D, legacy.viewRange(), 0.0D);
    assertEquals("#00000078", legacy.style().background());
    assertEquals(0.5D, legacy.style().scaleY(), 0.0D);
    assertEquals(1, legacy.interaction().actions().size());

    LegacyHologramDraft welcome = scan.drafts().get(1);
    assertEquals(10.5D, welcome.location().x(), 0.0D);
    assertEquals(90.0D, welcome.location().yaw(), 0.0D);
    assertEquals(10.0D, welcome.location().pitch(), 0.0D);
    assertEquals(40.0D, welcome.viewRange(), 0.0D);
    assertEquals("cmi.hologram.welcome", welcome.permission());
    assertEquals("#11223300", welcome.style().background());
    assertEquals(Boolean.FALSE, welcome.style().textShadow());
    assertEquals("left", welcome.style().textAlignment());
    assertEquals("fixed", welcome.style().billboard());
    assertEquals(List.of("[ff0000]Hello {{ player.name }}", "item:minecraft:diamond_sword", "Click"),
        welcome.rows().stream().map(LegacyHologramDraft.LegacyRow::text).toList());
    assertEquals(1.0D, welcome.rows().get(0).y(), 0.0D);
    assertEquals(0.75D, welcome.rows().get(1).y(), 0.0D);
    assertEquals(0.0D, welcome.rows().get(2).y(), 0.0D);
    LegacyHologramDraft.LegacyInteraction interaction = welcome.interaction();
    assertEquals(1.5D, interaction.height(), 0.0D);
    assertEquals(0.75D, interaction.y(), 0.0D);
    assertEquals(2, interaction.actions().size());
    assertEquals(LegacyHologramDraft.LegacyAction.Kind.PLAYER_COMMAND, interaction.actions().get(0).kind());
    assertEquals("warp spawn", interaction.actions().get(0).value());
    assertNull(interaction.actions().get(0).trigger());
    assertEquals(LegacyHologramDraft.LegacyAction.Kind.SERVER_COMMAND, interaction.actions().get(1).kind());
    assertEquals("give %player% diamond 1", interaction.actions().get(1).value());
    assertEquals("right_click", interaction.actions().get(1).trigger());
    for (String expected : List.of("first page", "clickable text", "icon flags", "CMI-only syntax")) {
      assertTrue(expected, welcome.warnings().stream().anyMatch(warning -> warning.contains(expected)));
    }

    LegacyHologramConverter.ConversionResult conversion = new LegacyHologramConverter().convert(
        LegacyImportSource.CMI_HOLOGRAMS, scan.drafts(), worlds());
    assertTrue(conversion.issues().toString(), conversion.issues().isEmpty());
    LegacyImportCandidate candidate = conversion.candidates().get(1);
    assertEquals("imports/cmi/welcome", candidate.boardId());
    JsonArray actions = component(candidate, "interaction").getAsJsonObject("data").getAsJsonArray("actions");
    assertEquals("player", actions.get(0).getAsJsonObject().get("source").getAsString());
    assertEquals("right_click", actions.get(1).getAsJsonObject().get("trigger").getAsString());
  }

  private static JsonObject component(LegacyImportCandidate candidate, String id) {
    JsonArray components = JsonParser.parseString(candidate.menuSource()).getAsJsonObject()
        .getAsJsonArray("components");
    for (int index = 0; index < components.size(); index++) {
      JsonObject component = components.get(index).getAsJsonObject();
      if (component.get("id").getAsString().equals(id)) {
        return component;
      }
    }
    throw new AssertionError("component " + id + " is missing");
  }

  private static LegacyWorldCatalog worlds() {
    return LegacyWorldCatalog.of(new LegacyWorldCatalog.WorldDescriptor(
        "world", "minecraft:overworld", WORLD_UUID));
  }
}
