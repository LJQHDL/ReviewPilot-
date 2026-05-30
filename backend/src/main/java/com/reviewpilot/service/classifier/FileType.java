package com.reviewpilot.service.classifier;

/**
 * Coarse functional category of a changed file, used downstream by
 * {@code RiskDetector} (PR#5) to pick which rules to apply and by
 * {@code PromptBuilder} (PR#6) to pick which prompt template to render.
 * <p>
 * The set is intentionally small — six buckets is enough to drive meaningful
 * differentiation in prompts without forcing the classifier to be precise
 * about edge cases. Anything we can't confidently place falls into
 * {@link #OTHER} and gets the generic prompt.
 *
 * <ul>
 *   <li>{@link #CONTROLLER} — HTTP-facing entry points (Spring MVC controllers,
 *       REST endpoints). Reviewers should think about input validation, status
 *       codes, auth.</li>
 *   <li>{@link #SERVICE} — business logic / Spring service beans. Reviewers
 *       should think about transactions, concurrency, error handling.</li>
 *   <li>{@link #CONFIG} — application configuration (yml/properties, Spring
 *       {@code @Configuration} classes). Reviewers should think about secret
 *       leakage, environment-specific defaults.</li>
 *   <li>{@link #SQL} — schema or query files. Reviewers should think about
 *       index impact, migration safety, injection.</li>
 *   <li>{@link #TEST} — test sources. Reviewers should think about coverage and
 *       brittleness, not production correctness.</li>
 *   <li>{@link #OTHER} — anything else (frontend, docs, build files,
 *       unclassified). Receives a generic review prompt.</li>
 * </ul>
 */
public enum FileType {
    CONTROLLER,
    SERVICE,
    CONFIG,
    SQL,
    TEST,
    OTHER
}
