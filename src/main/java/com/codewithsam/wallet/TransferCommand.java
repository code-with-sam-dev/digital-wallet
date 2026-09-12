package com.codewithsam.wallet;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * One request to move money.
 *
 * The idempotency key belongs to the LOGICAL operation, not to the HTTP call.
 * A client that retries reuses the same key; a client starting a genuinely new
 * transfer generates a new one.
 */
public record TransferCommand(String idempotencyKey, long fromWallet, long toWallet, long amountMinor) {

    public TransferCommand {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("an idempotency key is required");
        }
        if (fromWallet == toWallet) {
            throw new IllegalArgumentException("a transfer needs two different wallets");
        }
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }

    /**
     * A fingerprint of the request, so a retry can be told apart from a
     * DIFFERENT request that happens to reuse a key.
     *
     * Without this, the second case silently returns the first transfer's
     * result and the client believes something happened that never did.
     */
    public String requestHash() {
        String material = fromWallet + ":" + toWallet + ":" + amountMinor;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
