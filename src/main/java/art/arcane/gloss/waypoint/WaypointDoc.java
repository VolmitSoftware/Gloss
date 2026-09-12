package art.arcane.gloss.waypoint;

import art.arcane.gloss.api.MarkerAnchor;
import art.arcane.gloss.api.WaypointSpec;
import art.arcane.gloss.condition.ShowCondition;
import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;
import art.arcane.gloss.marker.Audience;
import art.arcane.gloss.marker.MarkerColors;

import java.util.Objects;

public record WaypointDoc(int schemaVersion, long revision, ShowCondition show, MarkerAnchor anchor,
                          String color, String style, Double range, Audience audience) {
    public static final String KIND = "waypoints";
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public WaypointDoc {
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        show = show == null ? ShowCondition.ALWAYS : show;
        anchor = Objects.requireNonNull(anchor, "a waypoint requires an anchor");
        MarkerColors.parse(color, "waypoint color");
        WaypointStyle.parse(style);
        range = range == null ? 0.0D : range;
        audience = audience == null ? Audience.ALWAYS : audience;
    }

    public static WaypointDoc parse(String fileName, String raw) {
        return DocumentParsers.parseJson(fileName, raw, WaypointDoc.class);
    }

    public int rgb() {
        return MarkerColors.parse(color, "waypoint color");
    }

    public WaypointStyle waypointStyle() {
        return WaypointStyle.parse(style);
    }

    public WaypointSpec toSpec(String id) {
        return new WaypointSpec(id, anchor, rgb(), waypointStyle().serializedName(), range);
    }
}
