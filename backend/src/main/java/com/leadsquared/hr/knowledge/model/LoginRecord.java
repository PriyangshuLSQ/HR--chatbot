package com.leadsquared.hr.knowledge.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * When an account last signed in, and how often it has.
 *
 * <p>Its own collection rather than fields on {@link Employee}, because that record is replaced
 * wholesale by the HR extract import. A {@code lastLoginAt} living there would be silently reset
 * to null by the next import, which is a bug that only shows up long after the change that caused
 * it.
 *
 * <p>Keyed on email rather than employee code so it works for people the extract does not cover —
 * new joiners, contractors, and the admin accounts that never appear in an HR extract at all.
 * {@link #employeeCode} is filled in when a match exists, so the console can show a name and grade
 * beside the timestamp without this collection having to duplicate them.
 *
 * @param loginCount total sign-ins observed. Incremented rather than derived from a per-event
 *     collection: the question the console asks is "who is using this and when did they last",
 *     which needs one row per person, not one row per visit.
 */
@Document("login_records")
public record LoginRecord(
    @Id String id,
    @Indexed(unique = true) String email,
    String name,
    String employeeCode,
    Instant firstLoginAt,
    @Indexed Instant lastLoginAt,
    long loginCount,
    /** {@code entra}, {@code local} or {@code dev} — which door they came through. */
    String method) {

  public static final String ENTRA = "entra";
  public static final String LOCAL = "local";

  /**
   * TEMPORARY: the configured-address bypass used while the Entra registration is pending. Its own
   * value rather than reusing {@code local} so the sign-in list stays honest about which sessions
   * Microsoft actually vouched for.
   */
  public static final String DEV = "dev";

  public static LoginRecord first(String email, String name, String employeeCode, String method) {
    Instant now = Instant.now();
    return new LoginRecord(null, email, name, employeeCode, now, now, 1, method);
  }

  /** The same person, seen again. Keeps the first-seen timestamp and the known employee code. */
  public LoginRecord seenAgain(String latestName, String resolvedCode, String usedMethod) {
    return new LoginRecord(
        id,
        email,
        latestName == null || latestName.isBlank() ? name : latestName,
        resolvedCode == null ? employeeCode : resolvedCode,
        firstLoginAt,
        Instant.now(),
        loginCount + 1,
        usedMethod == null ? method : usedMethod);
  }
}
