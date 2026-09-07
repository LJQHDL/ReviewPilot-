package com.reviewpilot.model;

/**
 * Wall-clock budget for one review request.
 *
 * <p>Each layer used to bound only its own HTTP call — the ReAct loop capped
 * rounds, the provider capped per-call timeouts and retried internally — so one
 * request could hold a servlet thread long after the client gave up. This is the
 * single number checked before starting any further model round-trip.
 *
 * <p>It bounds how much work may <em>start</em>, not total elapsed time: a call
 * already in flight runs to its own timeout and nothing can preempt it. Only
 * moving review out of the request thread makes the bound exact.
 *
 * @param expiresAtMillis absolute deadline; {@code 0} means no budget was set
 */
public record Deadline(long expiresAtMillis) {

    public static final Deadline NONE = new Deadline(0);

    public static Deadline of(long budgetMillis) {
        return budgetMillis <= 0 ? NONE : new Deadline(System.currentTimeMillis() + budgetMillis);
    }

    public boolean expired() {
        return expiresAtMillis > 0 && System.currentTimeMillis() >= expiresAtMillis;
    }
}
