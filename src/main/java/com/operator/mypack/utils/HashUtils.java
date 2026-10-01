package com.operator.mypack.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Hashing helpers (SHA-1 is what the Minecraft client expects for resource packs). */
public final class HashUtils {

    private HashUtils() {
    }

    public static byte[] sha1(byte[] data) {
        return digest("SHA-1", data);
    }

    public static String sha1Hex(byte[] data) {
        return HexFormat.of().formatHex(sha1(data));
    }

    public static String sha256Hex(byte[] data) {
        return HexFormat.of().formatHex(digest("SHA-256", data));
    }

    public static String sha256Hex(Path file) throws IOException {
        MessageDigest md = newDigest("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int read;
            while ((read = in.read(buf)) != -1) {
                md.update(buf, 0, read);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    public static byte[] fromHex(String hex) {
        return HexFormat.of().parseHex(hex);
    }

    public static String toHex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static byte[] digest(String algorithm, byte[] data) {
        return newDigest(algorithm).digest(data);
    }

    private static MessageDigest newDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            // Every JRE is required to ship SHA-1 and SHA-256.
            throw new IllegalStateException(algorithm + " is not available", e);
        }
    }
}
