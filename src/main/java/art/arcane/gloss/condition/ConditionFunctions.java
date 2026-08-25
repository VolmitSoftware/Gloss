package art.arcane.gloss.condition;

import art.arcane.gloss.expr.ExprException;

import java.util.List;

final class ConditionFunctions {

  private static final int NO_POSITION = -1;

  private ConditionFunctions() {
  }

  static Object call(String name, List<Object> args) {
    return switch (name) {
      case "oneOf" -> oneOf(name, args);
      case "contains" -> contains(name, args);
      case "startsWith" -> startsWith(name, args);
      case "endsWith" -> endsWith(name, args);
      case "matchesGlob" -> matchesGlob(name, args);
      default -> null;
    };
  }

  private static boolean oneOf(String name, List<Object> args) {
    requireCount(name, args, 2);
    String expected = stringArgument(name, args, 0);
    Object candidatesArgument = args.get(1);
    if (!(candidatesArgument instanceof List<?> candidates)) {
      throw error(name + " argument 2 must be a list");
    }
    for (int index = 0; index < candidates.size(); index++) {
      Object candidate = candidates.get(index);
      if (!(candidate instanceof String text)) {
        throw error(name + " argument 2 entries must be strings");
      }
      if (expected.equals(text)) {
        return true;
      }
    }
    return false;
  }

  private static boolean contains(String name, List<Object> args) {
    requireCount(name, args, 2);
    return stringArgument(name, args, 0).contains(stringArgument(name, args, 1));
  }

  private static boolean startsWith(String name, List<Object> args) {
    requireCount(name, args, 2);
    return stringArgument(name, args, 0).startsWith(stringArgument(name, args, 1));
  }

  private static boolean endsWith(String name, List<Object> args) {
    requireCount(name, args, 2);
    return stringArgument(name, args, 0).endsWith(stringArgument(name, args, 1));
  }

  private static boolean matchesGlob(String name, List<Object> args) {
    requireCount(name, args, 2);
    String value = stringArgument(name, args, 0);
    String pattern = stringArgument(name, args, 1);
    boolean[] previous = new boolean[value.length() + 1];
    previous[0] = true;
    for (int patternIndex = 0; patternIndex < pattern.length(); patternIndex++) {
      char token = pattern.charAt(patternIndex);
      boolean[] current = new boolean[value.length() + 1];
      if (token == '*') {
        current[0] = previous[0];
        for (int valueIndex = 1; valueIndex <= value.length(); valueIndex++) {
          current[valueIndex] = previous[valueIndex] || current[valueIndex - 1];
        }
      } else {
        for (int valueIndex = 1; valueIndex <= value.length(); valueIndex++) {
          current[valueIndex] = previous[valueIndex - 1]
              && (token == '?' || token == value.charAt(valueIndex - 1));
        }
      }
      previous = current;
    }
    return previous[value.length()];
  }

  private static void requireCount(String name, List<Object> args, int count) {
    if (args.size() != count) {
      throw error(name + " expects " + count + " argument(s), got " + args.size());
    }
  }

  private static String stringArgument(String name, List<Object> args, int index) {
    Object value = args.get(index);
    if (value instanceof String text) {
      return text;
    }
    throw error(name + " argument " + (index + 1) + " must be a string");
  }

  private static ExprException error(String message) {
    return new ExprException(message, NO_POSITION);
  }
}
