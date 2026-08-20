package art.arcane.gloss.panel;

import art.arcane.gloss.doc.DocumentIds;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class PanelIds {
  public static final int MAX_ID_LENGTH = DocumentIds.MAX_ID_LENGTH;
  public static final int MAX_SEGMENT_LENGTH = DocumentIds.MAX_SEGMENT_LENGTH;
  public static final int MAX_MENU_ID_LENGTH = DocumentIds.MAX_ID_LENGTH;

  private static final Pattern SEGMENT = Pattern.compile("[a-z0-9][a-z0-9._-]*");

  private PanelIds() {
  }

  public static String canonicalize(String value) {
    if (value == null) {
      throw new IllegalArgumentException("panel id must not be null");
    }

    String normalized = value.strip().toLowerCase(Locale.ROOT);
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("panel id must not be blank");
    }
    if (normalized.length() > MAX_ID_LENGTH) {
      throw new IllegalArgumentException("panel id must be at most " + MAX_ID_LENGTH + " characters");
    }
    if (normalized.indexOf('\\') >= 0) {
      throw new IllegalArgumentException("panel id must use forward slashes between segments");
    }

    String[] rawSegments = normalized.split("/", -1);
    List<String> segments = new ArrayList<>(rawSegments.length);
    for (String segment : rawSegments) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        throw new IllegalArgumentException("panel id contains an invalid path segment: " + value);
      }
      if (segment.length() > MAX_SEGMENT_LENGTH || !SEGMENT.matcher(segment).matches()) {
        throw new IllegalArgumentException("panel id segment must match [a-z0-9][a-z0-9._-]*: " + segment);
      }
      segments.add(segment);
    }
    return String.join("/", segments);
  }

  public static String requireMenuReference(String value) {
    return DocumentIds.require("root menu id", value);
  }
}
