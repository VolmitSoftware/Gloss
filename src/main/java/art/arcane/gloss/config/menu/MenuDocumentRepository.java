package art.arcane.gloss.config.menu;

import art.arcane.gloss.doc.AtomicFiles;
import art.arcane.gloss.doc.DocumentRevisionConflictException;
import art.arcane.volmlib.util.bukkit.json.BukkitJson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.UnaryOperator;

final class MenuDocumentRepository {
  private static final String JSON_EXTENSION = ".json";
  private static final String NOUN = "menu";

  private final Path directory;

  MenuDocumentRepository(File pluginDataDirectory) {
    this(Objects.requireNonNull(pluginDataDirectory, "pluginDataDirectory").toPath());
  }

  MenuDocumentRepository(Path pluginDataDirectory) {
    Path dataDirectory = Objects.requireNonNull(pluginDataDirectory, "pluginDataDirectory")
        .toAbsolutePath().normalize();
    this.directory = dataDirectory.resolve("menus").normalize();
  }

  Path directory() {
    return directory;
  }

  MenuDocument mutate(String id, String expectedRevision,
                      UnaryOperator<JsonObject> mutation) throws IOException {
    String menuId = MenuIds.require(id);
    String requiredRevision = Objects.requireNonNull(expectedRevision, "expectedRevision");
    UnaryOperator<JsonObject> requiredMutation = Objects.requireNonNull(mutation, "mutation");
    Path target = path(menuId);
    byte[] originalBytes = readRegularFile(target);
    String originalSource = new String(originalBytes, StandardCharsets.UTF_8);
    String actualRevision = MenuDocument.revisionOf(originalSource);
    requireRevision(menuId, requiredRevision, actualRevision);

    JsonElement parsed = JsonParser.parseString(originalSource);
    if (!parsed.isJsonObject()) {
      throw new IllegalArgumentException("menu document must be a JSON object");
    }
    JsonObject changed = Objects.requireNonNull(
        requiredMutation.apply(parsed.getAsJsonObject().deepCopy()), "menu mutation result");
    String changedSource = BukkitJson.GSON.toJson(changed) + System.lineSeparator();
    MenuDocument validated = MenuDocumentParser.parse(menuId, changedSource);

    if (changed.equals(parsed)) {
      return MenuDocumentParser.parse(menuId, originalSource);
    }

    prepareParent(target);
    writeReplacement(menuId, target, originalBytes, requiredRevision, changedSource);
    return readPersisted(menuId, target, validated.revision());
  }

  MenuDocument copy(String sourceId, String expectedRevision, String targetId) throws IOException {
    String sourceMenuId = MenuIds.require(sourceId);
    String targetMenuId = MenuIds.require(targetId);
    if (sourceMenuId.equals(targetMenuId)) {
      throw new IllegalArgumentException("new menu id must differ from the source menu id");
    }
    String requiredRevision = Objects.requireNonNull(expectedRevision, "expectedRevision");
    Path source = path(sourceMenuId);
    Path target = path(targetMenuId);
    byte[] originalBytes = readRegularFile(source);
    String originalSource = new String(originalBytes, StandardCharsets.UTF_8);
    String actualRevision = MenuDocument.revisionOf(originalSource);
    requireRevision(sourceMenuId, requiredRevision, actualRevision);
    JsonElement parsed = JsonParser.parseString(originalSource);
    if (!parsed.isJsonObject()) {
      throw new IllegalArgumentException("menu document must be a JSON object");
    }
    String copiedSource = BukkitJson.GSON.toJson(parsed) + System.lineSeparator();
    MenuDocument validated = MenuDocumentParser.parse(targetMenuId, copiedSource);

    prepareParent(target);
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      throw new FileAlreadyExistsException(targetMenuId);
    }
    Path temporary = writeTemporary(target, copiedSource);
    try {
      requireSourceRevision(sourceMenuId, source, requiredRevision);
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        throw new FileAlreadyExistsException(targetMenuId);
      }
      publishNewFile(temporary, target);
      AtomicFiles.forceDirectory(target.getParent());
    } finally {
      Files.deleteIfExists(temporary);
    }
    return readPersisted(targetMenuId, target, validated.revision());
  }

  MenuDocument create(String id, String source) throws IOException {
    String menuId = MenuIds.require(id);
    if (source == null || source.isEmpty()) {
      throw new IllegalArgumentException("menu document must not be empty");
    }
    String normalizedSource = source.endsWith(System.lineSeparator())
        ? source
        : source + System.lineSeparator();
    MenuDocument validated = MenuDocumentParser.parse(menuId, normalizedSource);
    Path target = path(menuId);

    prepareParent(target);
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      throw new FileAlreadyExistsException(menuId);
    }
    Path temporary = writeTemporary(target, normalizedSource);
    try {
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        throw new FileAlreadyExistsException(menuId);
      }
      publishNewFile(temporary, target);
      AtomicFiles.forceDirectory(target.getParent());
    } finally {
      Files.deleteIfExists(temporary);
    }
    return readPersisted(menuId, target, validated.revision());
  }

  private void writeReplacement(String menuId, Path target, byte[] originalBytes,
                                String expectedRevision, String source) throws IOException {
    Path temporary = writeTemporary(target, source);
    try {
      byte[] latestBytes = readRegularFile(target);
      String latestRevision = MenuDocument.revisionOf(new String(latestBytes, StandardCharsets.UTF_8));
      requireRevision(menuId, expectedRevision, latestRevision);
      if (!Arrays.equals(originalBytes, latestBytes)) {
        throw new DocumentRevisionConflictException(NOUN, menuId, expectedRevision, latestRevision);
      }
      Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      AtomicFiles.forceDirectory(target.getParent());
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private Path writeTemporary(Path target, String source) throws IOException {
    Path parent = target.getParent();
    if (parent == null) {
      throw new IOException("menu file has no parent directory: " + target);
    }
    return AtomicFiles.writeDurableTemporary(parent, "." + target.getFileName() + ".",
        source.getBytes(StandardCharsets.UTF_8));
  }

  private MenuDocument readPersisted(String menuId, Path target, String writtenRevision) throws IOException {
    byte[] persistedBytes = readRegularFile(target);
    String persistedSource = new String(persistedBytes, StandardCharsets.UTF_8);
    String persistedRevision = MenuDocument.revisionOf(persistedSource);
    if (!persistedRevision.equals(writtenRevision)) {
      throw new DocumentRevisionConflictException(NOUN, menuId, writtenRevision, persistedRevision);
    }
    return MenuDocumentParser.parse(menuId, persistedSource);
  }

  private void publishNewFile(Path temporary, Path target) throws IOException {
    try {
      Files.createLink(target, temporary);
    } catch (UnsupportedOperationException failure) {
      throw new IOException("menu storage does not support atomic no-clobber file creation", failure);
    }
  }

  private void requireSourceRevision(String menuId, Path source, String expectedRevision) throws IOException {
    String actualSource = new String(readRegularFile(source), StandardCharsets.UTF_8);
    requireRevision(menuId, expectedRevision, MenuDocument.revisionOf(actualSource));
  }

  /**
   * A read never creates a directory. An absent {@code menus/} is the same answer as an absent menu
   * — {@code NoSuchFileException}, which the mutation service classifies as an operator mistake
   * rather than a storage fault — so asking for a menu that was never authored does not leave an
   * empty folder behind.
   */
  private byte[] readRegularFile(Path path) throws IOException {
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw new NoSuchFileException(path.toString());
    }
    validateAncestors(path);
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new NoSuchFileException(path.toString());
    }
    if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("menu path must be a regular non-symbolic file: " + path);
    }
    return Files.readAllBytes(path);
  }

  private void prepareParent(Path target) throws IOException {
    AtomicFiles.prepareParent(directory, target, NOUN);
  }

  private void validateAncestors(Path target) throws IOException {
    AtomicFiles.validateAncestors(directory, target, NOUN);
  }

  private Path path(String id) {
    Path target = directory.resolve(id + JSON_EXTENSION).normalize();
    if (!target.startsWith(directory)) {
      throw new IllegalArgumentException("menu path escapes the menu directory: " + id);
    }
    return target;
  }

  private static void requireRevision(String menuId, String expectedRevision, String actualRevision) {
    if (!expectedRevision.equals(actualRevision)) {
      throw new DocumentRevisionConflictException(NOUN, menuId, expectedRevision, actualRevision);
    }
  }
}
