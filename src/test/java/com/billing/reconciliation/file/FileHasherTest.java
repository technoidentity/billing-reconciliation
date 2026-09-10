package com.billing.reconciliation.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileHasherTest {

    @TempDir
    Path tempDir;

    @Test
    void hashesFileContents() throws Exception {
        Path file = tempDir.resolve("billing.csv");
        Files.writeString(file, "id,txn_id\n1,TXN1\n");
        String digest = FileHasher.sha256(file);
        assertEquals(64, digest.length());
        assertEquals(digest, FileHasher.sha256(file));
    }

    @Test
    void parsesGnuSidecar() {
        assertEquals("abc123", FileHasher.parseSidecar("ABC123  billing.csv\n"));
        assertEquals("deadbeef", FileHasher.parseSidecar("*DEADBEEF"));
    }

    @Test
    void matchesExpectedDigest() {
        assertTrue(FileHasher.matches("AbC", "abc"));
        assertFalse(FileHasher.matches("abc", "abd"));
        assertFalse(FileHasher.matches("", "abc"));
    }
}
