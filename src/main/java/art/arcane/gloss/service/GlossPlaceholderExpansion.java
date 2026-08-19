package art.arcane.gloss.service;

import art.arcane.volmlib.util.bukkit.papi.PlaceholderKeyRegistry;
import art.arcane.volmlib.util.bukkit.papi.PlaceholderValues;
import art.arcane.volmlib.util.bukkit.papi.PlayerSnapshotStore;
import art.arcane.volmlib.util.bukkit.papi.VolmitPlaceholderExpansion;

import java.util.Objects;
import java.util.logging.Logger;

public final class GlossPlaceholderExpansion extends VolmitPlaceholderExpansion {
  private static final String IDENTIFIER = "gloss";
  private static final String REQUIRED_PLUGIN = "Gloss";
  private static final String AUTHOR = "Volmit Software";
  private static final String VERSION = "1.0.0";
  private static final String MENU_OPEN = "menu.open";
  private static final String MENU_ID = "menu.id";

  public GlossPlaceholderExpansion(PlayerSnapshotStore<String> openMenus, Logger logger) {
    super(IDENTIFIER, AUTHOR, VERSION, REQUIRED_PLUGIN, registry(openMenus), logger);
  }

  static PlaceholderKeyRegistry registry(PlayerSnapshotStore<String> openMenus) {
    Objects.requireNonNull(openMenus, "openMenus");

    return PlaceholderKeyRegistry.builder()
        .key(PlaceholderKeyRegistry.AVAILABLE, playerId -> PlaceholderValues.TRUE)
        .key(MENU_OPEN, playerId -> PlaceholderValues.bool(openMenus.get(playerId) != null))
        .key(MENU_ID, playerId -> PlaceholderValues.text(openMenus.get(playerId)))
        .build();
  }
}
