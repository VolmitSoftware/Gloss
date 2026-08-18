package art.arcane.gloss.drop;

public final class DropNameFormatter {
    private DropNameFormatter() {
    }

    public static String format(String template, int count, String typeName) {
        return template
            .replace("{count}", Integer.toString(count))
            .replace("{type}", typeName);
    }
}
