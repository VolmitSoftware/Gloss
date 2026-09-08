package art.arcane.gloss.paper;

import art.arcane.gloss.preview.ContainerPreviewAccess;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperRelocationBoundaryTest {

    private static final String PAPER_PACKAGE = "art/arcane/gloss/paper";

    private static final Map<String, String> RELOCATIONS = Map.of(
            "net/kyori/", "art/arcane/gloss/libs/kyori/",
            "org/apache/commons/", "art/arcane/gloss/libs/commons/",
            "com/moandjiezana/toml/", "art/arcane/gloss/libs/toml/",
            "org/bstats/", "art/arcane/gloss/libs/bstats/",
            "com/github/retrooper/packetevents/", "art/arcane/gloss/libs/packetevents/api/",
            "io/github/retrooper/packetevents/", "art/arcane/gloss/libs/packetevents/impl/",
            "io/github/slimjar/", "art/arcane/gloss/libs/slimjar/");

    @Test
    void serverFacingCallSitesNameNothingTheShadedJarRelocates() throws IOException, URISyntaxException {
        List<Path> compiled = compiledPaperClasses();
        assertFalse(compiled.isEmpty(), "No compiled classes found under " + PAPER_PACKAGE);
        List<String> violations = new ArrayList<>();
        for (Path candidate : compiled) {
            collectViolations(candidate, violations);
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    private static List<Path> compiledPaperClasses() throws IOException, URISyntaxException {
        Path mainClasses = Path.of(ContainerPreviewAccess.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        Path paperClasses = mainClasses.resolve(PAPER_PACKAGE);
        if (!Files.isDirectory(paperClasses)) {
            return List.of();
        }
        try (Stream<Path> tree = Files.walk(paperClasses)) {
            return tree.filter(path -> path.getFileName().toString().endsWith(".class")).sorted().toList();
        }
    }

    private static void collectViolations(Path classFile, List<String> violations) throws IOException {
        ClassModel model = ClassFile.of().parse(Files.readAllBytes(classFile));
        String origin = model.thisClass().asInternalName();
        for (PoolEntry entry : model.constantPool()) {
            if (!(entry instanceof MemberRefEntry reference)) {
                continue;
            }
            String owner = reference.owner().asInternalName();
            if (relocatedPrefix(owner) != null) {
                continue;
            }
            String descriptor = reference.type().stringValue();
            if (!namesRelocatedType(descriptor)) {
                continue;
            }
            violations.add(origin + " calls " + owner + "." + reference.name().stringValue() + descriptor
                    + " but the shaded jar emits " + relocate(descriptor)
                    + ", a descriptor no server member matches");
        }
    }

    private static String relocatedPrefix(String internalName) {
        for (String prefix : RELOCATIONS.keySet()) {
            if (internalName.startsWith(prefix)) {
                return prefix;
            }
        }
        return null;
    }

    private static boolean namesRelocatedType(String descriptor) {
        for (String prefix : RELOCATIONS.keySet()) {
            if (descriptor.contains("L" + prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String relocate(String descriptor) {
        String relocated = descriptor;
        for (Map.Entry<String, String> relocation : RELOCATIONS.entrySet()) {
            relocated = relocated.replace("L" + relocation.getKey(), "L" + relocation.getValue());
        }
        return relocated;
    }
}
