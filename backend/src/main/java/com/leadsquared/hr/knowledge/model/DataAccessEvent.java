package com.leadsquared.hr.knowledge.model;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One employee-data access event, for the audit trail HR Ops reviews.
 *
 * <p>Append-only by convention: nothing in the application updates or deletes these, and
 * the repository exposes no method that would.
 *
 * <p><b>This collection is itself sensitive.</b> It records which employees asked about
 * their own compensation, leave and performance ratings — a more concentrated disclosure
 * than the employee collection, because it carries intent as well as fact. It is gated
 * behind the same admin check as the escalation queue, and {@link #question} is truncated
 * rather than stored whole so the log does not quietly become a second copy of every
 * conversation.
 *
 * @param subject the signed-in email the access was made under, from the session token
 * @param employeeCode whose record was read. Under the access rules this is always the
 *     subject's own, which is exactly why it is recorded: a row where this does not
 *     correspond to the subject is the signature of the breach this design prevents, and
 *     it is worth being able to prove the absence of one.
 * @param outcome {@link #ALLOWED}, {@link #DENIED_THIRD_PARTY} or
 *     {@link #DENIED_UNAUTHENTICATED}
 * @param fields which parts of the record reached the answer — {@code compensation},
 *     {@code leave}, {@code attendance}, {@code employment}, {@code pms}. Field names, not
 *     values: the log says what was looked at, never what it said.
 */
@Document("data_access_events")
public record DataAccessEvent(
    @Id String id,
    @Indexed Instant at,
    @Indexed String subject,
    String employeeCode,
    @Indexed String outcome,
    String intent,
    List<String> fields,
    String question,
    String threadId) {

  public static final String ALLOWED = "allowed";

  /** An attempt to read another employee's data. Rule 2 requires these be logged. */
  public static final String DENIED_THIRD_PARTY = "denied_third_party";

  /** A data question with no verified session behind it. Rule 3. */
  public static final String DENIED_UNAUTHENTICATED = "denied_unauthenticated";

  /**
   * An HR admin read someone else's record — the deliberate exception to rule 2.
   *
   * <p>Its own outcome rather than {@link #ALLOWED} so these are countable and reviewable
   * separately. "How many times did an admin read another employee's data, and whose?" is the
   * question HR Ops will ask about this exception, and it should take one filter to answer.
   */
  public static final String ALLOWED_ADMIN_CROSS_EMPLOYEE = "allowed_admin_cross_employee";

  /** Longest question text retained. Enough to review an attempt, not a transcript. */
  public static final int MAX_QUESTION_CHARS = 300;

  public static String truncate(String raw) {
    if (raw == null) return null;
    String trimmed = raw.strip();
    return trimmed.length() <= MAX_QUESTION_CHARS
        ? trimmed
        : trimmed.substring(0, MAX_QUESTION_CHARS) + "…";
  }
}
