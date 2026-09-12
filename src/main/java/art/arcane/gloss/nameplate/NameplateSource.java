package art.arcane.gloss.nameplate;

import art.arcane.gloss.Gloss;
import art.arcane.gloss.condition.GlossConditionContext;
import art.arcane.gloss.condition.GlossConditionScope;
import art.arcane.gloss.entity.EntityOverlaySource;
import art.arcane.gloss.entity.EntityOverlayText;
import art.arcane.gloss.expr.ExprScope;
import org.bukkit.GameMode;
import org.bukkit.entity.LivingEntity;
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
    /** Health segments a nameplate's {@code {bar}} token draws with. */
    public static final int HEALTH_SEGMENTS = 10;

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
    public boolean wants(LivingEntity target) {
        return active.getAsBoolean() && target instanceof Player;
    }

    /**
     * A pane is withheld from a viewer who cannot see the subject, from a subject that is
     * invisible or spectating, from the subject's own client, and from a sneaking subject when the
     * document asks for that.
     */
    public boolean visible(Player viewer, Player subject, boolean hideSneaking) {
        if (viewer.getUniqueId().equals(subject.getUniqueId())) {
            return false;
        }
        if (subject.isInvisible() || subject.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }
        if (hideSneaking && subject.isSneaking()) {
            return false;
        }
        return viewer.canSee(subject);
    }

    @Override
    public Pane prepare(Player viewer, LivingEntity target, EntityOverlayText.Snapshot snapshot) {
        if (!(target instanceof Player subject)) {
            return null;
        }
        ExprScope scope = new GlossConditionScope(Gloss.instance,
            GlossConditionContext.subject(viewer, subject, null, java.util.Map.of()));
        NameplateRuntime runtime = NameplateRuntime.select(documents.get(), scope);
        if (runtime == null) {
            retire(viewer, subject);
            return null;
        }
        NameplateDoc.Presentation presentation = runtime.presentation(scope);
        if (!visible(viewer, subject, presentation.hideSneaking())) {
            retire(viewer, subject);
            return null;
        }
        List<String> lines = runtime.visibleLines(scope, scope);
        if (lines.isEmpty()) {
            retire(viewer, subject);
            return null;
        }
        String relation = runtime.relationColor(presentation, scope);
        List<String> colored = relation.isEmpty() ? lines : prefix(lines, relation);
        admit(viewer, subject);
        return new Pane(EntityOverlayText.prepareLines(Gloss.instance, viewer, colored,
            HEALTH_SEGMENTS, snapshot), presentation.style(), presentation.box(), presentation.offset());
    }

    private static List<String> prefix(List<String> lines, String color) {
        return lines.stream().map(line -> color + line).toList();
    }

    private void admit(Player viewer, Player subject) {
        if (suppression != null) {
            suppression.admit(viewer, subject, Gloss.instance.bedrock().isBedrock(viewer));
        }
    }

    @Override
    public void retired(UUID viewerId, UUID targetId) {
        if (suppression != null) {
            suppression.retire(viewerId, targetId);
        }
    }

    private void retire(Player viewer, Player subject) {
        retired(viewer.getUniqueId(), subject.getUniqueId());
    }
}
