package art.arcane.gloss.config.components;

import com.google.gson.annotations.SerializedName;

public enum HoverEasing {
  @SerializedName("linear")
  LINEAR {
    @Override
    public double apply(double progress) {
      return progress;
    }
  },
  @SerializedName("ease_out_cubic")
  EASE_OUT_CUBIC {
    @Override
    public double apply(double progress) {
      double inverse = 1D - progress;
      return 1D - inverse * inverse * inverse;
    }
  },
  @SerializedName("ease_in_out_cubic")
  EASE_IN_OUT_CUBIC {
    @Override
    public double apply(double progress) {
      if (progress < 0.5D) {
        return 4D * progress * progress * progress;
      }
      double inverse = -2D * progress + 2D;
      return 1D - inverse * inverse * inverse / 2D;
    }
  },
  @SerializedName("back_out")
  BACK_OUT {
    @Override
    public double apply(double progress) {
      double shifted = progress - 1D;
      return 1D + 2.70158D * shifted * shifted * shifted + 1.70158D * shifted * shifted;
    }
  };

  public static final HoverEasing DEFAULT = EASE_OUT_CUBIC;

  public abstract double apply(double progress);

  public static HoverEasing resolve(HoverEasing easing) {
    return easing == null ? DEFAULT : easing;
  }
}
