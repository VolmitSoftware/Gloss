package art.arcane.gloss.config;

import art.arcane.gloss.expr.Expr;
import art.arcane.gloss.expr.ExprEvaluator;
import art.arcane.gloss.expr.ExprException;
import art.arcane.gloss.expr.ExprParser;
import art.arcane.gloss.expr.ExpressionScope;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

/**
 * Moves and resizes a component's existing displays. {@code x}, {@code y} and {@code z} are added
 * to the component's offset and {@code scale} multiplies its size; the viewer's client eases every
 * change over {@code ticks}, so a component that keeps moving never leaves the screen. Every value
 * is re-read each tick, which lets a hidden component jump with {@code ticks} 0 and glide once shown.
 */
@JsonAdapter(ComponentMotion.Adapter.class)
public final class ComponentMotion {
  public static final int DEFAULT_TICKS = 3;
  public static final int MAX_TICKS = 59;
  public static final float MIN_SCALE = 0.001F;
  public static final ComponentMotion NONE = new ComponentMotion(new Sources(null, null, null, null, "0"));

  private final Sources sources;
  private final Expr x;
  private final Expr y;
  private final Expr z;
  private final Expr scale;
  private final Expr ticks;

  private ComponentMotion(Sources sources) {
    this.sources = sources;
    this.x = compile("x", sources.x());
    this.y = compile("y", sources.y());
    this.z = compile("z", sources.z());
    this.scale = compile("scale", sources.scale());
    this.ticks = compile("ticks", sources.ticks());
    if (ExprEvaluator.isConstant(ticks)) {
      requireTickRange(ExprEvaluator.number(ticks, null));
    }
  }

  public static ComponentMotion of(String x, String y, String z, String scale, String ticks) {
    return new ComponentMotion(new Sources(x, y, z, scale, ticks == null ? Integer.toString(DEFAULT_TICKS) : ticks));
  }

  public boolean isActive() {
    return x != null || y != null || z != null || scale != null;
  }

  public int ticks(ExpressionScope scope) {
    double value = finite(ExprEvaluator.number(ticks, scope));
    return (int) Math.max(0D, Math.min(MAX_TICKS, Math.rint(value)));
  }

  public double x(ExpressionScope scope) {
    return axis(x, scope);
  }

  public double y(ExpressionScope scope) {
    return axis(y, scope);
  }

  public double z(ExpressionScope scope) {
    return axis(z, scope);
  }

  public float scale(ExpressionScope scope) {
    if (scale == null) {
      return 1F;
    }
    return Math.max(MIN_SCALE, (float) finite(ExprEvaluator.number(scale, scope)));
  }

  private static void requireTickRange(double value) {
    if (value != Math.rint(value) || value < 0D || value > MAX_TICKS) {
      throw new IllegalArgumentException("motion.ticks must be a whole number between 0 and " + MAX_TICKS + ", got " + value);
    }
  }

  private static double axis(Expr axis, ExpressionScope scope) {
    return axis == null ? 0D : finite(ExprEvaluator.number(axis, scope));
  }

  private static double finite(double value) {
    if (!Double.isFinite(value)) {
      throw new ExprException("motion expression produced " + value, -1);
    }
    return value;
  }

  private static Expr compile(String field, String source) {
    if (source == null) {
      return null;
    }
    try {
      return ExprParser.parse(source);
    } catch (ExprException failure) {
      throw new IllegalArgumentException("motion." + field + " is not a valid expression: " + failure.getMessage(), failure);
    }
  }

  private record Sources(String x, String y, String z, String scale, String ticks) {
  }

  public static final class Adapter extends TypeAdapter<ComponentMotion> {
    @Override
    public ComponentMotion read(JsonReader reader) throws IOException {
      String x = null;
      String y = null;
      String z = null;
      String scale = null;
      String ticks = null;
      reader.beginObject();
      while (reader.hasNext()) {
        String name = reader.nextName();
        switch (name) {
          case "x" -> x = expression(reader);
          case "y" -> y = expression(reader);
          case "z" -> z = expression(reader);
          case "scale" -> scale = expression(reader);
          case "ticks" -> ticks = expression(reader);
          default -> reader.skipValue();
        }
      }
      reader.endObject();
      return of(x, y, z, scale, ticks);
    }

    @Override
    public void write(JsonWriter writer, ComponentMotion motion) throws IOException {
      writer.beginObject();
      writeExpression(writer, "x", motion.sources.x());
      writeExpression(writer, "y", motion.sources.y());
      writeExpression(writer, "z", motion.sources.z());
      writeExpression(writer, "scale", motion.sources.scale());
      writeExpression(writer, "ticks", motion.sources.ticks());
      writer.endObject();
    }

    private static String expression(JsonReader reader) throws IOException {
      JsonToken token = reader.peek();
      if (token == JsonToken.NUMBER) {
        return reader.nextString();
      }
      if (token != JsonToken.STRING) {
        throw new IllegalArgumentException("motion values must be a number or expression at " + reader.getPath());
      }
      String source = reader.nextString().trim();
      if (source.startsWith("{{") && source.endsWith("}}")) {
        return source.substring(2, source.length() - 2).trim();
      }
      return source;
    }

    private static void writeExpression(JsonWriter writer, String name, String source) throws IOException {
      if (source != null) {
        writer.name(name).value(source);
      }
    }
  }
}
