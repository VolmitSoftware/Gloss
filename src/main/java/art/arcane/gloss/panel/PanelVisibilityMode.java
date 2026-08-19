package art.arcane.gloss.panel;

import com.google.gson.annotations.SerializedName;

public enum PanelVisibilityMode {
  @SerializedName("public")
  PUBLIC,
  @SerializedName("permission")
  PERMISSION,
  @SerializedName("hidden")
  HIDDEN
}
