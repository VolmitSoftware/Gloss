package art.arcane.gloss.command;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rebuilt from HoloUi's {@code HoloUiPermissionDeclarationTest} for the merged plugin: every
 * permission string the code checks is declared in BOTH descriptors, every declared node is
 * actually checked somewhere, and dynamic per-content nodes stay undeclared.
 *
 * <p>Static permission literals are complete {@code "gloss.a.b"} strings; dynamic prefixes end
 * with a dot ({@code "gloss.open." + id}) and therefore never match the literal scan.
 */
class GlossPermissionDeclarationTest {

  /** Nodes that exist purely as grouping parents in the descriptor tree. */
  private static final Set<String> STRUCTURAL_NODES = Set.of("gloss.*");

  /** Dynamic per-content prefixes that must never be declared as bare parents. */
  private static final Set<String> DYNAMIC_PREFIXES = Set.of("gloss.open.", "gloss.emoji.", "gloss.bubbles.style.");

  /** The only nodes servers grant to everyone by default. */
  private static final Set<String> PLAYER_DEFAULT_NODES = Set.of(
      "gloss.emoji.use", "gloss.bubbles.send", "gloss.indicators.show");

  private static final Set<String> NON_PERMISSION_LITERALS = Set.of("gloss.toml");

  private static final Pattern PERMISSION_LITERAL = Pattern.compile("\"(gloss(?:\\.[a-z]+)+)\"");

  @Test
  void bothDescriptorsDeclareTheSamePermissionTree() throws IOException {
    assertEquals(tree(permissions("/plugin.yml")), tree(permissions("/paper-plugin.yml")));
  }

  @Test
  void everyPermissionStringUsedInCodeIsDeclared() throws IOException {
    ConfigurationSection permissions = permissions("/plugin.yml");
    for (String used : usedPermissions()) {
      assertTrue(permissions.contains(used), "code checks undeclared permission " + used);
    }
  }

  @Test
  void everyDeclaredNodeIsCheckedByCode() throws IOException {
    ConfigurationSection permissions = permissions("/plugin.yml");
    Set<String> used = usedPermissions();
    for (String declared : permissions.getKeys(false)) {
      if (STRUCTURAL_NODES.contains(declared)) {
        continue;
      }
      assertTrue(used.contains(declared), "declared node " + declared + " is checked by no code path");
    }
  }

  @Test
  void dynamicPerContentNodesStayUndeclared() throws IOException {
    ConfigurationSection permissions = permissions("/plugin.yml");
    assertFalse(permissions.contains("gloss.open"), "gloss.open is checked by no code path");
    Set<String> used = usedPermissions();
    for (String declared : permissions.getKeys(false)) {
      if (used.contains(declared)) {
        // statically checked nodes may share a dynamic namespace (gloss.emoji.use, gloss.emoji.reset)
        continue;
      }
      for (String prefix : DYNAMIC_PREFIXES) {
        assertFalse(declared.startsWith(prefix),
            "dynamic node " + declared + " must not be declared");
      }
    }
  }

  @Test
  void everyNodeDefaultsToOpExceptThePlayerBaseline() throws IOException {
    ConfigurationSection permissions = permissions("/plugin.yml");
    for (String node : permissions.getKeys(false)) {
      ConfigurationSection section = permissions.getConfigurationSection(node);
      assertNotNull(section, node);
      assertFalse(section.getString("description", "").isBlank(), node + " needs a description");
      String expected = PLAYER_DEFAULT_NODES.contains(node) ? "true" : "op";
      assertEquals(expected, section.getString("default"), node);
    }
  }

  @Test
  void everyDeclaredChildIsItselfDeclared() throws IOException {
    ConfigurationSection permissions = permissions("/plugin.yml");
    for (String node : permissions.getKeys(false)) {
      ConfigurationSection children = permissions.getConfigurationSection(node).getConfigurationSection("children");
      if (children == null) {
        continue;
      }
      for (String child : children.getKeys(false)) {
        assertTrue(permissions.contains(child), node + " lists undeclared child " + child);
      }
    }
  }

  private static Set<String> usedPermissions() throws IOException {
    Path sources = Path.of(System.getProperty("user.dir"), "src", "main", "java");
    assertTrue(Files.isDirectory(sources), "main sources missing at " + sources);
    Path catalogPackage = sources.resolve(Path.of("art", "arcane", "gloss", "locale"));
    Set<String> used = new TreeSet<>();
    try (Stream<Path> files = Files.walk(sources)) {
      for (Path file : files
          .filter(path -> path.toString().endsWith(".java"))
          // The message catalog's gloss.* ids share the permission namespace shape but are
          // localization keys, not permission checks.
          .filter(path -> !path.startsWith(catalogPackage))
          .toList()) {
        Matcher matcher = PERMISSION_LITERAL.matcher(Files.readString(file, StandardCharsets.UTF_8));
        while (matcher.find()) {
          String literal = matcher.group(1);
          if (!NON_PERMISSION_LITERALS.contains(literal)) {
            used.add(literal);
          }
        }
      }
    }
    assertFalse(used.isEmpty(), "the permission literal scan found nothing");
    return used;
  }

  private static Map<String, Map<String, Object>> tree(ConfigurationSection permissions) {
    Map<String, Map<String, Object>> tree = new TreeMap<>();
    for (String node : permissions.getKeys(false)) {
      ConfigurationSection section = permissions.getConfigurationSection(node);
      Map<String, Object> entry = new TreeMap<>();
      entry.put("default", section.getString("default"));
      ConfigurationSection children = section.getConfigurationSection("children");
      entry.put("children", children == null ? Set.of() : new TreeSet<>(children.getKeys(false)));
      tree.put(node, entry);
    }
    return tree;
  }

  private static ConfigurationSection permissions(String resource) throws IOException {
    YamlConfiguration config = new YamlConfiguration();
    config.options().pathSeparator(Character.MIN_VALUE);

    try (InputStream stream = GlossPermissionDeclarationTest.class.getResourceAsStream(resource)) {
      assertNotNull(stream, resource + " missing from the resource output");
      try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
        config.load(reader);
      } catch (InvalidConfigurationException e) {
        throw new IOException(e);
      }
    }

    ConfigurationSection permissions = config.getConfigurationSection("permissions");
    assertNotNull(permissions, resource + " declares no permissions");
    return permissions;
  }
}
