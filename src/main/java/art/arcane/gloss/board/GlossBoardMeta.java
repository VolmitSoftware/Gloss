package art.arcane.gloss.board;

import art.arcane.gloss.doc.DocumentEnvelope;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class GlossBoardMeta {
    public static final String UNRESTRICTED_PERMISSION = "default";
    public static final String PERMISSION_NODE_PREFIX = "gloss.board.";

    private final String id;
    private final CopyOnWriteArrayList<String> content;
    private volatile String title;
    private volatile boolean primary;
    private volatile String permission;
    private volatile List<String> groups;
    private volatile long revision;

    public GlossBoardMeta(String id) {
        this.id = id;
        this.content = new CopyOnWriteArrayList<>();
        this.title = id;
        this.primary = false;
        this.permission = UNRESTRICTED_PERMISSION;
        this.groups = List.of();
        this.revision = 0L;
    }

    public static GlossBoardMeta fromDoc(String id, BoardDoc doc) {
        GlossBoardMeta meta = new GlossBoardMeta(id);
        meta.setTitle(doc.title().isEmpty() ? id : doc.title());
        for (String line : doc.lines()) {
            meta.addLine(line);
        }
        meta.setPrimary(doc.primary());
        meta.setPermission(doc.permission());
        meta.setGroups(doc.groups());
        meta.revision = doc.revision();
        return meta;
    }

    public BoardDoc toDoc(long revision) {
        return new BoardDoc(BoardDoc.CURRENT_SCHEMA_VERSION, revision, title, lines(), primary, permission, groups);
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title == null ? id : title;
    }

    public List<String> lines() {
        return List.copyOf(content);
    }

    List<String> contentView() {
        return content;
    }

    public void addLine(String line) {
        content.add(line == null ? "" : line);
    }

    public void setLine(int index, String line) {
        content.set(index, line == null ? "" : line);
    }

    public void removeLine(int index) {
        content.remove(index);
    }

    public boolean primary() {
        return primary;
    }

    public void setPrimary(boolean primary) {
        this.primary = primary;
    }

    public String permission() {
        return permission;
    }

    public void setPermission(String permission) {
        this.permission = BoardDoc.normalizePermission(permission);
    }

    public boolean permissionGated() {
        return !UNRESTRICTED_PERMISSION.equals(permission);
    }

    public String permissionNode() {
        return PERMISSION_NODE_PREFIX + permission;
    }

    public List<String> groups() {
        return groups;
    }

    public void setGroups(List<String> groups) {
        this.groups = groups == null ? List.of() : List.copyOf(groups);
    }

    public long revision() {
        return revision;
    }

    long nextRevision() {
        long next = revision >= DocumentEnvelope.MAX_SAFE_REVISION
            ? DocumentEnvelope.MAX_SAFE_REVISION
            : revision + 1L;
        revision = next;
        return next;
    }
}
