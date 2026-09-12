package art.arcane.gloss.marker;

import art.arcane.gloss.api.MarkerAnchor;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.config.icon.MenuIconData;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;

import java.util.Objects;

public record MarkerDoc(int schemaVersion, long revision, ShowCondition show, MarkerAnchor anchor,
                        String label, MenuIconData icon, String color, String distanceScale,
                        Double hideWithin, Double maxDistance, MarkerSpec.Beam beam, MarkerSpec.Edge edge,
                        MarkerSpec.Trail trail, Audience audience, Long lifetimeTicks, Boolean waypoint) {
    public static final String KIND = "markers";
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public MarkerDoc {
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        show = show == null ? ShowCondition.ALWAYS : show;
        anchor = Objects.requireNonNull(anchor, "a marker requires an anchor");
        label = label == null ? "" : label;
        MarkerColors.parse(color, "marker color");
        distanceScale = distanceScale == null || distanceScale.isBlank() ? null : distanceScale.trim();
        hideWithin = hideWithin == null ? 0.0D : hideWithin;
        maxDistance = maxDistance == null ? MarkerSpec.DEFAULT_MAX_DISTANCE : maxDistance;
        beam = beam == null ? MarkerSpec.Beam.off() : beam;
        edge = edge == null ? MarkerSpec.Edge.off() : edge;
        trail = trail == null ? MarkerSpec.Trail.off() : trail;
        audience = audience == null ? Audience.ALWAYS : audience;
        lifetimeTicks = lifetimeTicks == null ? 0L : Math.max(0L, lifetimeTicks);
        waypoint = waypoint != null && waypoint;
    }

    public static MarkerDoc parse(String fileName, String raw) {
        return DocumentParsers.parseJson(fileName, raw, MarkerDoc.class);
    }

    public int rgb() {
        return MarkerColors.parse(color, "marker color");
    }

    public MarkerSpec toSpec(String id) {
        return new MarkerSpec(id, anchor, label, icon, rgb(), distanceScale, hideWithin, maxDistance,
            beam, edge, trail, audience.when(), lifetimeTicks, waypoint);
    }
}
