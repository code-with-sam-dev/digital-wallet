package com.codewithsam.wallet;

/**
 * The sender does not have the money.
 *
 * A refusal, not an error. It is counted separately in the metrics for exactly
 * that reason: a system refusing transfers correctly is healthy, and mixing
 * refusals into an error rate hides real failures behind normal behaviour.
 */
public class InsufficientFunds extends RuntimeException {
    public InsufficientFunds(long walletId, long available, long requested) {
        super("wallet %d has %d and cannot send %d".formatted(walletId, available, requested));
    }
}
