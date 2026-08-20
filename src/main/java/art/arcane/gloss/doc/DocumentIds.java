package art.arcane.gloss.doc;

import java.util.regex.Pattern;

/**
 * Shared validation for the slash-separated ids that name documents on disk. The {@code noun}
 * parameter is the operator-facing name of the field being validated, so the rejection message
 * still points at the setting the operator typed.
 */
public final class DocumentIds {
  public static final int MAX_ID_LENGTH = 255;
  public static final int MAX_SEGMENT_LENGTH = 64;

  private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

  private DocumentIds() {
  }

  public static String require(String noun, String value) {
    if (value == null) {
      throw new IllegalArgumentException(noun + " must not be null");
    }
    String normalized = value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(noun + " must not be blank");
    }
    if (normalized.length() > MAX_ID_LENGTH) {
      throw new IllegalArgumentException(noun + " must be at most " + MAX_ID_LENGTH + " characters");
    }
    if (normalized.indexOf('\\') >= 0) {
      throw new IllegalArgumentException(noun + " must use forward slashes between segments");
    }
    for (String segment : normalized.split("/", -1)) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        throw new IllegalArgumentException(noun + " contains an invalid path segment: " + value);
      }
      if (segment.length() > MAX_SEGMENT_LENGTH || !SEGMENT.matcher(segment).matches()) {
        throw new IllegalArgumentException(
            noun + " segment must match [A-Za-z0-9][A-Za-z0-9._-]*: " + segment);
      }
    }
    return normalized;
  }
}
