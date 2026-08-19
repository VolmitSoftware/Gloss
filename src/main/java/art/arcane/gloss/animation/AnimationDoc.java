package art.arcane.gloss.animation;

import art.arcane.gloss.doc.DocumentEnvelope;
import art.arcane.gloss.doc.DocumentParsers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record AnimationDoc(int schemaVersion, long revision, String mode, long frameIntervalMs, List<String> frames) {
    public static final String KIND = "animations";
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final long MIN_FRAME_INTERVAL_MS = 1L;
    public static final long MAX_FRAME_INTERVAL_MS = 60_000L;

    public AnimationDoc {
        DocumentEnvelope.requireSchemaVersion(KIND, schemaVersion, CURRENT_SCHEMA_VERSION);
        DocumentEnvelope.requireRevision(KIND, revision);
        mode = requireMode(mode);
        frameIntervalMs = Math.max(MIN_FRAME_INTERVAL_MS, Math.min(MAX_FRAME_INTERVAL_MS, frameIntervalMs));
        frames = copyFrames(frames);
    }

    public AnimationMode toMode() {
        return AnimationMode.valueOf(mode.toUpperCase(Locale.ROOT));
    }

    public static AnimationDoc parse(String fileName, String raw) {
        return DocumentParsers.parseJson(fileName, raw, AnimationDoc.class);
    }

    private static String requireMode(String mode) {
        if (mode == null || mode.isBlank()) {
            throw new IllegalArgumentException("animation requires a mode");
        }
        String normalized = mode.toLowerCase(Locale.ROOT);
        try {
            AnimationMode.valueOf(normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("unknown animation mode: " + mode);
        }
        return normalized;
    }

    private static List<String> copyFrames(List<String> frames) {
        if (frames == null || frames.isEmpty()) {
            throw new IllegalArgumentException("animation requires at least one frame");
        }
        List<String> copied = new ArrayList<>(frames.size());
        for (String frame : frames) {
            copied.add(frame == null ? "" : frame);
        }
        return List.copyOf(copied);
    }
}
