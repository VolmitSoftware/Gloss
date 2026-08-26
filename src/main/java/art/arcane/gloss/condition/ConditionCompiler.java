package art.arcane.gloss.condition;

import art.arcane.gloss.expr.Expr;
import art.arcane.gloss.expr.ExprException;
import art.arcane.gloss.expr.ExprParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public final class ConditionCompiler {

  private static final String DEFAULT_PATH = "<condition>";

  private ConditionCompiler() {
  }

  public static CompiledCondition compile(String expression) {
    return compile(new ConditionSource(DEFAULT_PATH, expression));
  }

  public static CompiledCondition compile(ConditionSource source) {
    Expr expression;
    try {
      expression = ExprParser.parse(source.expression());
    } catch (ExprException exception) {
      throw validationError(source, exception.getMessage(), exception.position(), exception);
    }

    ReferenceCollector collector = new ReferenceCollector();
    collector.collect(expression);
    TypeValidator validator = new TypeValidator(source);
    ValueType rootType = validator.typeOf(expression);
    if (rootType.kind() != ValueKind.BOOLEAN && rootType.kind() != ValueKind.UNKNOWN) {
      throw validationError(source,
          "condition must produce a boolean, got " + rootType.displayName(), -1, null);
    }
    return new CompiledCondition(source, expression, collector.references());
  }

  private static ConditionValidationException validationError(
      ConditionSource source, String detail, int position, Throwable cause) {
    return new ConditionValidationException(
        source.path(), source.expression(), detail, position, cause);
  }

  private enum ValueKind {
    BOOLEAN,
    NUMBER,
    STRING,
    LIST,
    NUMBER_OR_STRING,
    UNKNOWN
  }

  private record ValueType(ValueKind kind, ValueType elementType) {

    private static final ValueType BOOLEAN = new ValueType(ValueKind.BOOLEAN, null);
    private static final ValueType NUMBER = new ValueType(ValueKind.NUMBER, null);
    private static final ValueType STRING = new ValueType(ValueKind.STRING, null);
    private static final ValueType UNKNOWN = new ValueType(ValueKind.UNKNOWN, null);

    private static ValueType list(ValueType elementType) {
      return new ValueType(ValueKind.LIST, elementType);
    }

    private String displayName() {
      return kind == ValueKind.NUMBER_OR_STRING
          ? "number or string"
          : kind.name().toLowerCase();
    }
  }

  private static final class ReferenceCollector {

    private final Set<String> variables = new TreeSet<String>();
    private final Set<String> functions = new TreeSet<String>();
    private final Set<String> metricKeys = new TreeSet<String>();

    private void collect(Expr expression) {
      switch (expression) {
        case Expr.Num number -> {
        }
        case Expr.Str string -> {
        }
        case Expr.Bool bool -> {
        }
        case Expr.Var variable -> variables.add(variable.name());
        case Expr.ListLiteral list -> collectAll(list.items());
        case Expr.Unary unary -> collect(unary.operand());
        case Expr.Binary binary -> {
          collect(binary.left());
          collect(binary.right());
        }
        case Expr.Ternary ternary -> {
          collect(ternary.condition());
          collect(ternary.ifTrue());
          collect(ternary.ifFalse());
        }
        case Expr.Call call -> {
          functions.add(call.name());
          if (call.name().equals("metric") && !call.args().isEmpty()
              && call.args().getFirst() instanceof Expr.Str metricKey) {
            metricKeys.add(metricKey.value());
          }
          collectAll(call.args());
        }
      }
    }

    private void collectAll(List<Expr> expressions) {
      for (Expr expression : expressions) {
        collect(expression);
      }
    }

    private ConditionReferences references() {
      return new ConditionReferences(variables, functions, metricKeys);
    }
  }

  private static final class TypeValidator {

    private final ConditionSource source;

    private TypeValidator(ConditionSource source) {
      this.source = source;
    }

    private ValueType typeOf(Expr expression) {
      return switch (expression) {
        case Expr.Num number -> ValueType.NUMBER;
        case Expr.Str string -> ValueType.STRING;
        case Expr.Bool bool -> ValueType.BOOLEAN;
        case Expr.Var variable -> ValueType.UNKNOWN;
        case Expr.ListLiteral list -> listType(list);
        case Expr.Unary unary -> unaryType(unary);
        case Expr.Binary binary -> binaryType(binary);
        case Expr.Ternary ternary -> ternaryType(ternary);
        case Expr.Call call -> callType(call);
      };
    }

    private ValueType listType(Expr.ListLiteral list) {
      ValueType elementType = ValueType.UNKNOWN;
      for (Expr item : list.items()) {
        elementType = mergeTypes(elementType, typeOf(item), "list entries have incompatible types");
      }
      return ValueType.list(elementType);
    }

    private ValueType unaryType(Expr.Unary unary) {
      ValueType operandType = typeOf(unary.operand());
      return switch (unary.op()) {
        case "!" -> {
          requireType("!", operandType, ValueKind.BOOLEAN);
          yield ValueType.BOOLEAN;
        }
        case "-" -> {
          requireType("-", operandType, ValueKind.NUMBER);
          yield ValueType.NUMBER;
        }
        default -> throw error("unknown unary operator: " + unary.op());
      };
    }

    private ValueType binaryType(Expr.Binary binary) {
      ValueType left = typeOf(binary.left());
      ValueType right = typeOf(binary.right());
      return switch (binary.op()) {
        case "&&", "||" -> {
          requireType(binary.op(), left, ValueKind.BOOLEAN);
          requireType(binary.op(), right, ValueKind.BOOLEAN);
          yield ValueType.BOOLEAN;
        }
        case "<", "<=", ">", ">=" -> {
          requireType(binary.op(), left, ValueKind.NUMBER);
          requireType(binary.op(), right, ValueKind.NUMBER);
          yield ValueType.BOOLEAN;
        }
        case "==", "!=" -> equalityType(binary.op(), left, right);
        case "+" -> additionType(left, right);
        case "-", "*", "/", "%" -> {
          requireType(binary.op(), left, ValueKind.NUMBER);
          requireType(binary.op(), right, ValueKind.NUMBER);
          yield ValueType.NUMBER;
        }
        default -> throw error("unknown binary operator: " + binary.op());
      };
    }

    private ValueType equalityType(String operator, ValueType left, ValueType right) {
      requireComparable(operator, left);
      requireComparable(operator, right);
      if (left.kind() != ValueKind.UNKNOWN && right.kind() != ValueKind.UNKNOWN
          && left.kind() != right.kind() && !mayShareComparableType(left, right)) {
        throw error(operator + " cannot compare " + left.displayName() + " and " + right.displayName());
      }
      return ValueType.BOOLEAN;
    }

    private ValueType additionType(ValueType left, ValueType right) {
      if (left.kind() == ValueKind.LIST || right.kind() == ValueKind.LIST) {
        throw error("+ does not accept list operands");
      }
      if (left.kind() == ValueKind.STRING || right.kind() == ValueKind.STRING) {
        return ValueType.STRING;
      }
      if (left.kind() == ValueKind.NUMBER && right.kind() == ValueKind.NUMBER) {
        return ValueType.NUMBER;
      }
      if (left.kind() != ValueKind.UNKNOWN && right.kind() != ValueKind.UNKNOWN) {
        throw error("+ requires numbers unless either operand is a string");
      }
      return new ValueType(ValueKind.NUMBER_OR_STRING, null);
    }

    private ValueType ternaryType(Expr.Ternary ternary) {
      ValueType condition = typeOf(ternary.condition());
      requireType("ternary condition", condition, ValueKind.BOOLEAN);
      ValueType ifTrue = typeOf(ternary.ifTrue());
      ValueType ifFalse = typeOf(ternary.ifFalse());
      return mergeTypes(ifTrue, ifFalse, "ternary branches have incompatible types");
    }

    private ValueType callType(Expr.Call call) {
      List<ValueType> arguments = argumentTypes(call.args());
      return switch (call.name()) {
        case "oneOf" -> oneOfType(call.name(), arguments);
        case "contains", "startsWith", "endsWith", "matchesGlob" ->
            fixedType(call.name(), arguments, ValueType.BOOLEAN, ValueKind.STRING, ValueKind.STRING);
        case "metric" -> fixedType(call.name(), arguments, ValueType.NUMBER,
            ValueKind.STRING, ValueKind.NUMBER);
        case "hasPermission", "inGroup", "inRegion" -> fixedType(call.name(), arguments,
            ValueType.BOOLEAN, ValueKind.STRING, ValueKind.STRING);
        case "papi" -> fixedType(call.name(), arguments, ValueType.STRING,
            ValueKind.STRING, ValueKind.STRING, ValueKind.STRING);
        case "papiNumber" -> fixedType(call.name(), arguments, ValueType.NUMBER,
            ValueKind.STRING, ValueKind.STRING, ValueKind.NUMBER);
        case "clamp", "lerp", "smoothstep", "rgb", "mix" -> fixedType(call.name(), arguments,
            ValueType.NUMBER, ValueKind.NUMBER, ValueKind.NUMBER, ValueKind.NUMBER);
        case "argb" -> fixedType(call.name(), arguments, ValueType.NUMBER,
            ValueKind.NUMBER, ValueKind.NUMBER, ValueKind.NUMBER, ValueKind.NUMBER);
        case "min", "max", "mod", "pow", "alpha" -> fixedType(call.name(), arguments,
            ValueType.NUMBER, ValueKind.NUMBER, ValueKind.NUMBER);
        case "floor", "ceil", "round", "abs", "sin", "cos", "hex" -> fixedType(
            call.name(), arguments,
            call.name().equals("hex") ? ValueType.STRING : ValueType.NUMBER,
            ValueKind.NUMBER);
        case "palette" -> listSelectionType(call.name(), arguments, ValueKind.NUMBER, ValueType.NUMBER);
        case "select" -> listSelectionType(call.name(), arguments, null, null);
        case "number" -> numberType(call.name(), arguments);
        case "bar" -> fixedType(call.name(), arguments, ValueType.STRING,
            ValueKind.NUMBER, ValueKind.NUMBER, ValueKind.NUMBER, ValueKind.STRING, ValueKind.STRING);
        case "str" -> stringConversionType(call.name(), arguments);
        case "fixed" -> fixedType(call.name(), arguments, ValueType.STRING,
            ValueKind.NUMBER, ValueKind.NUMBER);
        case "plain", "readable" -> fixedType(call.name(), arguments, ValueType.STRING, ValueKind.STRING);
        case "align" -> fixedType(call.name(), arguments, ValueType.STRING,
            ValueKind.STRING, ValueKind.NUMBER, ValueKind.STRING);
        case "marquee", "typewriter", "flash" -> animationThreeType(call.name(), arguments);
        case "timeline" -> fixedType(call.name(), arguments, ValueType.STRING, ValueKind.LIST, ValueKind.NUMBER);
        case "wipe", "scramble" -> fixedType(call.name(), arguments, ValueType.STRING,
            ValueKind.STRING, ValueKind.NUMBER);
        case "scanner" -> fixedType(call.name(), arguments, ValueType.STRING,
            ValueKind.STRING, ValueKind.STRING, ValueKind.STRING, ValueKind.NUMBER);
        case "odometer" -> fixedType(call.name(), arguments, ValueType.STRING,
            ValueKind.NUMBER, ValueKind.NUMBER, ValueKind.NUMBER, ValueKind.NUMBER);
        case "wave" -> fixedType(call.name(), arguments, ValueType.STRING,
            ValueKind.STRING, ValueKind.LIST, ValueKind.NUMBER);
        default -> throw error("unknown condition function: " + call.name());
      };
    }

    private ValueType oneOfType(String name, List<ValueType> arguments) {
      requireCount(name, arguments, 2);
      requireArgumentType(name, arguments, 0, ValueKind.STRING);
      requireArgumentType(name, arguments, 1, ValueKind.LIST);
      ValueType entries = arguments.get(1).elementType();
      if (entries != null) {
        requireType(name + " argument 2 entries", entries, ValueKind.STRING);
      }
      return ValueType.BOOLEAN;
    }

    private ValueType fixedType(
        String name, List<ValueType> arguments, ValueType result, ValueKind... expected) {
      requireCount(name, arguments, expected.length);
      for (int index = 0; index < expected.length; index++) {
        requireArgumentType(name, arguments, index, expected[index]);
      }
      return result;
    }

    private ValueType listSelectionType(
        String name, List<ValueType> arguments, ValueKind entryKind, ValueType result) {
      requireCount(name, arguments, 2);
      requireArgumentType(name, arguments, 0, ValueKind.LIST);
      requireArgumentType(name, arguments, 1, ValueKind.NUMBER);
      ValueType entries = arguments.getFirst().elementType();
      if (entryKind != null && entries != null) {
        requireType(name + " argument 1 entries", entries, entryKind);
      }
      return result == null && entries != null ? entries : result == null ? ValueType.UNKNOWN : result;
    }

    private ValueType numberType(String name, List<ValueType> arguments) {
      requireCount(name, arguments, 1);
      ValueType argument = arguments.getFirst();
      if (argument.kind() != ValueKind.UNKNOWN && argument.kind() != ValueKind.NUMBER
          && argument.kind() != ValueKind.STRING && argument.kind() != ValueKind.NUMBER_OR_STRING) {
        throw error(name + " argument 1 must be a number or string, got " + argument.displayName());
      }
      return ValueType.NUMBER;
    }

    private ValueType stringConversionType(String name, List<ValueType> arguments) {
      requireCount(name, arguments, 1);
      ValueType argument = arguments.getFirst();
      if (argument.kind() == ValueKind.LIST) {
        throw error(name + " argument 1 must not be a list");
      }
      return ValueType.STRING;
    }

    private ValueType animationThreeType(String name, List<ValueType> arguments) {
      ValueKind[] expected = name.equals("flash")
          ? new ValueKind[]{ValueKind.STRING, ValueKind.STRING, ValueKind.NUMBER}
          : new ValueKind[]{ValueKind.STRING, ValueKind.NUMBER, ValueKind.NUMBER};
      return fixedType(name, arguments, ValueType.STRING, expected);
    }

    private List<ValueType> argumentTypes(List<Expr> arguments) {
      List<ValueType> types = new ArrayList<ValueType>(arguments.size());
      for (Expr argument : arguments) {
        types.add(typeOf(argument));
      }
      return types;
    }

    private ValueType mergeTypes(ValueType first, ValueType second, String message) {
      if (first.kind() == ValueKind.UNKNOWN) {
        return second;
      }
      if (second.kind() == ValueKind.UNKNOWN) {
        return first;
      }
      if (first.kind() != second.kind()) {
        throw error(message + ": " + first.displayName() + " and " + second.displayName());
      }
      if (first.kind() != ValueKind.LIST) {
        return first;
      }
      ValueType entries = mergeTypes(first.elementType(), second.elementType(), message);
      return ValueType.list(entries);
    }

    private void requireComparable(String operator, ValueType type) {
      if (type.kind() == ValueKind.LIST) {
        throw error(operator + " does not accept list operands");
      }
    }

    private boolean mayShareComparableType(ValueType first, ValueType second) {
      if (first.kind() == ValueKind.NUMBER_OR_STRING) {
        return second.kind() == ValueKind.NUMBER || second.kind() == ValueKind.STRING
            || second.kind() == ValueKind.NUMBER_OR_STRING;
      }
      if (second.kind() == ValueKind.NUMBER_OR_STRING) {
        return first.kind() == ValueKind.NUMBER || first.kind() == ValueKind.STRING;
      }
      return false;
    }

    private void requireArgumentType(
        String function, List<ValueType> arguments, int index, ValueKind expected) {
      ValueType actual = arguments.get(index);
      if (!mayBeType(actual, expected)) {
        throw error(function + " argument " + (index + 1) + " must be a "
            + expected.name().toLowerCase() + ", got " + actual.displayName());
      }
    }

    private void requireType(String context, ValueType actual, ValueKind expected) {
      if (!mayBeType(actual, expected)) {
        throw error(context + " requires " + expected.name().toLowerCase()
            + ", got " + actual.displayName());
      }
    }

    private boolean mayBeType(ValueType actual, ValueKind expected) {
      return actual.kind() == ValueKind.UNKNOWN || actual.kind() == expected
          || actual.kind() == ValueKind.NUMBER_OR_STRING
          && (expected == ValueKind.NUMBER || expected == ValueKind.STRING);
    }

    private void requireCount(String function, List<ValueType> arguments, int expected) {
      if (arguments.size() != expected) {
        throw error(function + " expects " + expected + " argument(s), got " + arguments.size());
      }
    }

    private ConditionValidationException error(String detail) {
      return validationError(source, detail, -1, null);
    }
  }
}
