package art.arcane.gloss.editor.sync;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.config.menu.MenuIds;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.imaging.ImageInfo;
import org.apache.commons.imaging.Imaging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EditorSyncSnapshotBuilder implements EditorSyncSnapshotSource {
  static final int MAX_MENU_COUNT = 256;
  static final int MAX_IMAGE_COUNT = 512;
  static final int MAX_IMAGE_BYTES = 512 * 1024;
  static final int MAX_WARNING_COUNT = 256;
  static final int MAX_WARNING_CHARACTERS = 512;
  static final int MAX_IMAGE_DIMENSION = 16;
  static final long MAX_IMAGE_PIXELS = 256L;
  static final long MAX_PROJECT_IMAGE_PIXELS = 262_144L;
  static final long MAX_PROJECT_IMAGE_ROWS = 4_096L;
  static final int MAX_WORKSPACE_IMAGE_DIMENSION = 4_096;
  static final long MAX_WORKSPACE_IMAGE_PIXELS = 16_777_216L;
  static final long MAX_WORKSPACE_PROJECT_IMAGE_PIXELS = 67_108_864L;
  private static final Pattern PLAYER_OPEN_COMMAND = Pattern.compile(
      "^\\s*/?(?:gloss|gl|glo|gg)\\s+menus?\\s+open\\s+(?:menu=)?([^\\s]+)\\s*$",
      Pattern.CASE_INSENSITIVE);

  private final EditorSyncContentSnapshotBuilder contentSnapshots;

  public EditorSyncSnapshotBuilder(Gloss plugin) {
    this.contentSnapshots = new EditorSyncContentSnapshotBuilder(
        Objects.requireNonNull(plugin, "plugin").getDataFolder().toPath());
  }

  @Override
  public EditorSyncProject open(EditorSyncKind kind, String subjectId, int maximumBytes) {
    return contentSnapshots.open(kind, subjectId, maximumBytes);
  }

  @Override
  public List<String> subjectIds(EditorSyncKind kind) {
    return contentSnapshots.subjectIds(kind);
  }

  static void collectTargets(JsonElement element, Set<String> targets) {
    if (element == null || element.isJsonNull()) {
      return;
    }
    if (element.isJsonArray()) {
      for (JsonElement child : element.getAsJsonArray()) {
        collectTargets(child, targets);
      }
      return;
    }
    if (!element.isJsonObject()) {
      return;
    }
    JsonObject object = element.getAsJsonObject();
    String type = primitiveString(object.get("type"));
    if ("navigate".equals(type)) {
      String mode = primitiveString(object.get("mode"));
      String target = primitiveString(object.get("target"));
      if ((mode == null || mode.equals("push") || mode.equals("replace")) && target != null) {
        addMenuId(targets, target);
      }
    } else if ("command".equals(type)) {
      String source = primitiveString(object.get("source"));
      String command = primitiveString(object.get("command"));
      if ((source == null || source.equals("player")) && command != null) {
        Matcher matcher = PLAYER_OPEN_COMMAND.matcher(command);
        if (matcher.matches()) {
          addMenuId(targets, matcher.group(1));
        }
      }
    }
    for (java.util.Map.Entry<String, JsonElement> entry : object.entrySet()) {
      collectTargets(entry.getValue(), targets);
    }
  }

  static void collectImagePaths(JsonElement element, Set<String> paths) {
    List<String> usages = new ArrayList<>();
    collectImageUsages(element, usages);
    paths.addAll(usages);
  }

  static void collectImageUsages(JsonElement element, List<String> paths) {
    if (element == null || element.isJsonNull()) {
      return;
    }
    if (element.isJsonArray()) {
      for (JsonElement child : element.getAsJsonArray()) {
        collectImageUsages(child, paths);
      }
      return;
    }
    if (!element.isJsonObject()) {
      return;
    }
    JsonObject object = element.getAsJsonObject();
    String type = primitiveString(object.get("type"));
    if ("textImage".equals(type)) {
      addImagePath(paths, primitiveString(object.get("path")));
    } else if ("animatedTextImage".equals(type)) {
      JsonElement source = object.get("source");
      if (source != null && source.isJsonArray()) {
        for (JsonElement frame : source.getAsJsonArray()) {
          addImagePath(paths, primitiveString(frame));
        }
      } else {
        addImagePath(paths, primitiveString(source));
      }
    }
    for (java.util.Map.Entry<String, JsonElement> entry : object.entrySet()) {
      collectImageUsages(entry.getValue(), paths);
    }
  }

  static Path resolveConfinedFile(Path root, String relative, boolean requireExisting) throws IOException {
    String normalizedRelative = normalizeRelative(relative);
    Path canonicalRoot = root.toAbsolutePath().normalize();
    if (Files.exists(canonicalRoot, LinkOption.NOFOLLOW_LINKS)
        && (Files.isSymbolicLink(canonicalRoot)
        || !Files.isDirectory(canonicalRoot, LinkOption.NOFOLLOW_LINKS))) {
      throw new IOException("asset root is not a real directory");
    }
    Path candidate = canonicalRoot.resolve(normalizedRelative).normalize();
    if (!candidate.startsWith(canonicalRoot) || candidate.equals(canonicalRoot)) {
      throw new IOException("asset path escapes the image directory");
    }
    Path current = canonicalRoot;
    Path parent = candidate.getParent();
    if (parent == null) {
      throw new IOException("asset path has no parent");
    }
    for (Path segment : canonicalRoot.relativize(parent)) {
      current = current.resolve(segment);
      if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
        throw new IOException("asset path contains a symbolic link");
      }
    }
    if (Files.isSymbolicLink(candidate)) {
      throw new IOException("asset file is a symbolic link");
    }
    if (requireExisting && !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("asset file does not exist");
    }
    return candidate;
  }

  static String normalizeRelative(String relative) {
    if (relative == null || relative.isBlank()) {
      throw new IllegalArgumentException("asset path must not be blank");
    }
    String normalized = relative.strip().replace('\\', '/');
    if (normalized.startsWith("/") || normalized.contains("//")) {
      throw new IllegalArgumentException("asset path must be relative");
    }
    String[] segments = normalized.split("/", -1);
    for (String segment : segments) {
      if (segment.isBlank() || segment.equals(".") || segment.equals("..") || segment.startsWith(".")) {
        throw new IllegalArgumentException("asset path contains an invalid segment");
      }
    }
    return String.join("/", segments);
  }

  private static void addMenuId(Set<String> targets, String target) {
    try {
      targets.add(MenuIds.require(target));
    } catch (IllegalArgumentException ignored) {
    }
  }

  private static void addImagePath(java.util.Collection<String> paths, String path) {
    if (path == null) {
      return;
    }
    try {
      paths.add(normalizeRelative(path));
    } catch (IllegalArgumentException ignored) {
    }
  }

  private static String primitiveString(JsonElement element) {
    return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
        ? element.getAsString()
        : null;
  }

  static String detectMediaType(byte[] data) {
    if (data.length >= 8 && (data[0] & 0xff) == 0x89 && data[1] == 'P'
        && data[2] == 'N' && data[3] == 'G' && data[4] == 0x0d && data[5] == 0x0a
        && data[6] == 0x1a && data[7] == 0x0a) {
      return "image/png";
    }
    if (data.length >= 3 && (data[0] & 0xff) == 0xff && (data[1] & 0xff) == 0xd8
        && (data[2] & 0xff) == 0xff) {
      return "image/jpeg";
    }
    if (data.length >= 6 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F'
        && data[3] == '8' && (data[4] == '7' || data[4] == '9') && data[5] == 'a') {
      return "image/gif";
    }
    if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F'
        && data[3] == 'F' && data[8] == 'W' && data[9] == 'E' && data[10] == 'B'
        && data[11] == 'P') {
      return "image/webp";
    }
    if (data.length >= 2 && data[0] == 'B' && data[1] == 'M') {
      return "image/bmp";
    }
    throw new IllegalArgumentException("unsupported image encoding");
  }

  static ValidatedImage validateImage(String path, byte[] data) {
    String mediaType = detectMediaType(data);
    try {
      ImageInfo information = Imaging.getImageInfo(data);
      if (information == null || information.getWidth() < 1 || information.getHeight() < 1
          || information.getWidth() > MAX_IMAGE_DIMENSION
          || information.getHeight() > MAX_IMAGE_DIMENSION
          || (long) information.getWidth() * information.getHeight() > MAX_IMAGE_PIXELS) {
        throw new IllegalArgumentException("image dimensions exceed the sync limit: " + path);
      }
      if (Imaging.getBufferedImage(data) == null) {
        throw new IllegalArgumentException("image data is not decodable: " + path);
      }
      return new ValidatedImage(mediaType,
          information.getWidth(), information.getHeight());
    } catch (IOException failure) {
      throw new IllegalArgumentException("image data is not decodable: " + path, failure);
    }
  }

  static ValidatedImage validateWorkspaceImage(String path, byte[] data) {
    String mediaType = detectMediaType(data);
    try {
      ImageInfo information = Imaging.getImageInfo(data);
      if (information == null || information.getWidth() < 1 || information.getHeight() < 1
          || information.getWidth() > MAX_WORKSPACE_IMAGE_DIMENSION
          || information.getHeight() > MAX_WORKSPACE_IMAGE_DIMENSION
          || (long) information.getWidth() * information.getHeight()
          > MAX_WORKSPACE_IMAGE_PIXELS) {
        throw new IllegalArgumentException("image dimensions exceed the workspace sync limit: " + path);
      }
      if (Imaging.getBufferedImage(data) == null) {
        throw new IllegalArgumentException("image data is not decodable: " + path);
      }
      return new ValidatedImage(mediaType, information.getWidth(), information.getHeight());
    } catch (IOException failure) {
      throw new IllegalArgumentException("image data is not decodable: " + path, failure);
    }
  }

  static void validateWorkspaceImageBudgets(Map<String, ValidatedImage> images) {
    long pixels = 0L;
    for (ValidatedImage image : images.values()) {
      pixels += image.pixels();
      if (pixels > MAX_WORKSPACE_PROJECT_IMAGE_PIXELS) {
        throw new IllegalArgumentException("workspace images exceed the aggregate decoded-pixel limit");
      }
    }
  }

  static void validateReferencedImageBudgets(Iterable<String> menuSources,
                                             Map<String, ValidatedImage> images) {
    Map<String, ValidatedImage> referencedImages = new LinkedHashMap<>();
    List<String> usages = new ArrayList<>();
    for (String source : menuSources) {
      collectImageUsages(JsonParser.parseString(source), usages);
    }
    for (String path : usages) {
      ValidatedImage image = images.get(path);
      if (image == null) {
        throw new IllegalArgumentException("referenced image is absent from sync project: " + path);
      }
      if (image.width() > MAX_IMAGE_DIMENSION || image.height() > MAX_IMAGE_DIMENSION
          || image.pixels() > MAX_IMAGE_PIXELS) {
        throw new IllegalArgumentException("referenced image dimensions exceed the sync limit: " + path);
      }
      referencedImages.put(path, image);
    }
    validateImageBudgets(menuSources, referencedImages);
  }

  static void validateImageBudgets(Iterable<String> menuSources,
                                   Map<String, ValidatedImage> images) {
    long storedPixels = 0L;
    for (ValidatedImage image : images.values()) {
      storedPixels += image.pixels();
    }
    requirePixelBudget(storedPixels, "stored image entries");

    List<String> usages = new ArrayList<>();
    for (String source : menuSources) {
      collectImageUsages(JsonParser.parseString(source), usages);
    }
    long runtimePixels = 0L;
    long runtimeRows = 0L;
    for (String path : usages) {
      ValidatedImage image = images.get(path);
      if (image == null) {
        throw new IllegalArgumentException("referenced image is absent from sync project: " + path);
      }
      runtimePixels += image.pixels();
      runtimeRows += image.height();
      requirePixelBudget(runtimePixels, "runtime image usages");
      if (runtimeRows > MAX_PROJECT_IMAGE_ROWS) {
        throw new IllegalArgumentException(
            "runtime image usages exceed the sync aggregate row limit");
      }
    }
  }

  private static void requirePixelBudget(long pixels, String label) {
    if (pixels > MAX_PROJECT_IMAGE_PIXELS) {
      throw new IllegalArgumentException(label + " exceed the sync aggregate pixel limit");
    }
  }

  record ValidatedImage(String mediaType, int width, int height) {
    long pixels() {
      return (long) width * height;
    }
  }

}
