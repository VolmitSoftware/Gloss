package art.arcane.gloss.api;

import org.bukkit.Location;

import java.util.List;

public interface Hologram {
    String id();

    Location location();

    void teleport(Location location);

    List<String> lines();

    void addLine(String line);

    void setLine(int index, String line);

    void setLines(List<String> lines);

    void removeLine(int index);

    void clearLines();
}
