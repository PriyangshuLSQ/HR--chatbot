package com.leadsquared.hr.knowledge.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One administrative action, for the trail HR Ops Admin reviews.
 *
 * <p>Distinct from {@link DataAccessEvent}, which records employees reading their own records.
 * That trail answers "whose data was looked at"; this one answers "who changed the system" — who
 * uploaded the policy the assistant is now quoting, and who granted the access that let them.
 * Keeping them apart matters because they have different subjects, different retention interests
 * and different readers: the data trail is about employees, this is about administrators.
 *
 * <p>Append-only by convention, like the other trail: the repository offers no way to amend or
 * remove a row, so the history of who granted what cannot be edited by someone who holds the
 * permission to grant it.
 *
 * @param actor the signed-in email that performed the action. Never null in practice; recorded as
 *     {@code unknown} rather than dropped if a caller reaches a write with no session, because a
 *     change with no attributable author is the most interesting row in the table.
 * @param action one of the {@code *} constants below — a stable machine key, so the console can
 *     group and filter without parsing prose.
 * @param target what was acted on: a document title, an email address, a role name.
 * @param detail a short human-readable summary, already rendered. The console displays this
 *     verbatim, which keeps the wording in one place rather than duplicated per client.
 */
@Document("admin_audit_events")
public record AdminAuditEvent(
    @Id String id,
    @Indexed Instant at,
    @Indexed String actor,
    @Indexed String action,
    String target,
    String detail) {

  /** A policy document was uploaded or typed into the console. */
  public static final String POLICY_UPLOADED = "policy.uploaded";

  /** A policy document was removed from the knowledge base. */
  public static final String POLICY_DELETED = "policy.deleted";

  /** The corpus was re-embedded. Cheap to record and it explains a sudden latency change. */
  public static final String KNOWLEDGE_REINDEXED = "knowledge.reindexed";

  /** An address was given an access record for the first time. */
  public static final String ACCESS_GRANTED = "access.granted";

  /** An existing record's roles changed. */
  public static final String ACCESS_CHANGED = "access.changed";

  /** An access record was removed entirely. */
  public static final String ACCESS_REVOKED = "access.revoked";

  /** A role's label, description or permissions were edited. */
  public static final String ROLE_UPDATED = "role.updated";

  /** Longest detail retained, so the trail cannot become a copy of what was uploaded. */
  public static final int MAX_DETAIL_CHARS = 400;

  public static AdminAuditEvent of(String actor, String action, String target, String detail) {
    return new AdminAuditEvent(
        null,
        Instant.now(),
        actor == null || actor.isBlank() ? "unknown" : actor,
        action,
        target,
        truncate(detail));
  }

  public static String truncate(String raw) {
    if (raw == null) return null;
    String trimmed = raw.strip();
    return trimmed.length() <= MAX_DETAIL_CHARS
        ? trimmed
        : trimmed.substring(0, MAX_DETAIL_CHARS) + "…";
  }
}
