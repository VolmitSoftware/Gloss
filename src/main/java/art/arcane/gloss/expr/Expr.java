package art.arcane.gloss.expr;

import java.util.List;

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

  record ListLiteral(List<Expr> items) implements Expr {
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
