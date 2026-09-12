package art.arcane.gloss.prompt;

import art.arcane.gloss.config.action.BookActionData;
import art.arcane.gloss.config.action.MenuActionData;
import art.arcane.gloss.config.action.PromptActionData;
import art.arcane.gloss.doc.DocumentParsers;
import art.arcane.gloss.enums.MenuActionType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The two actions that hand a player something to read or something to type into. */
class BookActionTest {

    @Test
    void aBookCarriesItsTitleAuthorAndPages() {
        BookActionData book = (BookActionData) action("""
            { "type": "book", "title": "Rules", "author": "Server",
              "pages": [ "&6Rules\\n\\n1. Be kind.", "2. Have fun." ] }
            """);
        assertEquals(MenuActionType.BOOK, book.getType());
        assertEquals("Rules", book.title());
        assertEquals("Server", book.author());
        assertEquals(2, book.pages().size());
        assertNull(book.invalidReason());
    }

    @Test
    void aBookWithNoPagesCanNeverDoAnything() {
        BookActionData book = (BookActionData) action("{ \"type\": \"book\", \"title\": \"Rules\" }");
        assertNotNull(book.invalidReason());
    }

    @Test
    void aBookRefusesMorePagesThanTheClientCanHold() {
        StringBuilder pages = new StringBuilder();
        for (int index = 0; index < BookActionData.MAX_PAGES + 1; index++) {
            pages.append(pages.isEmpty() ? "" : ",").append("\"page ").append(index).append('"');
        }
        String raw = "{ \"type\": \"book\", \"pages\": [" + pages + "] }";
        RuntimeException failure = assertThrows(RuntimeException.class, () -> action(raw));
        assertTrue(rootMessage(failure).contains("at most " + BookActionData.MAX_PAGES + " pages"),
            rootMessage(failure));
    }

    @Test
    void aPromptNamesItsEditorVariableAndFollowUp() {
        PromptActionData prompt = (PromptActionData) action("""
            { "type": "prompt", "kind": "sign", "var": "search", "label": "Search",
              "initial": "", "timeoutTicks": 400,
              "then": [ { "type": "message", "message": "searching {{ input.value }}" } ] }
            """);
        assertEquals(MenuActionType.PROMPT, prompt.getType());
        assertEquals("sign", prompt.kind());
        assertEquals("search", prompt.variable());
        assertEquals(400, prompt.timeoutTicks());
        assertEquals(1, prompt.then().size());
        assertNull(prompt.invalidReason());
    }

    @Test
    void aPromptWithAnUnknownEditorIsRefused() {
        RuntimeException failure = assertThrows(RuntimeException.class,
            () -> action("{ \"type\": \"prompt\", \"kind\": \"hologram\", \"var\": \"search\" }"));
        assertTrue(rootMessage(failure).contains("hologram"), rootMessage(failure));
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        String message = failure.getMessage();
        while (current.getCause() != null) {
            current = current.getCause();
            if (current.getMessage() != null) {
                message = current.getMessage();
            }
        }
        return message == null ? "" : message;
    }

    @Test
    void aPromptThatWritesNowhereAndRunsNothingCanNeverDoAnything() {
        PromptActionData prompt = (PromptActionData) action("{ \"type\": \"prompt\", \"kind\": \"chat\" }");
        assertNotNull(prompt.invalidReason());
    }

    @Test
    void aPromptRequestClampsItsTimeout() {
        PromptRequest request = new PromptRequest("chat", "search", "Search", "", List.of(), 0, null);
        assertEquals(PromptRequest.DEFAULT_TIMEOUT_TICKS, request.timeoutTicks());

        PromptRequest capped = new PromptRequest("chat", "search", "Search", "", List.of(), 999_999, null);
        assertEquals(PromptRequest.MAX_TIMEOUT_TICKS, capped.timeoutTicks());
    }

    private static MenuActionData action(String raw) {
        MenuActionData parsed = DocumentParsers.GSON.fromJson(raw, MenuActionData.class);
        assertInstanceOf(MenuActionData.class, parsed);
        return parsed;
    }
}
