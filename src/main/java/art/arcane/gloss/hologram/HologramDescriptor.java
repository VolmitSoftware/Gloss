package art.arcane.gloss.hologram;

import art.arcane.volmlib.util.json.JSONArray;
import art.arcane.volmlib.util.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record HologramDescriptor(String id, String world, double x, double y, double z, List<String> lines) {
    public HologramDescriptor {
        Objects.requireNonNull(id, "Hologram descriptor requires an id.");
        Objects.requireNonNull(world, "Hologram descriptor requires a world name.");
        lines = List.copyOf(lines);
    }

    public static HologramDescriptor fromJson(JSONObject json) {
        JSONArray lineArray = json.getJSONArray("lines");
        List<String> lines = new ArrayList<>(lineArray.length());
        for (int index = 0; index < lineArray.length(); index++) {
            lines.add(lineArray.getString(index));
        }

        return new HologramDescriptor(
            json.getString("id"),
            json.getString("world"),
            json.getDouble("x"),
            json.getDouble("y"),
            json.getDouble("z"),
            lines
        );
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("world", world);
        json.put("x", x);
        json.put("y", y);
        json.put("z", z);
        JSONArray lineArray = new JSONArray();
        for (String line : lines) {
            lineArray.put(line);
        }

        json.put("lines", lineArray);
        return json;
    }
}
