package art.arcane.gloss.preview.doc;

import art.arcane.gloss.api.ParticleLayer;
import com.google.gson.JsonElement;

import java.util.List;
import java.util.Map;

/**
 * Raw shape of a preview JSON document, populated field-by-field from a Gson {@link com.google.gson.Gson}
 * binding by {@link PreviewDocumentParser}. Every field that can be either a JSON constant or a
 * live expression (numbers, colors, and the two booleans {@code framed}/{@code visible}) is typed
 * {@link JsonElement} so the parser decides constant-vs-expression itself instead of letting Gson's
 * reflective binding reject one of the two accepted shapes; see {@link PreviewDocumentParser} for
 * the exact per-field rules.
 */
final class PreviewDocument {
  MatchDef match;
  List<VariantDef> variants;
  CardDef card;
  List<ElementDef> elements;
  List<ParticleLayer> particleLayers;
}

/**
 * Match-criteria shape shared between the document's own top-level match and each
 * {@link VariantDef}. {@code special} and {@code priority} are only read from the top-level match;
 * {@code vars} values are JSON primitives only and are never treated as expressions (see
 * {@link PreviewDocumentParser}).
 */
class MatchDef {
  List<String> blocks;
  List<String> entities;
  String special;
  Integer priority;
  Map<String, JsonElement> vars;
}

/** A variant reuses the match shape to pick alternate {@code vars} for the same element templates. */
final class VariantDef extends MatchDef {
}

final class CardDef {
  // JsonElement rather than the brief sketch's plain Boolean: framed accepts a JSON boolean
  // constant OR an expression string, same as element `visible` below.
  JsonElement framed;
  String title;
  String accent;
  Integer minHalfWidth;
}

final class ElementDef {
  String type;
  JsonElement x;
  JsonElement y;
  JsonElement z;
  JsonElement width;
  JsonElement height;
  JsonElement size;
  JsonElement color;
  JsonElement wellColor;
  JsonElement index;
  JsonElement background;
  String text;
  // JsonElement rather than the brief sketch's plain String: visible accepts a JSON boolean
  // constant OR an expression string.
  JsonElement visible;
  RepeatDef repeat;
}

final class RepeatDef {
  JsonElement count;
  String var;
}
