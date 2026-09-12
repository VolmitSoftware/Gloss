package art.arcane.gloss.command;

import art.arcane.gloss.doc.DocumentReviser;
import art.arcane.gloss.marker.MarkerDoc;

/** Revision handling for operator-written marker documents. */
final class MarkerDocReviser implements DocumentReviser<MarkerDoc> {
    static final MarkerDocReviser INSTANCE = new MarkerDocReviser();

    private MarkerDocReviser() {
    }

    @Override
    public long revisionOf(MarkerDoc value) {
        return value.revision();
    }

    @Override
    public MarkerDoc withRevision(MarkerDoc value, long revision) {
        return new MarkerDoc(value.schemaVersion(), revision, value.show(), value.anchor(), value.label(),
            value.icon(), value.color(), value.distanceScale(), value.hideWithin(), value.maxDistance(),
            value.beam(), value.edge(), value.trail(), value.audience(), value.lifetimeTicks(),
            value.waypoint());
    }
}
