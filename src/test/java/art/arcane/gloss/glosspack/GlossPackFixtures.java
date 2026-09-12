package art.arcane.gloss.glosspack;

import art.arcane.gloss.doc.DocumentHashes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class GlossPackFixtures {
  static final String BOARD = """
      {"schemaVersion":2,"revision":1,"title":"&aLobby","lines":["&7one"]}""";
  static final String MENU_WITH_SERVER_COMMAND = """
      {"components":[{"id":"a","data":{"type":"button","icon":{"type":"text","text":"&aGo"},\
      "actions":[{"type":"command","source":"server","command":"op {player}"},\
      {"type":"command","source":"player","command":"spawn"}]}}]}""";

  private GlossPackFixtures() {
  }

  static byte[] archive(String manifestBody, Map<String, String> files) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(output)) {
      zip.putNextEntry(new ZipEntry("manifest.json"));
      zip.write(manifestBody.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      for (Map.Entry<String, String> file : files.entrySet()) {
        zip.putNextEntry(new ZipEntry(file.getKey()));
        zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return output.toByteArray();
  }

  static String manifest(String id, String packVersion, Map<String, String> documents,
                         boolean serverCommands) {
    StringBuilder refs = new StringBuilder();
    for (Map.Entry<String, String> document : documents.entrySet()) {
      String[] parts = document.getKey().split("/", 2);
      if (refs.length() > 0) {
        refs.append(',');
      }
      refs.append("{\"kind\":\"").append(parts[0])
          .append("\",\"id\":\"").append(parts[1].replace(".json", ""))
          .append("\",\"sha256\":\"").append(DocumentHashes.sha256(document.getValue()))
          .append("\"}");
    }
    return """
        {"format":"glosspack","version":1,"id":"%s","name":"Lobby Starter",\
        "packVersion":"%s","author":"Volmit",\
        "requires":{"gloss":">=3.0.0","schemas":{},"plugins":[],"itemProviders":[]},\
        "documents":[%s],"images":[],"serverCommands":%s}"""
        .formatted(id, packVersion, refs, serverCommands);
  }

  static Map<String, String> documents(Map<String, String> byRelativePath) {
    Map<String, String> files = new LinkedHashMap<>();
    byRelativePath.forEach((path, content) -> files.put("documents/" + path, content));
    return files;
  }
}
