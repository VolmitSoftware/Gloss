package art.arcane.gloss.hologram;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class HologramTick {
    private static final double CELL_CENTER = 8.0D;
    private static final double CELL_RADIUS = Math.sqrt(3.0D) * CELL_CENTER;

    record Viewer(Player player, UUID id, UUID worldId, double x, double y, double z) {
    }

    private record AudienceCell(UUID worldId, int chunkX, int sectionY, int chunkZ, long rangeBits) {
    }

    private record AudienceQuery(UUID worldId, long xBits, long yBits, long zBits, long rangeBits) {
    }

    private record AudienceCellSnapshot(List<Viewer> guaranteedViewers,
                                        List<Player> guaranteedPlayers,
                                        List<Viewer> boundaryViewers) {
    }

    private final HologramViewerIndex index;
    private final Map<AudienceCell, AudienceCellSnapshot> temporaryAudienceCells;
    private final Map<AudienceQuery, List<Viewer>> temporaryViewerAudiences;
    private final Map<AudienceQuery, List<Player>> temporaryPlayerAudiences;

    HologramTick() {
        this(null);
    }

    HologramTick(HologramViewerIndex index) {
        this.index = index;
        this.temporaryAudienceCells = new ConcurrentHashMap<>();
        this.temporaryViewerAudiences = new ConcurrentHashMap<>();
        this.temporaryPlayerAudiences = new ConcurrentHashMap<>();
    }

    List<Viewer> temporaryViewers(World world, Location anchor, double range) {
        AudienceQuery query = audienceQuery(world, anchor, range);
        return temporaryViewerAudiences.computeIfAbsent(query, ignored -> {
            AudienceCellSnapshot cell = temporaryAudienceCell(world, anchor, range);
            List<Viewer> boundary = exactBoundary(cell.boundaryViewers(), anchor, range);
            if (boundary.isEmpty()) {
                return cell.guaranteedViewers();
            }
            if (cell.guaranteedViewers().isEmpty()) {
                return boundary;
            }

            List<Viewer> viewers = new ArrayList<>(cell.guaranteedViewers().size() + boundary.size());
            viewers.addAll(cell.guaranteedViewers());
            viewers.addAll(boundary);
            return List.copyOf(viewers);
        });
    }

    List<Player> temporaryPlayers(World world, Location anchor, double range) {
        AudienceQuery query = audienceQuery(world, anchor, range);
        return temporaryPlayerAudiences.computeIfAbsent(query, ignored -> {
            AudienceCellSnapshot cell = temporaryAudienceCell(world, anchor, range);
            List<Player> boundary = exactBoundaryPlayers(cell.boundaryViewers(), anchor, range);
            if (boundary.isEmpty()) {
                return cell.guaranteedPlayers();
            }
            if (cell.guaranteedPlayers().isEmpty()) {
                return boundary;
            }
            return new CombinedPlayerList(cell.guaranteedPlayers(), boundary);
        });
    }

    private AudienceQuery audienceQuery(World world, Location anchor, double range) {
        return new AudienceQuery(world.getUID(),
            Double.doubleToLongBits(anchor.getX()), Double.doubleToLongBits(anchor.getY()),
            Double.doubleToLongBits(anchor.getZ()), Double.doubleToLongBits(range));
    }

    private AudienceCellSnapshot temporaryAudienceCell(World world, Location anchor, double range) {
        int chunkX = anchor.getBlockX() >> 4;
        int sectionY = anchor.getBlockY() >> 4;
        int chunkZ = anchor.getBlockZ() >> 4;
        AudienceCell cell = new AudienceCell(world.getUID(), chunkX, sectionY, chunkZ,
            Double.doubleToLongBits(range));
        return temporaryAudienceCells.computeIfAbsent(cell, ignored -> {
            Location center = new Location(world, (chunkX << 4) + CELL_CENTER,
                (sectionY << 4) + CELL_CENTER, (chunkZ << 4) + CELL_CENTER);
            List<Viewer> candidates = viewers(world, center, range + CELL_RADIUS);
            List<Viewer> guaranteedViewers = new ArrayList<>(candidates.size());
            List<Player> guaranteedPlayers = new ArrayList<>(candidates.size());
            List<Viewer> boundaryViewers = new ArrayList<>();
            double guaranteedRange = Math.max(0.0D, range - CELL_RADIUS);
            double guaranteedRangeSquared = guaranteedRange * guaranteedRange;
            for (Viewer viewer : candidates) {
                double dx = viewer.x() - center.getX();
                double dy = viewer.y() - center.getY();
                double dz = viewer.z() - center.getZ();
                if (dx * dx + dy * dy + dz * dz <= guaranteedRangeSquared) {
                    guaranteedViewers.add(viewer);
                    guaranteedPlayers.add(viewer.player());
                } else {
                    boundaryViewers.add(viewer);
                }
            }
            return new AudienceCellSnapshot(List.copyOf(guaranteedViewers),
                List.copyOf(guaranteedPlayers), List.copyOf(boundaryViewers));
        });
    }

    private static List<Viewer> exactBoundary(List<Viewer> candidates, Location anchor, double range) {
        List<Viewer> viewers = new ArrayList<>();
        double rangeSquared = range * range;
        for (Viewer candidate : candidates) {
            if (distanceSquared(candidate, anchor) <= rangeSquared) {
                viewers.add(candidate);
            }
        }
        return List.copyOf(viewers);
    }

    private static List<Player> exactBoundaryPlayers(List<Viewer> candidates, Location anchor, double range) {
        List<Player> players = new ArrayList<>();
        double rangeSquared = range * range;
        for (Viewer candidate : candidates) {
            if (distanceSquared(candidate, anchor) <= rangeSquared) {
                players.add(candidate.player());
            }
        }
        return List.copyOf(players);
    }

    private static double distanceSquared(Viewer viewer, Location anchor) {
        double dx = viewer.x() - anchor.getX();
        double dy = viewer.y() - anchor.getY();
        double dz = viewer.z() - anchor.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    int temporaryAudienceCellCount() {
        return temporaryAudienceCells.size();
    }

    List<Viewer> viewers(World world, Location anchor, double range) {
        if (index != null) {
            return index.nearby(anchor, range);
        }

        List<Player> players = world.getPlayers();
        List<Viewer> viewers = new ArrayList<>(players.size());
        double rangeSquared = range * range;
        for (Player player : players) {
            Location location = player.getLocation();
            if (location.getWorld() != world) {
                continue;
            }
            if (location.distanceSquared(anchor) <= rangeSquared) {
                viewers.add(new Viewer(player, player.getUniqueId(), world.getUID(),
                    location.getX(), location.getY(), location.getZ()));
            }
        }
        return List.copyOf(viewers);
    }

    private static final class CombinedPlayerList extends AbstractList<Player> implements RandomAccess {
        private final List<Player> guaranteed;
        private final List<Player> boundary;

        private CombinedPlayerList(List<Player> guaranteed, List<Player> boundary) {
            this.guaranteed = guaranteed;
            this.boundary = boundary;
        }

        @Override
        public Player get(int index) {
            if (index < 0 || index >= size()) {
                throw new IndexOutOfBoundsException(index);
            }
            return index < guaranteed.size()
                ? guaranteed.get(index)
                : boundary.get(index - guaranteed.size());
        }

        @Override
        public int size() {
            return guaranteed.size() + boundary.size();
        }
    }
}
