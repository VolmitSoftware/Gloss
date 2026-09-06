package art.arcane.gloss.preview;

import art.arcane.gloss.particle.ParticleText;
import art.arcane.gloss.api.IconDisplayStyle;
import art.arcane.gloss.api.HologramBox;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.Inventory;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

public sealed interface PreviewElement
    permits PreviewElement.Panel, PreviewElement.Cell, PreviewElement.Slot, PreviewElement.Label {

  IconDisplayStyle DEFAULT_ITEM_STYLE = new IconDisplayStyle(null, null, null, null, null, null, null,
      15, 15, null, null, null, null, null, null, null, null, null);

  int x();

  int y();

  int z();

  IconDisplayStyle style();

  record Panel(int x, int y, int z, int width, int height, int color, IconDisplayStyle style) implements PreviewElement {
    public Panel {
      style = IconDisplayStyle.resolve(style);
    }
  }

  record Cell(int x, int y, int z, int size, IntSupplier color, IconDisplayStyle style) implements PreviewElement {
    public Cell {
      style = IconDisplayStyle.resolve(style);
    }
  }

  record Slot(int x, int y, int z, int size, int wellColor, Inventory inventory, int slot, IconDisplayStyle style, IconDisplayStyle textStyle) implements PreviewElement {
    public Slot {
      style = style == null ? DEFAULT_ITEM_STYLE : style;
      textStyle = IconDisplayStyle.resolve(textStyle);
    }
  }

  record Label(int x, int y, int z, Supplier<Component> text,
               Supplier<ParticleText.Rendered> particleText, int backgroundColor, IconDisplayStyle style, HologramBox box) implements PreviewElement {
    public Label {
      style = IconDisplayStyle.resolve(style);
      box = box == null ? HologramBox.defaults() : box;
    }
  }
}
