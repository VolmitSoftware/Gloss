package art.arcane.gloss.editor;

import art.arcane.gloss.editor.relay.EditorRelayClient;
import art.arcane.gloss.editor.sync.EditorSyncDocumentKind;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@code schema/editor/editor-v1.endpoints.json}, which the web editor reads, says what this code does. */
class EditorEndpointsTableTest {
  private static JsonObject table() throws Exception {
    return JsonParser.parseString(Files.readString(Path.of("schema/editor/editor-v1.endpoints.json"),
        StandardCharsets.UTF_8)).getAsJsonObject();
  }

  @Test
  void errorStatusesScopesAndKindsMatchTheCode() throws Exception {
    JsonObject table = table();
    Map<String, Integer> statuses = new TreeMap<>();
    for (EditorError.Code code : EditorError.Code.values()) {
      statuses.put(code.wireName(), code.status());
    }
    Map<String, Integer> listed = new TreeMap<>();
    table.getAsJsonObject("errorStatus").entrySet().forEach((Map.Entry<String, JsonElement> entry) ->
        listed.put(entry.getKey(), entry.getValue().getAsInt()));
    Map<String, String> kinds = new TreeMap<>();
    for (EditorSyncDocumentKind kind : EditorSyncDocumentKind.ORDERED) {
      kinds.put(kind.wireName(), EditorScope.forKind(kind.wireName()).orElseThrow().wireName());
    }
    Map<String, String> listedKinds = new TreeMap<>();
    table.getAsJsonObject("kinds").entrySet().forEach((Map.Entry<String, JsonElement> entry) ->
        listedKinds.put(entry.getKey(), entry.getValue().getAsString()));

    assertEquals(statuses, listed);
    assertEquals(EditorScope.names(), strings(table, "scopes"));
    assertEquals(kinds, listedKinds);
    assertEquals(EditorScope.DEFAULT_PAIRING.stream().map(EditorScope::wireName).toList(),
        strings(table.getAsJsonObject("pairing"), "defaultScopes"));
  }

  @Test
  void pairingLimitsAndRelayConstantsMatchTheCode() throws Exception {
    JsonObject table = table();
    JsonObject pairing = table.getAsJsonObject("pairing");
    JsonObject limits = table.getAsJsonObject("limits");
    JsonObject relay = table.getAsJsonObject("relay");

    assertEquals(PairingCode.PREFIX, pairing.get("prefix").getAsString());
    assertEquals(EditorTokens.PAIRING_LIFETIME.toMinutes(), pairing.get("secretMinutes").getAsLong());
    assertEquals(EditorSettings.DEFAULT.tokenDays(), pairing.get("defaultTokenDays").getAsInt());
    assertEquals(EditorTokens.MAXIMUM_PAIRINGS, pairing.get("maximumWaitingLinks").getAsInt());
    assertEquals(EditorTokens.MAXIMUM_TOKENS, pairing.get("maximumTokens").getAsInt());
    assertEquals(EditorApi.UNCONFIRMED_ADMIN.toDays(), pairing.get("unconfirmedAdminDays").getAsLong());
    assertEquals(EditorListener.OPEN_BODY_BYTES, limits.get("openBodyBytes").getAsInt());
    assertEquals(EditorSettings.DEFAULT.requestBodyBytes(), limits.get("requestBodyBytes").getAsInt());
    assertEquals(EditorDocumentRoutes.PAGE_ENTRIES, limits.get("catalogPageEntries").getAsInt());
    assertEquals(EditorDocumentRoutes.CATALOG_BYTES, limits.get("catalogPageBytes").getAsInt());
    assertEquals(EditorDocumentRoutes.ANSWER_BYTES, limits.get("answerBytes").getAsInt());
    assertEquals(EditorDocumentRoutes.MAXIMUM_ANSWER_BYTES, limits.get("maximumAnswerBytes").getAsInt());
    assertEquals(EditorDocumentRoutes.RELAY_MARGIN, limits.get("relayMarginBytes").getAsInt());
    assertEquals(EditorDocumentRoutes.MAXIMUM_REFS, limits.get("documentsReadRefs").getAsInt());
    assertEquals(EditorDocumentRoutes.MAXIMUM_HASHES, limits.get("imagesReadHashes").getAsInt());
    assertEquals(EditorPublishRoutes.MAXIMUM_DOCUMENT_ENTRIES, limits.get("publishDocumentEntries").getAsInt());
    assertEquals(EditorPublishRoutes.MAXIMUM_IMAGE_ENTRIES, limits.get("publishImageEntries").getAsInt());
    assertEquals(EditorPublishRoutes.CONFIRM_DELETES_ABOVE, limits.get("confirmDeletesAbove").getAsInt());
    assertEquals(EditorPublishRoutes.CONFIRM_DELETES_PERCENT, limits.get("confirmDeletesPercent").getAsInt());
    assertEquals(EditorUploads.LIFETIME.toMinutes(), limits.get("stagedImageMinutes").getAsLong());
    assertEquals(EditorEvents.CATALOG_FRAME_BYTES, limits.get("catalogEventFrameBytes").getAsInt());
    assertEquals(EditorEvents.QUEUE_LIMIT, limits.get("eventQueueFrames").getAsInt());
    assertEquals(EditorListener.CONNECTIONS_PER_ADDRESS, limits.get("connectionsPerAddress").getAsInt());
    assertEquals(EditorPublishRoutes.IDEMPOTENCY.toHours(), limits.get("idempotencyHours").getAsLong());
    assertEquals(EditorRelayClient.CHALLENGE_PREFIX, relay.get("challengePrefix").getAsString());
    assertEquals(EditorRelayClient.PROOF_PREFIX, relay.get("proofPrefix").getAsString());
    assertEquals(EditorSettings.DEFAULT.relayFrameBytes(), relay.get("frameBytes").getAsInt());
    assertEquals(EditorApi.PROTOCOL, table.get("protocol").getAsInt());
    assertEquals(EditorListener.BASE, table.get("basePath").getAsString());
    assertEquals(EditorApi.COUNTER_HEADER, table.getAsJsonObject("headers").get("counter").getAsString());
  }

  private static List<String> strings(JsonObject object, String field) {
    List<String> values = new ArrayList<>();
    object.getAsJsonArray(field).forEach((JsonElement element) -> values.add(element.getAsString()));
    return values;
  }
}
