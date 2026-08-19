package art.arcane.gloss.editor.sync;

import art.arcane.gloss.GlossConfig;

interface EditorSyncSettingsView {
  boolean enabled();

  String endpoint();

  String createToken();

  int sessionMinutes();

  int pollSeconds();

  int maximumProjectBytes();

  String builderUrl();

  static EditorSyncSettingsView runtime() {
    return new EditorSyncSettingsView() {
      @Override
      public boolean enabled() {
        return GlossConfig.current().editorSync().enabled();
      }

      @Override
      public String endpoint() {
        return GlossConfig.current().editorSync().endpoint();
      }

      @Override
      public String createToken() {
        return GlossConfig.current().editorSync().createToken();
      }

      @Override
      public int sessionMinutes() {
        return GlossConfig.current().editorSync().sessionMinutes();
      }

      @Override
      public int pollSeconds() {
        return GlossConfig.current().editorSync().pollSeconds();
      }

      @Override
      public int maximumProjectBytes() {
        return GlossConfig.current().editorSync().maxProjectMiB() * 1024 * 1024;
      }

      @Override
      public String builderUrl() {
        return GlossConfig.current().editorSync().builderUrl();
      }
    };
  }
}
