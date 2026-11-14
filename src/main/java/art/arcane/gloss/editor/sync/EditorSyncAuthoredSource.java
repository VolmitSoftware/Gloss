package art.arcane.gloss.editor.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Writes a publish into the text a document file already holds. Values the publish left alone keep their bytes
 * (spacing, comments, number spelling, quoting), a changed number keeps the spelling of the one it replaces
 * ({@code 30.0} set to 45 is {@code 45.0}), removed keys go with their separator and new keys follow the file's
 * indentation. Reads what Gson's lenient reader reads, comments included.
 */
final class EditorSyncAuthoredSource {
  private static final Gson STRINGS = new GsonBuilder().disableHtmlEscaping().create();
  private static final Pattern PLAIN_SEPARATOR = Pattern.compile("[ \\t\\r\\n]*[,;][ \\t\\r\\n]*");
  private static final Pattern PLAIN_COLON = Pattern.compile("[ \\t]*[:=][ \\t]*");
  private static final int MAX_MATCHED_PAIRS = 1_000_000;

  private EditorSyncAuthoredSource() {
  }

  /** {@code source} rewritten to hold {@code after}, or null when the text cannot be read or would not read back. */
  static String edit(String source, JsonElement after) {
    Node root = Reader.read(source);
    if (root == null) {
      return null;
    }
    try {
      String edited = new Writer(source, root).document(root.json(), after);
      return EditorSyncJson.canonical(JsonParser.parseString(edited)).equals(EditorSyncJson.canonical(after))
          ? edited : null;
    } catch (RuntimeException unreadable) {
      return null;
    }
  }

  /**
   * {@code source} with what changed between {@code before} and {@code after} written in, for a file a model was read
   * from: keys the model does not know and keys the edit did not touch stay as written. Null when the text cannot be
   * read; the caller checks the result reads back as the edited model.
   */
  static String edit(String source, JsonElement before, JsonElement after) {
    Node root = Reader.read(source);
    if (root == null) {
      return null;
    }
    try {
      return new Writer(source, root).document(before, after);
    } catch (RuntimeException unreadable) {
      return null;
    }
  }

  private sealed interface Node permits ObjectNode, ArrayNode, Leaf {
    int start();

    int end();

    JsonElement json();
  }

  private record Member(String name, int start, int nameEnd, Node value) {
    int end() {
      return value.end();
    }
  }

  private record ObjectNode(int start, int end, List<Member> members) implements Node {
    @Override
    public JsonElement json() {
      JsonObject object = new JsonObject();
      for (Member member : members) {
        object.add(member.name(), member.value().json());
      }
      return object;
    }
  }

  private record ArrayNode(int start, int end, List<Node> items) implements Node {
    @Override
    public JsonElement json() {
      JsonArray array = new JsonArray();
      for (Node item : items) {
        array.add(item.json());
      }
      return array;
    }
  }

  private record Leaf(int start, int end, JsonElement json) implements Node {
    boolean number() {
      return json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber();
    }
  }

  /** A document read with the span of every value. Returns null for text Gson's lenient reader would refuse. */
  private static final class Reader {
    private final String text;
    private int position;
    private int depth;

    private Reader(String text) {
      this.text = text;
    }

    static Node read(String text) {
      Reader reader = new Reader(text);
      try {
        reader.trivia();
        Node root = reader.value();
        reader.trivia();
        return reader.position == text.length() && !(root instanceof Leaf) ? root : null;
      } catch (RuntimeException unreadable) {
        return null;
      }
    }

    private Node value() {
      if (++depth > 256) {
        throw new IllegalArgumentException("too deep");
      }
      try {
        char next = peek();
        if (next == '{') {
          return object();
        }
        if (next == '[') {
          return array();
        }
        int start = position;
        String token = next == '"' || next == '\'' ? quoted() : literal();
        return new Leaf(start, position, JsonParser.parseString(token));
      } finally {
        depth--;
      }
    }

    private ObjectNode object() {
      int start = position++;
      List<Member> members = new ArrayList<>();
      trivia();
      if (peek() == '}') {
        position++;
        return new ObjectNode(start, position, members);
      }
      while (true) {
        int nameStart = position;
        char next = peek();
        String token = next == '"' || next == '\'' ? quoted() : literal();
        String name = next == '"' || next == '\'' ? JsonParser.parseString(token).getAsString() : token;
        int nameEnd = position;
        trivia();
        char separator = take();
        if (separator == '=') {
          if (position < text.length() && text.charAt(position) == '>') {
            position++;
          }
        } else if (separator != ':') {
          throw new IllegalArgumentException("expected a colon");
        }
        trivia();
        members.add(new Member(name, nameStart, nameEnd, value()));
        trivia();
        char after = take();
        if (after == '}') {
          return new ObjectNode(start, position, members);
        }
        if (after != ',' && after != ';') {
          throw new IllegalArgumentException("expected a comma");
        }
        trivia();
      }
    }

    private ArrayNode array() {
      int start = position++;
      List<Node> items = new ArrayList<>();
      trivia();
      if (peek() == ']') {
        position++;
        return new ArrayNode(start, position, items);
      }
      while (true) {
        items.add(value());
        trivia();
        char after = take();
        if (after == ']') {
          return new ArrayNode(start, position, items);
        }
        if (after != ',' && after != ';') {
          throw new IllegalArgumentException("expected a comma");
        }
        trivia();
      }
    }

    private String quoted() {
      int start = position;
      char quote = take();
      while (true) {
        char next = take();
        if (next == '\\') {
          take();
        } else if (next == quote) {
          return text.substring(start, position);
        }
      }
    }

    private String literal() {
      int start = position;
      while (position < text.length() && !delimiter(text.charAt(position))) {
        position++;
      }
      if (position == start) {
        throw new IllegalArgumentException("expected a value");
      }
      return text.substring(start, position);
    }

    private static boolean delimiter(char character) {
      return switch (character) {
        case '/', '\\', ';', '#', '=', '{', '}', '[', ']', ':', ',', ' ', '\t', '\f', '\r', '\n', '"', '\'' -> true;
        default -> false;
      };
    }

    private void trivia() {
      while (position < text.length()) {
        char next = text.charAt(position);
        if (next == ' ' || next == '\t' || next == '\n' || next == '\r') {
          position++;
        } else if (next == '#' || text.startsWith("//", position)) {
          while (position < text.length() && text.charAt(position) != '\n') {
            position++;
          }
        } else if (text.startsWith("/*", position)) {
          int close = text.indexOf("*/", position + 2);
          if (close < 0) {
            throw new IllegalArgumentException("unterminated comment");
          }
          position = close + 2;
        } else {
          return;
        }
      }
    }

    private char peek() {
      if (position >= text.length()) {
        throw new IllegalArgumentException("unexpected end");
      }
      return text.charAt(position);
    }

    private char take() {
      char next = peek();
      position++;
      return next;
    }
  }

  /** Builds the edited text from the original spans, writing only what differs. */
  private static final class Writer {
    private final String source;
    private final Node root;
    private final String newline;
    private final boolean multiline;
    private final String unit;
    private final String colon;
    private final String inline;

    Writer(String source, Node root) {
      this.source = source;
      this.root = root;
      this.newline = source.contains("\r\n") ? "\r\n" : "\n";
      this.multiline = source.substring(root.start(), root.end()).indexOf('\n') >= 0;
      this.unit = indentUnit(root);
      this.colon = colonStyle(root);
      this.inline = inlineSeparator(root);
    }

    String document(JsonElement before, JsonElement after) {
      return source.substring(0, root.start()) + render(root, before, after) + source.substring(root.end());
    }

    private String render(Node node, JsonElement before, JsonElement after) {
      if (same(before, after)) {
        return original(node);
      }
      if (node instanceof ObjectNode object && after.isJsonObject()) {
        return object(object, before.isJsonObject() ? before.getAsJsonObject() : object.json().getAsJsonObject(),
            after.getAsJsonObject());
      }
      if (node instanceof ArrayNode array && after.isJsonArray()) {
        return array(array, before.isJsonArray() ? before.getAsJsonArray() : array.json().getAsJsonArray(),
            after.getAsJsonArray());
      }
      if (node instanceof Leaf leaf && leaf.number() && after.isJsonPrimitive()
          && after.getAsJsonPrimitive().isNumber()) {
        return number(original(leaf), after.getAsJsonPrimitive());
      }
      return fresh(after, lineIndent(node.start()), multiline);
    }

    private String object(ObjectNode node, JsonObject before, JsonObject after) {
      Map<String, Integer> last = new HashMap<>();
      for (int index = 0; index < node.members().size(); index++) {
        last.put(node.members().get(index).name(), index);
      }
      boolean lines = elementsOnOwnLines(node);
      String inner = innerIndent(node, lines);
      boolean freshLines = lines || node.members().isEmpty() && multiline;
      List<Piece> pieces = new ArrayList<>();
      for (int index = 0; index < node.members().size(); index++) {
        Member member = node.members().get(index);
        if (last.get(member.name()) != index || !after.has(member.name())) {
          if (last.get(member.name()) != index || !before.has(member.name())) {
            pieces.add(new Piece(index, original(member.start(), member.end())));
          }
          continue;
        }
        JsonElement was = before.has(member.name()) ? before.get(member.name()) : member.value().json();
        pieces.add(new Piece(index, source.substring(member.start(), member.value().start())
            + render(member.value(), was, after.get(member.name()))));
      }
      for (Map.Entry<String, JsonElement> entry : after.entrySet()) {
        if (last.containsKey(entry.getKey())
            || before.has(entry.getKey()) && same(before.get(entry.getKey()), entry.getValue())) {
          continue;
        }
        pieces.add(new Piece(-1, STRINGS.toJson(entry.getKey()) + colon + fresh(entry.getValue(), inner,
            freshLines)));
      }
      return assemble(node, pieces, inner, freshLines);
    }

    private String array(ArrayNode node, JsonArray before, JsonArray after) {
      List<Node> items = node.items();
      if (before.size() != items.size()) {
        before = node.json().getAsJsonArray();
      }
      boolean lines = elementsOnOwnLines(node);
      String inner = innerIndent(node, lines);
      boolean freshLines = lines || items.isEmpty() && multiline;
      List<Piece> pieces = new ArrayList<>();
      if (before.size() == after.size()) {
        for (int index = 0; index < items.size(); index++) {
          pieces.add(new Piece(index, render(items.get(index), before.get(index), after.get(index))));
        }
      } else {
        int[] matched = match(before, after);
        int oldIndex = 0;
        int newIndex = 0;
        while (newIndex <= after.size()) {
          int kept = newIndex;
          while (kept < after.size() && matched[kept] < 0) {
            kept++;
          }
          int oldEnd = kept < after.size() ? matched[kept] : items.size();
          for (int added = newIndex; added < kept; added++) {
            if (oldIndex < oldEnd) {
              pieces.add(new Piece(oldIndex, render(items.get(oldIndex), before.get(oldIndex), after.get(added))));
              oldIndex++;
            } else {
              pieces.add(new Piece(-1, fresh(after.get(added), inner, freshLines)));
            }
          }
          oldIndex = oldEnd;
          if (kept < after.size()) {
            pieces.add(new Piece(oldIndex, original(items.get(oldIndex))));
            oldIndex++;
          }
          newIndex = kept + 1;
        }
      }
      return assemble(node, pieces, inner, freshLines);
    }

    /**
     * For each item of {@code after}, the index of the {@code before} item it keeps unchanged, or -1: the longest
     * run of equal items in order, after the shared head and tail.
     */
    private static int[] match(JsonArray before, JsonArray after) {
      List<String> left = canonicalItems(before);
      List<String> right = canonicalItems(after);
      int[] matched = new int[right.size()];
      java.util.Arrays.fill(matched, -1);
      int head = 0;
      while (head < left.size() && head < right.size() && left.get(head).equals(right.get(head))) {
        matched[head] = head;
        head++;
      }
      int tail = 0;
      while (tail < left.size() - head && tail < right.size() - head
          && left.get(left.size() - 1 - tail).equals(right.get(right.size() - 1 - tail))) {
        matched[right.size() - 1 - tail] = left.size() - 1 - tail;
        tail++;
      }
      int rows = left.size() - head - tail;
      int columns = right.size() - head - tail;
      if (rows == 0 || columns == 0 || (long) rows * columns > MAX_MATCHED_PAIRS) {
        return matched;
      }
      int[][] lengths = new int[rows + 1][columns + 1];
      for (int row = rows - 1; row >= 0; row--) {
        for (int column = columns - 1; column >= 0; column--) {
          lengths[row][column] = left.get(head + row).equals(right.get(head + column))
              ? lengths[row + 1][column + 1] + 1
              : Math.max(lengths[row + 1][column], lengths[row][column + 1]);
        }
      }
      int row = 0;
      int column = 0;
      while (row < rows && column < columns) {
        if (left.get(head + row).equals(right.get(head + column))) {
          matched[head + column] = head + row;
          row++;
          column++;
        } else if (lengths[row + 1][column] >= lengths[row][column + 1]) {
          row++;
        } else {
          column++;
        }
      }
      return matched;
    }

    private static List<String> canonicalItems(JsonArray array) {
      List<String> items = new ArrayList<>(array.size());
      for (JsonElement item : array) {
        items.add(EditorSyncJson.canonical(item));
      }
      return items;
    }

    private record Piece(int original, String text) {
    }

    /**
     * The container rebuilt from {@code pieces}. Each element the edit kept brings the text around it: the comma and
     * the rest of its line after it, and the line break, comments and indent before it. A new element takes the
     * container's usual separator. Elements that stay neighbours keep the exact text between them.
     */
    private String assemble(Node container, List<Piece> pieces, String inner, boolean lines) {
      int start = container.start();
      int end = container.end();
      List<Integer> starts = starts(container);
      List<Integer> ends = children(container).stream().map(Node::end).toList();
      char open = source.charAt(start);
      char close = source.charAt(end - 1);
      if (pieces.isEmpty()) {
        return starts.isEmpty() ? original(start, end) : "" + open + close;
      }
      if (starts.isEmpty()) {
        StringBuilder text = new StringBuilder().append(open);
        String separator = lines ? "," + newline + inner : inline;
        text.append(lines ? newline + inner : "");
        for (int index = 0; index < pieces.size(); index++) {
          text.append(index > 0 ? separator : "").append(pieces.get(index).text());
        }
        return text.append(lines ? newline + lineIndent(start) : "").append(close).toString();
      }
      int count = starts.size();
      String usual = usualSeparator(starts, ends, inner, lines);
      int usualCut = cut(usual);
      String firstLead = source.substring(start + 1, starts.getFirst());
      String closing = source.substring(ends.getLast(), end);
      int closingCut = lineBreak(closing);
      if (closingCut < 0) {
        closingCut = closing.length() - 1;
        while (closingCut > 0 && Character.isWhitespace(closing.charAt(closingCut - 1))) {
          closingCut--;
        }
      }
      StringBuilder text = new StringBuilder().append(open);
      for (int index = 0; index < pieces.size(); index++) {
        Piece piece = pieces.get(index);
        int kept = piece.original();
        boolean last = index + 1 == pieces.size();
        if (index == 0) {
          String lead = kept > 0 ? lead(between(starts, ends, kept)) : firstLead;
          text.append(lead.indexOf('\n') >= 0 ? lead : firstLead);
        } else {
          text.append(kept > 0 ? lead(between(starts, ends, kept)) : usual.substring(usualCut));
        }
        text.append(piece.text());
        if (kept < 0) {
          text.append(last ? "" : usual.substring(0, usualCut));
        } else if (kept + 1 < count) {
          String tail = tail(between(starts, ends, kept + 1));
          text.append(last ? withoutComma(tail) : tail);
        } else {
          text.append(last ? "" : usual.substring(0, usualCut)).append(closing, 0, closingCut);
        }
      }
      return text.append(closing.substring(closingCut)).toString();
    }

    private String between(List<Integer> starts, List<Integer> ends, int index) {
      return source.substring(ends.get(index - 1), starts.get(index));
    }

    /** The part of a separator that stays with the element before it: the comma and the rest of that line. */
    private static String tail(String separator) {
      return separator.substring(0, cut(separator));
    }

    /** The part of a separator that stays with the element after it. */
    private static String lead(String separator) {
      return separator.substring(cut(separator));
    }

    private static String withoutComma(String tail) {
      int comma = comma(tail);
      return comma < 0 ? tail : tail.substring(0, comma) + tail.substring(comma + 1);
    }

    /** Where a separator splits: at the first line break after its comma, else just after the comma. */
    private static int cut(String separator) {
      int comma = comma(separator);
      if (comma < 0) {
        return 0;
      }
      int lineBreak = lineBreak(separator.substring(comma));
      return lineBreak < 0 ? comma + 1 : comma + lineBreak;
    }

    /** The separating comma of a run of whitespace and comments, or -1. */
    private static int comma(String trivia) {
      for (int index = 0; index < trivia.length(); index = skipComment(trivia, index)) {
        char character = trivia.charAt(index);
        if (character == ',' || character == ';') {
          return index;
        }
      }
      return -1;
    }

    /** The first line break outside a block comment, or -1. */
    private static int lineBreak(String trivia) {
      for (int index = 0; index < trivia.length(); index = skipComment(trivia, index)) {
        if (trivia.charAt(index) == '\n') {
          return index;
        }
      }
      return -1;
    }

    /** The index after the comment starting at {@code index}, up to its line break for a line comment. */
    private static int skipComment(String trivia, int index) {
      if (trivia.startsWith("/*", index)) {
        int close = trivia.indexOf("*/", index + 2);
        return close < 0 ? trivia.length() : close + 2;
      }
      if (trivia.charAt(index) == '#' || trivia.startsWith("//", index)) {
        int lineEnd = trivia.indexOf('\n', index);
        return lineEnd < 0 ? trivia.length() : lineEnd;
      }
      return index + 1;
    }

    private String usualSeparator(List<Integer> starts, List<Integer> ends, String inner, boolean lines) {
      if (starts.size() >= 2) {
        String between = source.substring(ends.get(ends.size() - 2), starts.getLast());
        if (PLAIN_SEPARATOR.matcher(between).matches()) {
          return between;
        }
      }
      return lines ? "," + newline + inner : inline;
    }

    private boolean elementsOnOwnLines(Node container) {
      List<Integer> starts = starts(container);
      return !starts.isEmpty() && startsLine(starts.getLast());
    }

    private String innerIndent(Node container, boolean lines) {
      return lines ? lineIndent(starts(container).getLast()) : lineIndent(container.start()) + unit;
    }

    /** {@code after} as new text, laid out like the file around it. */
    private String fresh(JsonElement value, String indent, boolean lines) {
      if (value.isJsonNull()) {
        return "null";
      }
      if (value.isJsonPrimitive()) {
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        return primitive.isNumber() ? primitive.getAsNumber().toString() : STRINGS.toJson(primitive);
      }
      boolean object = value.isJsonObject();
      List<String> parts = new ArrayList<>();
      String inner = indent + unit;
      if (object) {
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
          parts.add(STRINGS.toJson(entry.getKey()) + colon + fresh(entry.getValue(), inner, lines));
        }
      } else {
        for (JsonElement item : value.getAsJsonArray()) {
          parts.add(fresh(item, inner, lines));
        }
      }
      String open = object ? "{" : "[";
      String close = object ? "}" : "]";
      if (parts.isEmpty()) {
        return open + close;
      }
      if (!lines) {
        return open + String.join(inline, parts) + close;
      }
      return open + newline + inner + String.join("," + newline + inner, parts) + newline + indent + close;
    }

    /**
     * {@code after} spelled like {@code before}: the same exponent form, a fraction when it had one, and as many
     * fraction digits when it padded with zeros ({@code 1.50} set to 2.5 is {@code 2.50}).
     */
    static String number(String before, JsonPrimitive after) {
      String spelled = after.getAsNumber().toString();
      BigDecimal value;
      try {
        value = new BigDecimal(spelled);
      } catch (NumberFormatException unusual) {
        return spelled;
      }
      int exponent = Math.max(before.indexOf('e'), before.indexOf('E'));
      if (exponent >= 0) {
        BigDecimal reduced = value.stripTrailingZeros();
        int power = reduced.precision() - reduced.scale() - 1;
        boolean plus = exponent + 1 < before.length() && before.charAt(exponent + 1) == '+';
        return reduced.movePointLeft(power).toPlainString() + before.charAt(exponent)
            + (plus && power >= 0 ? "+" : "") + power;
      }
      BigDecimal magnitude = value.abs();
      if (magnitude.compareTo(BigDecimal.valueOf(1, -21)) >= 0
          || magnitude.signum() != 0 && magnitude.compareTo(BigDecimal.valueOf(1, 6)) < 0) {
        return spelled;
      }
      int dot = before.indexOf('.');
      int digits = dot < 0 ? 0 : before.endsWith("0") ? before.length() - dot - 1 : 1;
      BigDecimal plain = value.stripTrailingZeros();
      if (plain.scale() < 0) {
        plain = plain.setScale(0);
      }
      if (plain.scale() < digits) {
        plain = plain.setScale(digits);
      }
      return plain.toPlainString();
    }

    private static boolean same(JsonElement left, JsonElement right) {
      return EditorSyncJson.canonical(left == null ? JsonNull.INSTANCE : left)
          .equals(EditorSyncJson.canonical(right == null ? JsonNull.INSTANCE : right));
    }

    private String original(Node node) {
      return original(node.start(), node.end());
    }

    private String original(int start, int end) {
      return source.substring(start, end);
    }

    private boolean startsLine(int position) {
      for (int index = position - 1; index >= 0; index--) {
        char character = source.charAt(index);
        if (character == '\n') {
          return true;
        }
        if (character != ' ' && character != '\t' && character != '\r') {
          return false;
        }
      }
      return true;
    }

    private String lineIndent(int position) {
      int lineStart = source.lastIndexOf('\n', position - 1) + 1;
      int end = lineStart;
      while (end < source.length() && (source.charAt(end) == ' ' || source.charAt(end) == '\t')) {
        end++;
      }
      return source.substring(lineStart, end);
    }

    /** The step one level of nesting adds in this file, two spaces when nothing shows it. */
    private String indentUnit(Node root) {
      for (Node node : breadthFirst(root)) {
        List<Integer> starts = starts(node);
        if (!starts.isEmpty() && startsLine(starts.getFirst())) {
          String outer = lineIndent(node.start());
          String inner = lineIndent(starts.getFirst());
          if (inner.length() > outer.length() && inner.startsWith(outer)) {
            return inner.substring(outer.length());
          }
        }
      }
      return "  ";
    }

    /** The colon spelling between a name and its value, as the file first spells one. */
    private String colonStyle(Node root) {
      for (Node node : breadthFirst(root)) {
        if (node instanceof ObjectNode object && !object.members().isEmpty()) {
          Member first = object.members().getFirst();
          String between = source.substring(first.nameEnd(), first.value().start());
          if (PLAIN_COLON.matcher(between).matches() && between.indexOf('=') < 0) {
            return between;
          }
        }
      }
      return multiline ? ": " : ":";
    }

    /** The separator between two elements on one line, as the file first spells one. */
    private String inlineSeparator(Node root) {
      for (Node node : breadthFirst(root)) {
        List<Node> children = children(node);
        List<Integer> starts = starts(node);
        for (int index = 1; index < starts.size(); index++) {
          String between = source.substring(children.get(index - 1).end(), starts.get(index));
          if (between.indexOf('\n') < 0 && PLAIN_SEPARATOR.matcher(between).matches()) {
            return between;
          }
        }
      }
      return colon.endsWith(" ") ? ", " : ",";
    }

    private static List<Node> breadthFirst(Node root) {
      List<Node> nodes = new ArrayList<>(List.of(root));
      for (int index = 0; index < nodes.size(); index++) {
        nodes.addAll(children(nodes.get(index)));
      }
      return nodes;
    }

    private static List<Node> children(Node node) {
      return node instanceof ObjectNode object ? object.members().stream().map(Member::value).toList()
          : node instanceof ArrayNode array ? array.items() : List.of();
    }

    /** Where each element starts: a member at its name. */
    private static List<Integer> starts(Node node) {
      return node instanceof ObjectNode object ? object.members().stream().map(Member::start).toList()
          : children(node).stream().map(Node::start).toList();
    }
  }
}
