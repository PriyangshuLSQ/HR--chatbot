package com.leadsquared.hr.knowledge.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A thumbs-up / thumbs-down on one bot answer, and what the employee typed after
 * a thumbs-down.
 *
 * <p>Deliberately not linked to the employee: {@code intentId} and {@code query}
 * are kept so the weekly digest can say <em>which topic</em> is failing, but no
 * identity is stored. Rating an answer honestly should not be attributable.
 *
 * <p>Field names are the wire contract with {@code lib/hr-store.ts}.
 *
 * @param messageId the chat message being rated; unique, because re-rating
 *     replaces the previous entry rather than stacking a second vote
 * @param rating {@code up} or {@code down}
 * @param intentId null when nothing matched — those group under "Unmatched
 *     queries" in the digest, which is exactly the bucket HR needs to see
 */
@Document(collection = "feedback")
public record Feedback(
    @Id String id,
    @Indexed(unique = true) String messageId,
    String rating,
    String comment,
    String intentId,
    String intentLabel,
    String query,
    double confidence,
    @Indexed String createdAt,
    String channel) {}
