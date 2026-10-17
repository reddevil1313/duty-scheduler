package com.dutyscheduler.duty.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Invite tokens: 32 random bytes, sent as base64url, stored as SHA-256 (V7
 * says why that and not bcrypt).
 */
final class InviteTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private InviteTokens() {
    }

    /** 256 bits, too many to guess. */
    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // Every JVM is required to provide SHA-256.
            throw new IllegalStateException(e);
        }
    }
}
