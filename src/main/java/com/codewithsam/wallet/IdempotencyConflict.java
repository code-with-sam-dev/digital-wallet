package com.codewithsam.wallet;

/**
 * The same idempotency key arrived with materially different request data.
 *
 * This is a client error, not a duplicate. Returning the original result here
 * would tell the caller that a transfer it never asked for had been carried
 * out, which is worse than failing loudly.
 */
public class IdempotencyConflict extends RuntimeException {
    public IdempotencyConflict(String key) {
        super("idempotency key %s was already used for a different request".formatted(key));
    }
}
