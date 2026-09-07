package art.arcane.gloss.expr;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Abstract syntax tree for the preview expression language.
 */
public sealed interface Expr permits Expr.Num, Expr.Str, Expr.Bool, Expr.ListLiteral,
    Expr.Var, Expr.Unary, Expr.Binary, Expr.Ternary, Expr.Call {

  record Num(double value) implements Expr {
  }

  record Str(String value) implements Expr {
  }

  record Bool(boolean value) implements Expr {
  }

  /**
   * List literal. Elements that are all literals are folded once at parse time, so evaluating the
   * node does not rebuild the same list on every render.
   */
  final class ListLiteral implements Expr {
    private final List<Expr> items;
    private final List<Object> constantItems;

    public ListLiteral(List<Expr> items) {
      this.items = items == null ? List.of() : List.copyOf(items);
      this.constantItems = foldConstants(this.items);
    }

    public List<Expr> items() {
      return items;
    }

    /** Pre-evaluated values when every element is a literal, otherwise {@code null}. */
    List<Object> constantItems() {
      return constantItems;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof ListLiteral list && items.equals(list.items);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(items);
    }

    @Override
    public String toString() {
      return "ListLiteral[items=" + items + "]";
    }

    private static List<Object> foldConstants(List<Expr> items) {
      List<Object> values = new ArrayList<>(items.size());
      for (Expr item : items) {
        switch (item) {
          case Num number -> values.add(number.value());
          case Str text -> values.add(text.value());
          case Bool flag -> values.add(flag.value());
          default -> {
            return null;
          }
        }
      }
      return List.copyOf(values);
    }
  }

  /** Dotted identifier, e.g. "inventory.size". */
  record Var(String name) implements Expr {
  }

  /** Prefix operator: "-" or "!". */
  record Unary(String op, Expr operand) implements Expr {
  }

  /** "+ - * / % == != < <= > >= && ||". */
  record Binary(String op, Expr left, Expr right) implements Expr {
  }

  record Ternary(Expr condition, Expr ifTrue, Expr ifFalse) implements Expr {
  }

  /** Function call; names are never dotted. */
  record Call(String name, List<Expr> args) implements Expr {
  }
}
