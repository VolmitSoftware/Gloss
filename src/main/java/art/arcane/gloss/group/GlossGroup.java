package art.arcane.gloss.group;

public record GlossGroup(String name, String tablistName, String defaultBoard) {
    public GlossGroup {
        name = name == null ? "" : name;
        tablistName = tablistName == null ? "" : tablistName;
        defaultBoard = defaultBoard == null ? "" : defaultBoard;
    }
}
