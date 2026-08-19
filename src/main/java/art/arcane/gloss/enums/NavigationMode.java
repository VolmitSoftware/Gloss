package art.arcane.gloss.enums;

import com.google.gson.annotations.SerializedName;

public enum NavigationMode {
  @SerializedName("push") PUSH,
  @SerializedName("replace") REPLACE,
  @SerializedName("back") BACK,
  @SerializedName("home") HOME,
  @SerializedName("close") CLOSE
}
