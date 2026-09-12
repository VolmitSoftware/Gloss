package art.arcane.gloss.glosspack;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The dotted-number comparison a pack's {@code requires.gloss} range needs. A range is one or more
 * comma-separated comparators ({@code >=3.0.2, <4.0.0}); a bare version means {@code >=}.
 */
final class GlossPackVersions {
    private static final Pattern SEMVER = Pattern.compile("[0-9]+(\\.[0-9]+){0,3}([.-].*)?");
    private static final Pattern COMPARATOR = Pattern.compile("^(>=|<=|>|<|=)?\\s*(.+)$");

    private GlossPackVersions() {
    }

    static boolean isSemver(String value) {
        return value != null && SEMVER.matcher(value).matches();
    }

    static boolean satisfies(String range, String version) {
        for (String part : range.split(",")) {
            if (!satisfiesOne(part.strip(), version)) {
                return false;
            }
        }
        return true;
    }

    private static boolean satisfiesOne(String comparator, String version) {
        Matcher matched = COMPARATOR.matcher(comparator);
        if (!matched.matches()) {
            throw new IllegalArgumentException("pack version range is invalid: " + comparator);
        }
        String operator = matched.group(1) == null ? ">=" : matched.group(1);
        int order = compare(version, matched.group(2).strip());
        return switch (operator) {
            case ">=" -> order >= 0;
            case "<=" -> order <= 0;
            case ">" -> order > 0;
            case "<" -> order < 0;
            default -> order == 0;
        };
    }

    static int compare(String left, String right) {
        String[] leftParts = numbers(left);
        String[] rightParts = numbers(right);
        int length = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < length; index++) {
            int order = Integer.compare(part(leftParts, index), part(rightParts, index));
            if (order != 0) {
                return order;
            }
        }
        return 0;
    }

    private static String[] numbers(String version) {
        int cut = version.length();
        for (int index = 0; index < version.length(); index++) {
            char character = version.charAt(index);
            if (character != '.' && (character < '0' || character > '9')) {
                cut = index;
                break;
            }
        }
        return version.substring(0, cut).split("\\.");
    }

    private static int part(String[] parts, int index) {
        if (index >= parts.length || parts[index].isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException malformed) {
            return 0;
        }
    }
}
