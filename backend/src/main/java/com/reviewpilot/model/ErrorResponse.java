package com.reviewpilot.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Uniform error envelope returned by every {@code @ExceptionHandler} in
 * {@link com.reviewpilot.controller.ReviewController}. The frontend axios
 * interceptor (PR#7) reads exactly the {@code error} field, so locking the
 * field name as a typed record prevents an accidental rename from breaking
 * the SPA's error display.
 *
 * <p>Why a record and not a Map: with Map.of("error", "...") it's too easy to
 * silently change the key (e.g. "message", "reason") in a future PR and
 * discover the regression only when a user sees a blank toast. A record makes
 * the contract a compile-time invariant, surfaces in OpenAPI / Swagger output,
 * and is easier to extend later (e.g. add a request-id field) without
 * scattering updates.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String error) {
    public static ErrorResponse of(String message) {
        return new ErrorResponse(message);
    }
}
