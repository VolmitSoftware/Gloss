package art.arcane.gloss.enums;

import com.google.gson.annotations.SerializedName;
import org.bukkit.SoundCategory;

public enum SoundSource {
  @SerializedName("master") MASTER(SoundCategory.MASTER),
  @SerializedName("music") MUSIC(SoundCategory.MUSIC),
  @SerializedName("record") RECORD(SoundCategory.RECORDS),
  @SerializedName("weather") WEATHER(SoundCategory.WEATHER),
  @SerializedName("block") BLOCK(SoundCategory.BLOCKS),
  @SerializedName("hostile") HOSTILE(SoundCategory.HOSTILE),
  @SerializedName("neutral") NEUTRAL(SoundCategory.NEUTRAL),
  @SerializedName("player") PLAYER(SoundCategory.PLAYERS),
  @SerializedName("ambient") AMBIENT(SoundCategory.AMBIENT),
  @SerializedName("voice") VOICE(SoundCategory.VOICE);

  private final SoundCategory category;

  SoundSource(SoundCategory category) {
    this.category = category;
  }

  public SoundCategory getCategory() {
    return category;
  }
}
