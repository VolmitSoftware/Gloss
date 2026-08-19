package art.arcane.gloss.importer;

public final class LegacyImportBusyException extends IllegalStateException {
  public LegacyImportBusyException() {
    super("another legacy hologram import operation is already running");
  }
}
