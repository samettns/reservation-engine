package io.github.samettns.reservation.strategy;

/**
 * Published by an instance whose Redis calls work again after failures. Redis counters may be
 * stale (the DB changed while Redis was unreachable); the reconciler reacts with an immediate round.
 */
public record RedisGateRecovered() {
}
