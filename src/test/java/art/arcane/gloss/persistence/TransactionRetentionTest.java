package art.arcane.gloss.persistence;

import art.arcane.gloss.GlossConfig;
import art.arcane.gloss.config.GlossConfigFile;
import art.arcane.volmlib.util.config.ConfigExposePolicy;
import art.arcane.volmlib.util.config.TomlCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionRetentionTest {
    @TempDir
    Path data;

    @Test
    void countAndByteLimitsBothPruneOldestArchives() throws Exception {
        GlossProjectTransaction transaction = new GlossProjectTransaction(data);
        Path first = archive(transaction, 1);
        Path second = archive(transaction, 2);
        Path newest = archive(transaction, 3);
        transaction.configureRetention(new GlossProjectTransaction.RetentionPolicy(2, Long.MAX_VALUE));
        transaction.pruneRetainedBackups();
        assertFalse(Files.exists(first));
        assertTrue(Files.exists(second));
        assertTrue(Files.exists(newest));
        assertEquals(2, transaction.retentionStatus().retainedBackups());

        long newestBytes = bytes(newest);
        transaction.configureRetention(new GlossProjectTransaction.RetentionPolicy(100, newestBytes + 1));
        transaction.pruneRetainedBackups();
        assertFalse(Files.exists(second));
        assertTrue(Files.exists(newest));
        assertEquals(newestBytes, transaction.retentionStatus().retainedBytes());
        assertFalse(transaction.retentionStatus().overBudget());
        assertFalse(transaction.retentionPending());
    }

    @Test
    void oversizedNewestBackupRemainsProtected() throws Exception {
        GlossProjectTransaction transaction = new GlossProjectTransaction(data);
        Path oldest = archive(transaction, 1);
        Path newest = archive(transaction, 2);
        transaction.configureRetention(new GlossProjectTransaction.RetentionPolicy(1, 1));
        transaction.pruneRetainedBackups();
        assertFalse(Files.exists(oldest));
        assertTrue(Files.exists(newest.resolve("backup/menus/main.json")));
        assertEquals(1, transaction.retentionStatus().protectedBackups());
        assertEquals(bytes(newest), transaction.retentionStatus().protectedBytes());
        assertTrue(transaction.retentionStatus().overBudget());
    }

    @Test
    void damagedNewestCandidatesCannotDisplaceTheNewestIntactRollbackArchive() throws Exception {
        GlossProjectTransaction transaction = new GlossProjectTransaction(data);
        Path oldest = archive(transaction, 1);
        Path intact = archive(transaction, 2);
        Path truncated = archive(transaction, 3);
        Path sameSizeCorruption = archive(transaction, 4);
        Files.writeString(truncated.resolve("backup/menus/main.json"), "truncated");
        Files.writeString(sameSizeCorruption.resolve("backup/menus/main.json"), "x".repeat(2000));
        transaction.configureRetention(new GlossProjectTransaction.RetentionPolicy(1, 1));

        transaction.pruneRetainedBackups();

        assertFalse(Files.exists(oldest));
        assertEquals("1".repeat(2000), Files.readString(intact.resolve("backup/menus/main.json")));
        assertEquals("truncated", Files.readString(truncated.resolve("backup/menus/main.json")));
        assertEquals("x".repeat(2000), Files.readString(sameSizeCorruption.resolve("backup/menus/main.json")));
        assertEquals(3, transaction.retentionStatus().retainedBackups());
        assertEquals(3, transaction.retentionStatus().protectedBackups());
        assertTrue(transaction.retentionStatus().overBudget());
    }

    @Test
    void recoveryNeverPrunesBeforeRecoveredConfigurationCanBeApplied() throws Exception {
        GlossProjectTransaction transaction = new GlossProjectTransaction(data);
        Path first = archive(transaction, 1);
        Path second = archive(transaction, 2);
        Path newest = archive(transaction, 3);
        assertFalse(transaction.retentionPending());
        transaction.configureRetention(new GlossProjectTransaction.RetentionPolicy(1, Long.MAX_VALUE));
        transaction.recover();
        assertTrue(Files.exists(first));
        assertTrue(Files.exists(second));
        assertTrue(Files.exists(newest));
        assertTrue(transaction.retentionPending());
        transaction.pruneRetainedBackups();
        assertFalse(Files.exists(first));
        assertFalse(Files.exists(second));
        assertTrue(Files.exists(newest));
    }

    @Test
    void unresolvedTransactionBlocksAllArchiveDeletionUntilRecovery() throws Exception {
        GlossProjectTransaction transaction = new GlossProjectTransaction(data);
        Path first = archive(transaction, 1);
        Path newest = archive(transaction, 2);
        Path target = data.resolve("menus/main.json");
        GlossProjectTransaction.Pending pending = transaction.apply("pending",
            Map.of(target, GlossProjectTransaction.Mutation.write("pending".getBytes(StandardCharsets.UTF_8))),
            Map.of(target, Files.readAllBytes(target)));
        transaction.configureRetention(new GlossProjectTransaction.RetentionPolicy(1, Long.MAX_VALUE));
        transaction.pruneRetainedBackups();
        assertTrue(Files.exists(first));
        assertTrue(Files.exists(newest));
        assertTrue(Files.exists(pending.transactionDirectory()));
        assertTrue(transaction.retentionStatus().recoveryBlocked());
        transaction.recover();
        transaction.pruneRetainedBackups();
        assertFalse(Files.exists(first));
        assertTrue(Files.exists(newest));
        assertFalse(transaction.retentionStatus().recoveryBlocked());
        assertEquals("2".repeat(2000), Files.readString(target));
    }

    @Test
    void unknownNonterminalAndMissingOriginalArchivesRemainProtected() throws Exception {
        GlossProjectTransaction transaction = new GlossProjectTransaction(data);
        Path nonterminal = archive(transaction, 1);
        Path valid = archive(transaction, 2);
        Path missingOriginal = archive(transaction, 3);
        Path journal = nonterminal.resolve("journal.json");
        Files.writeString(journal, Files.readString(journal).replace("committed", "published"));
        Files.delete(missingOriginal.resolve("backup/menus/main.json"));
        Path unknown = data.resolve("editor-sync-backups/operator-files");
        Files.createDirectories(unknown);
        Files.writeString(unknown.resolve("notes.txt"), "preserve");
        transaction.configureRetention(new GlossProjectTransaction.RetentionPolicy(1, 1));
        transaction.pruneRetainedBackups();
        assertTrue(Files.exists(nonterminal));
        assertTrue(Files.exists(missingOriginal));
        assertTrue(Files.exists(valid));
        assertEquals("preserve", Files.readString(unknown.resolve("notes.txt")));
        assertEquals(4, transaction.retentionStatus().protectedBackups());
        assertTrue(transaction.retentionStatus().overBudget());
    }

    @Test
    void symlinkArchiveContentsNeverDeleteOrReadTheirTarget() throws Exception {
        GlossProjectTransaction transaction = new GlossProjectTransaction(data);
        Path unsafe = archive(transaction, 1);
        Path newest = archive(transaction, 2);
        Path external = data.resolve("external-kept.txt");
        Files.writeString(external, "keep");
        Files.createSymbolicLink(unsafe.resolve("linked"), external);
        transaction.configureRetention(new GlossProjectTransaction.RetentionPolicy(1, 1));
        transaction.pruneRetainedBackups();
        assertTrue(Files.isSymbolicLink(unsafe.resolve("linked")));
        assertEquals("keep", Files.readString(external));
        assertTrue(Files.exists(newest));
        assertEquals(2, transaction.retentionStatus().protectedBackups());
    }

    @Test
    void cleanupFailureKeepsNewestAndRemainingArchivesAndCanBeRetried() throws Exception {
        AtomicBoolean fail = new AtomicBoolean();
        GlossProjectTransaction transaction = new GlossProjectTransaction(data, directory -> {
            if (fail.get() && directory.equals(data.resolve("editor-sync-backups"))) {
                throw new IOException("retention force failed");
            }
        });
        Path first = archive(transaction, 1);
        Path second = archive(transaction, 2);
        Path newest = archive(transaction, 3);
        transaction.configureRetention(new GlossProjectTransaction.RetentionPolicy(1, Long.MAX_VALUE));
        fail.set(true);
        assertThrows(IOException.class, transaction::pruneRetainedBackups);
        assertFalse(Files.exists(first));
        assertTrue(Files.exists(second));
        assertTrue(Files.exists(newest));
        assertFalse(transaction.retentionStatus().inventoryComplete());
        fail.set(false);
        transaction.configureRetention(new GlossProjectTransaction.RetentionPolicy(1, Long.MAX_VALUE));
        transaction.pruneRetainedBackups();
        assertFalse(Files.exists(second));
        assertTrue(Files.exists(newest));
        assertTrue(transaction.retentionStatus().inventoryComplete());
    }

    @Test
    void policyIncreaseDuringPruningStopsFurtherDeletionAndKeepsMaintenancePending() throws Exception {
        AtomicBoolean change = new AtomicBoolean();
        AtomicReference<GlossProjectTransaction> current = new AtomicReference<>();
        GlossProjectTransaction transaction = new GlossProjectTransaction(data, directory -> {
            if (directory.equals(data.resolve("editor-sync-backups")) && change.compareAndSet(true, false)) {
                current.get().configureRetention(new GlossProjectTransaction.RetentionPolicy(3, Long.MAX_VALUE));
            }
        });
        current.set(transaction);
        Path first = archive(transaction, 1);
        Path second = archive(transaction, 2);
        Path newest = archive(transaction, 3);
        transaction.configureRetention(new GlossProjectTransaction.RetentionPolicy(1, Long.MAX_VALUE));
        change.set(true);
        transaction.pruneRetainedBackups();
        assertFalse(Files.exists(first));
        assertTrue(Files.exists(second));
        assertTrue(Files.exists(newest));
        assertTrue(transaction.retentionPending());
        transaction.pruneRetainedBackups();
        assertFalse(transaction.retentionPending());
        assertEquals(2, transaction.retentionStatus().retainedBackups());
    }

    @Test
    void inventoryDepthRefusalPreservesAllArchivesBeforeAnyDeletion() throws Exception {
        GlossProjectTransaction transaction = new GlossProjectTransaction(data);
        Path first = archive(transaction, 1);
        Path newest = archive(transaction, 2);
        Path nested = data.resolve("editor-sync-backups/operator-files");
        for (int depth = 0; depth < 130; depth++) {
            nested = nested.resolve("d");
        }
        Files.createDirectories(nested);
        transaction.configureRetention(new GlossProjectTransaction.RetentionPolicy(1, 1));
        assertThrows(IOException.class, transaction::pruneRetainedBackups);
        assertTrue(Files.exists(first));
        assertTrue(Files.exists(newest));
        assertFalse(transaction.retentionStatus().inventoryComplete());
    }

    @Test
    void retentionConfigNormalizesAndRoundTripsIndependentlyOfHistoryFeature() throws Exception {
        GlossConfigFile source = new GlossConfigFile();
        source.features.history = false;
        source.history.maxTransactionBackups = 0;
        source.history.maxTransactionBackupBytes = Long.MAX_VALUE;
        source.normalize();
        GlossConfig.History expected = GlossConfig.from(source).modules().history();
        assertFalse(expected.enabled());
        assertEquals(1, expected.maxTransactionBackups());
        assertEquals(68719476736L, expected.maxTransactionBackupBytes());
        GlossConfigFile decoded = TomlCodec.fromToml(TomlCodec.toToml(source, "gloss", ConfigExposePolicy.ALL), GlossConfigFile.class);
        decoded.normalize();
        assertEquals(expected, GlossConfig.from(decoded).modules().history());
    }

    private Path archive(GlossProjectTransaction transaction, int sequence) throws Exception {
        Path target = data.resolve("menus/main.json");
        Files.createDirectories(target.getParent());
        if (!Files.exists(target)) {
            Files.writeString(target, "0".repeat(2000));
        }
        GlossProjectTransaction.Pending pending = transaction.apply("retention" + sequence,
            Map.of(target, GlossProjectTransaction.Mutation.write(Integer.toString(sequence).repeat(2000).getBytes(StandardCharsets.UTF_8))),
            Map.of(target, Files.readAllBytes(target)));
        transaction.commit(pending);
        Path archived = data.resolve("editor-sync-backups").resolve(pending.id());
        Files.setLastModifiedTime(archived.resolve("journal.json"), FileTime.fromMillis(sequence * 1000L));
        return archived;
    }

    private long bytes(Path directory) throws Exception {
        try (Stream<Path> files = Files.walk(directory)) {
            long bytes = 0;
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                bytes += Files.size(file);
            }
            return bytes;
        }
    }
}
