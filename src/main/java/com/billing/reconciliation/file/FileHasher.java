package com.billing.reconciliation.file;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public final class FileHasher {

    private FileHasher() {
    }

    public static String sha256(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return sha256(in);
        }
    }

    public static String sha256(InputStream in) throws IOException {
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Accepts a raw hex digest or GNU {@code sha256sum} output ({@code <hex>  filename}).
     */
    public static String parseSidecar(String content) {
        if (content == null) {
            return "";
        }
        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String first = trimmed.split("\\s+")[0].replace("*", "");
        return first.toLowerCase(Locale.ROOT);
    }

    public static boolean matches(String expected, String actual) {
        if (expected == null || actual == null || expected.isBlank() || actual.isBlank()) {
            return false;
        }
        return parseSidecar(expected).equals(actual.toLowerCase(Locale.ROOT));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
