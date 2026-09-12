package art.arcane.gloss.rig;

import art.arcane.gloss.menu.action.MenuAction;
import com.github.retrooper.packetevents.util.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record CompiledRig(
    String id,
    RigDoc doc,
    RigModel model,
    List<Part> parts,
    Map<String, RigDoc.ClipRef> clips,
    CompiledGraph graph,
    List<CompiledHitbox> hitboxes
) {
    public static CompiledRig compile(String id, RigDoc doc, int partCap) {
        doc.requirePartCap(partCap);
        List<CompiledHitbox> hitboxes = new ArrayList<>(doc.hitboxes().size());
        for (RigDoc.Hitbox hitbox : doc.hitboxes()) {
            hitboxes.add(new CompiledHitbox(hitbox.part(), hitbox.extent(),
                MenuAction.resolve(hitbox.actions(), "rig:" + id, "hitbox:" + hitbox.part())));
        }
        return new CompiledRig(id, doc, doc.model(), doc.toParts(), doc.clips(), CompiledGraph.compile(doc.graph()),
            List.copyOf(hitboxes));
    }

    public int partIndex(String partId) {
        for (int index = 0; index < parts.size(); index++) {
            if (parts.get(index).id().equals(partId)) {
                return index;
            }
        }
        return -1;
    }

    public int minimalPartIndex() {
        String minimal = doc.lod().minimalPart();
        int index = minimal == null ? -1 : partIndex(minimal);
        return index < 0 ? 0 : index;
    }

    public record CompiledHitbox(String part, Vector3f size, List<MenuAction<?>> actions) {
    }
}
