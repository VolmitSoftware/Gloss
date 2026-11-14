package art.arcane.gloss.hologram;

import art.arcane.gloss.command.CommandGlossHologram;
import org.bukkit.command.CommandSender;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HologramInfoUnloadedWorldTest {
  @TempDir
  File dataFolder;

  @Test
  void infoOnAHologramWhoseWorldIsNotLoadedSaysSoAndStillListsItsLines() {
    try (CharacterizationHarness harness = new CharacterizationHarness(dataFolder)) {
      CharacterizationHarness.WorldState world = harness.world("overworld");
      PersistentHologram hologram = harness.persistent("lost", harness.at(world, 0.5D, 64.0D, 0.5D));
      hologram.setLines(List.of("First line"));
      HologramDoc doc = hologram.toDoc(1L);
      hologram.apply(new HologramDoc(doc.schemaVersion(), doc.revision(),
          new HologramDoc.Anchor("typed_by_mistake", new Vector(1, 2, 3)), doc.lines(), doc.style(), doc.box(),
          doc.yaw(), doc.pitch(), doc.particleLayers(), doc.show(), doc.pages(), doc.actions(), doc.hitbox(),
          doc.viewDistance(), doc.refreshTicks(), doc.variants(), doc.refresh()));
      List<String> received = new ArrayList<>();

      new CommandGlossHologram(harness.gloss).info(console(received), "lost");

      String told = String.join("\n", received);
      assertTrue(told.contains("lost") && told.contains("typed_by_mistake") && told.contains("not loaded"), told);
      assertTrue(told.contains("First line"), told);
    }
  }

  private static CommandSender console(List<String> received) {
    return (CommandSender) Proxy.newProxyInstance(HologramInfoUnloadedWorldTest.class.getClassLoader(),
        new Class<?>[]{CommandSender.class}, (proxy, method, args) -> switch (method.getName()) {
          case "sendMessage", "sendRichMessage", "sendPlainMessage" -> {
            received.add(String.valueOf(args[0]));
            yield null;
          }
          case "hasPermission" -> true;
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          case "getName" -> "CONSOLE";
          default -> null;
        });
  }
}
