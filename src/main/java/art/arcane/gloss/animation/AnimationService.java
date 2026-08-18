package art.arcane.gloss.animation;

import art.arcane.gloss.Gloss;
import art.arcane.volmlib.util.io.FolderWatcher;
import art.arcane.volmlib.util.io.IO;
import art.arcane.volmlib.util.json.JSONArray;
import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.volmlib.util.math.M;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class AnimationService {
    private static final String FUNCTION_PREFIX = "animation.";

    private final Gloss plugin;
    private final File folder;
    private final List<String> registeredFunctions;
    private volatile List<AnimationClip> clips;
    private volatile FolderWatcher watcher;
    private int watchTaskId;

    public AnimationService(Gloss plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "animations");
        this.registeredFunctions = new ArrayList<>();
        this.clips = List.of();
        this.watchTaskId = -1;
    }

    public void enable() {
        if (!plugin.cfg().animations().enabled()) {
            return;
        }
        loadClips();
        watcher = new FolderWatcher(folder);
        watchTaskId = plugin.scheduler().ar(this::pollWatcher, plugin.cfg().hotload().watchIntervalTicks());
    }

    public void disable() {
        if (watchTaskId >= 0) {
            plugin.scheduler().car(watchTaskId);
            watchTaskId = -1;
        }
        watcher = null;
        unregisterFunctions();
        clips = List.of();
    }

    public void reload() {
        disable();
        enable();
    }

    public List<String> names() {
        List<AnimationClip> snapshot = clips;
        List<String> out = new ArrayList<>(snapshot.size());
        for (AnimationClip clip : snapshot) {
            out.add(clip.id());
        }
        return out;
    }

    private void pollWatcher() {
        FolderWatcher active = watcher;
        if (active == null || !active.checkModified()) {
            return;
        }

        loadClips();
    }

    private synchronized void loadClips() {
        unregisterFunctions();
        if (!folder.isDirectory() && !folder.mkdirs()) {
            Gloss.warn("Unable to create animations folder at " + folder.getAbsolutePath());
        }

        File[] files = listJson();
        if (files.length == 0) {
            writeExample();
            files = listJson();
        }

        List<AnimationClip> loaded = new ArrayList<>(files.length);
        for (File file : files) {
            AnimationClip clip = readClip(file);
            if (clip != null) {
                loaded.add(clip);
            }
        }

        loaded.sort(Comparator.comparing(AnimationClip::id));
        clips = List.copyOf(loaded);
        for (AnimationClip clip : loaded) {
            String name = FUNCTION_PREFIX + clip.id();
            plugin.text().registerFunction(name, player -> clip.frameAt(M.ms()));
            registeredFunctions.add(name);
        }
    }

    private synchronized void unregisterFunctions() {
        for (String name : registeredFunctions) {
            plugin.text().unregisterFunction(name);
        }
        registeredFunctions.clear();
    }

    private File[] listJson() {
        File[] files = folder.listFiles(file -> file.isFile() && file.getName().endsWith(".json"));
        return files == null ? new File[0] : files;
    }

    private AnimationClip readClip(File file) {
        String id = file.getName().substring(0, file.getName().length() - 5);
        try {
            JSONObject json = new JSONObject(IO.readAll(file));
            double framerate = json.getDouble("target-framerate");
            AnimationMode mode = AnimationMode.valueOf(json.getString("animation-type").toUpperCase(Locale.ROOT));
            JSONArray frameArray = json.getJSONArray("frames");
            List<String> frames = new ArrayList<>(frameArray.length());
            for (int i = 0; i < frameArray.length(); i++) {
                frames.add(frameArray.getString(i));
            }

            if (frames.isEmpty()) {
                Gloss.warn("Animation " + file.getName() + " has no frames.");
                return null;
            }

            return new AnimationClip(id, framerate, mode, frames);
        } catch (IOException | RuntimeException failure) {
            Gloss.warn("Failed to load animation " + file.getName() + ": " + failure.getMessage());
            return null;
        }
    }

    private void writeExample() {
        JSONObject json = new JSONObject();
        json.put("target-framerate", 2.0D);
        json.put("animation-type", AnimationMode.ASCEND.name());
        JSONArray frames = new JSONArray();
        frames.put("&cGloss");
        frames.put("&6Gloss");
        frames.put("&aGloss");
        frames.put("&bGloss");
        json.put("frames", frames);
        try {
            IO.writeAll(new File(folder, "rainbow.json"), json.toString(4));
        } catch (IOException failure) {
            Gloss.warn("Failed to write example animation rainbow.json: " + failure.getMessage());
        }
    }
}
