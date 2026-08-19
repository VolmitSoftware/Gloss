package art.arcane.gloss.doc;

import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ShippedDocumentTest {
    private static final String EXTENSION = ".json";

    @TestFactory
    Stream<DynamicNode> everyRegisteredKindShipsWhatItDeclares() {
        return ShippedDocumentCatalog.all().stream().map(ShippedDocumentTest::containerFor);
    }

    private static DynamicContainer containerFor(ShippedDocumentCatalog.Entry<?> entry) {
        List<DynamicTest> tests = new ArrayList<>();
        tests.add(DynamicTest.dynamicTest("names are distinct and non-empty",
            () -> assertDistinctNames(entry)));
        tests.add(DynamicTest.dynamicTest("classpath listing matches the hardcoded names",
            () -> assertResourceListing(entry)));
        for (String name : entry.names()) {
            tests.add(DynamicTest.dynamicTest(name + EXTENSION + " is shipped and parses clean",
                () -> assertParsesClean(entry, name)));
        }
        return DynamicContainer.dynamicContainer(entry.kind(), tests);
    }

    private static void assertDistinctNames(ShippedDocumentCatalog.Entry<?> entry) {
        assertFalse(entry.names().isEmpty(), entry.kind() + " registers no shipped documents");
        assertEquals(entry.names().size(), Set.copyOf(entry.names()).size(),
            entry.kind() + " declares a duplicate shipped name");
    }

    private static void assertResourceListing(ShippedDocumentCatalog.Entry<?> entry) throws URISyntaxException {
        URL url = ShippedDocumentTest.class.getResource("/defaults/" + entry.kind());
        assertNotNull(url, "missing resource directory /defaults/" + entry.kind());
        if (!"file".equals(url.getProtocol())) {
            return;
        }
        File directory = new File(url.toURI());
        String[] files = directory.list((parent, name) -> name.endsWith(EXTENSION));
        assertNotNull(files, "/defaults/" + entry.kind() + " is not listable");
        List<String> present = new ArrayList<>(files.length);
        for (String file : files) {
            present.add(file.substring(0, file.length() - EXTENSION.length()));
        }
        present.sort(String::compareTo);
        List<String> declared = new ArrayList<>(entry.names());
        declared.sort(String::compareTo);
        assertEquals(declared, present,
            entry.kind() + " shipped list and /defaults/" + entry.kind() + "/ contents diverge");
    }

    private static void assertParsesClean(ShippedDocumentCatalog.Entry<?> entry, String name) throws IOException {
        String resource = "/defaults/" + entry.kind() + "/" + name + EXTENSION;
        try (InputStream stream = ShippedDocumentTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream, "missing shipped document " + resource);
            String raw = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            Object value = entry.parser().parse(name + EXTENSION, raw);
            assertNotNull(value, resource + " parsed to null");
        }
    }
}
