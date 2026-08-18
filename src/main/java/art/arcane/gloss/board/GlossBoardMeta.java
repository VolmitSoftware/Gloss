package art.arcane.gloss.board;

import art.arcane.volmlib.util.json.JSONArray;
import art.arcane.volmlib.util.json.JSONObject;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

public final class GlossBoardMeta {
    public static final String UNRESTRICTED_PERMISSION = "default";
    public static final String PERMISSION_NODE_PREFIX = "gloss.board.";

    private final String id;
    private final CopyOnWriteArrayList<String> content;
    private volatile String title;
    private volatile boolean primary;
    private volatile String permission;

    public GlossBoardMeta(String id) {
        this.id = id;
        this.content = new CopyOnWriteArrayList<>();
        this.title = id;
        this.primary = false;
        this.permission = UNRESTRICTED_PERMISSION;
    }

    public static GlossBoardMeta fromJson(String id, JSONObject json) {
        GlossBoardMeta meta = new GlossBoardMeta(id);
        meta.setTitle(json.optString("title", id));
        if (json.has("primary")) {
            meta.setPrimary(json.getBoolean("primary"));
        }
        meta.setPermission(json.optString("permission", UNRESTRICTED_PERMISSION));
        JSONArray lines = json.optJSONArray("content");
        if (lines != null) {
            for (int index = 0; index < lines.length(); index++) {
                meta.addLine(lines.optString(index, ""));
            }
        }
        return meta;
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
        String normalized = permission == null ? "" : permission.trim().toLowerCase(Locale.ROOT);
        this.permission = normalized.isEmpty() ? UNRESTRICTED_PERMISSION : normalized;
    }

    public boolean permissionGated() {
        return !UNRESTRICTED_PERMISSION.equals(permission);
    }

    public String permissionNode() {
        return PERMISSION_NODE_PREFIX + permission;
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("title", title);
        JSONArray lines = new JSONArray();
        for (String line : content) {
            lines.put(line);
        }
        json.put("content", lines);
        json.put("primary", primary);
        json.put("permission", permission);
        return json;
    }
}
