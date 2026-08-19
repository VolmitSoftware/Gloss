package art.arcane.gloss.config.action;

import art.arcane.gloss.api.HoloClickTrigger;
import art.arcane.gloss.enums.MenuActionType;
import art.arcane.gloss.enums.SoundSource;
import art.arcane.volmlib.util.bukkit.registry.RegistryUtil;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public record SoundActionData(String sound, SoundSource source, Float volume,
                              Float pitch, HoloClickTrigger trigger) implements MenuActionData {

  private static final ConcurrentMap<String, Optional<Sound>> RESOLVED_SOUNDS = new ConcurrentHashMap<>();

  public MenuActionType getType() {
    return MenuActionType.SOUND;
  }

  public SoundSource sourceOrDefault() {
    return source == null ? SoundSource.MASTER : source;
  }

  public float volumeOrDefault() {
    return volume == null ? 1F : volume;
  }

  public float pitchOrDefault() {
    return pitch == null ? 1F : pitch;
  }

  public Sound resolveSound() {
    if (sound == null || sound.isBlank())
      return null;

    return RESOLVED_SOUNDS.computeIfAbsent(sound, SoundActionData::findSound).orElse(null);
  }

  private static Optional<Sound> findSound(String sound) {
    try {
      NamespacedKey key = NamespacedKey.fromString(sound);
      return Optional.ofNullable(key == null ? null : RegistryUtil.find(Sound.class, key));
    } catch (RuntimeException | LinkageError ex) {
      return Optional.empty();
    }
  }
}
