package com.codewithsam.wallet;

import java.util.UUID;

/**
 * What the caller gets back.
 *
 * `replayed` is deliberately visible. A client that retried is entitled to know
 * that it retried, and hiding it makes the behaviour harder to reason about in
 * exactly the situations where reasoning matters.
 */
public record TransferResult(UUID transferId, String status, boolean replayed) {}
