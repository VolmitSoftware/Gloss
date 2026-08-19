package art.arcane.gloss.service;

import art.arcane.volmlib.util.bukkit.papi.PlaceholderRegistration;
import art.arcane.volmlib.util.bukkit.papi.PlayerSnapshotStore;

import java.util.logging.Logger;

public final class GlossPlaceholderInstaller {
  private GlossPlaceholderInstaller() {
  }

  public static void install(PlaceholderRegistration registration, PlayerSnapshotStore<String> openMenus, Logger logger) {
    registration.register(() -> new GlossPlaceholderExpansion(openMenus, logger));
  }
}
