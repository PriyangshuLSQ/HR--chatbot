package com.leadsquared.hr.knowledge.model;

import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * An account this application knows something about, and what it may do.
 *
 * <p><b>Not a user store.</b> Microsoft Entra decides who exists and who may sign
 * in; there is no password here and adding a row grants nobody a way in. A record
 * exists only to attach roles to an address, so "add a user" means "start tracking
 * this person's access", not "create an account".
 *
 * <p>The corollary matters: someone who signs in through Entra with no record here
 * is a normal employee. Absence denies rather than blocks — there is nothing to
 * lock out of, and requiring an entry per employee would mean the chat stopped
 * working for anyone HR had not enrolled by hand.
 *
 * @param email lower-cased, and the only thing an assignment keys on. It is
 *     matched against every form of the signed-in account's address — see {@code
 *     CurrentUser.identitiesOf} — so a guest whose UPN Entra has mangled still
 *     resolves.
 * @param roles {@link IamRole#name()} values. A name with no surviving role is
 *     ignored on read rather than failing the lookup.
 */
@Document(collection = "iam_users")
public record IamUser(
    @Id String id,
    @Indexed(unique = true) String email,
    String name,
    List<String> roles,
    String createdAt,
    String createdBy) {

  public IamUser withRoles(List<String> updated) {
    return new IamUser(id, email, name, updated, createdAt, createdBy);
  }

  public IamUser withName(String updated) {
    return new IamUser(id, email, updated, roles, createdAt, createdBy);
  }
}
