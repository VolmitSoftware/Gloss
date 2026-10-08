package art.arcane.gloss.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FailingImportTransaction {
    private FailingImportTransaction() {
    }

    public static GlossProjectTransaction afterMenuWrite(Path root, AtomicBoolean injected) {
        return new GlossProjectTransaction(root, directory -> {
            if (directory.equals(root.resolve("menus")) && Files.exists(root.resolve("menus/main.json"))
                && injected.compareAndSet(false, true)) {
                throw new IOException("injected import failure");
            }
        });
    }
}
