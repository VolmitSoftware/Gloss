package art.arcane.gloss.nameplate;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.condition.EntityRelationshipSnapshot;
import art.arcane.gloss.entity.EntityOverlaySource;
import art.arcane.gloss.entity.EntityOverlayText;
import art.arcane.gloss.expr.ExprScope;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The overlay source that owns player panes. It claims {@code Player} targets only, which is what
 * lets the entity-overlay document keep {@code includePlayers} off while nameplates are on and
 * still leave mobs alone.
 */
public final class NameplateSource implements EntityOverlaySource {
    private final BooleanSupplier active;
    private final Supplier<List<NameplateRuntime>> documents;
    private final NameplateSuppression suppression;

    public NameplateSource(BooleanSupplier active) {
        this(active, List::of, null);
    }

    public NameplateSource(BooleanSupplier active, Supplier<List<NameplateRuntime>> documents,
                           NameplateSuppression suppression) {
        this.active = Objects.requireNonNull(active, "active");
        this.documents = Objects.requireNonNull(documents, "documents");
        this.suppression = suppression;
    }

    @Override
    public boolean wants(EntityRelationshipSnapshot target) {
        return active.getAsBoolean() && target.player();
    }

    /**
     * A pane is withheld from a viewer who cannot see the subject, from a subject that is
     * invisible or spectating, from the subject's own client, and from a sneaking subject when the
     * document asks for that.
     */
    public boolean visible(UUID viewer, EntityRelationshipSnapshot subject, NameplateDoc.Presentation presentation) {
        return (presentation.showSelf() || !viewer.equals(subject.id()))
            && (!presentation.hideInvisible() || !subject.invisible())
            && (!presentation.hideSpectator() || !subject.spectator())
            && (!presentation.hideSneaking() || !subject.sneaking())
            && (presentation.includeNpcs() || !subject.npc());
    }

    @Override
    public ScanPolicy scanPolicy() {
        return Gloss.instance == null ? ScanPolicy.INHERIT : new ScanPolicy(
            Gloss.instance.cfg().modules().nameplates().viewerRange(),
            Gloss.instance.cfg().modules().nameplates().maxSubjectsPerViewer(),
            Gloss.instance.cfg().modules().nameplates().refreshIntervalTicks());
    }

    @Override
    public boolean includesSelf() {
        for (NameplateRuntime runtime : documents.get()) {
            if (runtime.includesSelf()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Pane prepare(Context context) {
        Player viewer = context.viewer();
        EntityRelationshipSnapshot subject = context.relationship();
        if (!subject.player()) {
            return null;
        }
        EntityOverlayText.Snapshot snapshot = context.snapshot();
        ExprScope scope = context.scope();
        NameplateRuntime runtime = NameplateRuntime.select(documents.get(), scope);
        if (runtime == null) {
            retired(viewer.getUniqueId(), subject.id());
            return null;
        }
        NameplateDoc.Presentation presentation = runtime.presentation(scope);
        if (!visible(viewer.getUniqueId(), subject, presentation)) {
            retired(viewer.getUniqueId(), subject.id());
            return null;
        }
        List<String> lines = runtime.visibleLines(scope, scope);
        if (lines.isEmpty()) {
            retired(viewer.getUniqueId(), subject.id());
            return null;
        }
        String relation = runtime.relationColor(presentation, scope);
        List<String> colored = relation.isEmpty() ? lines : prefix(lines, relation);
        return new Pane(EntityOverlayText.prepareLines(Gloss.instance, viewer, colored,
            presentation.healthSegments(), presentation.healthBar(), snapshot, scope), presentation.style(), presentation.box(), presentation.offset());
    }

    @Override
    public void displayed(Player viewer, EntityRelationshipSnapshot subject) {
        if (suppression != null) {
            suppression.admit(viewer, subject.id(), subject.teamEntry(),
                Gloss.instance.bedrock() != null && Gloss.instance.bedrock().isBedrock(viewer.getUniqueId()));
        }
    }

    private static List<String> prefix(List<String> lines, String color) {
        return lines.stream().map(line -> color + line).toList();
    }

    @Override
    public void retired(UUID viewerId, UUID targetId) {
        if (suppression != null) {
            suppression.retire(viewerId, targetId);
        }
    }

}
