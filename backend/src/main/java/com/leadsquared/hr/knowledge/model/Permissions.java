package com.leadsquared.hr.knowledge.model;

import java.util.List;

/**
 * What a role can grant.
 *
 * <p>A closed set, validated on write. An unrecognised permission string saved
 * against a role would be silently inert — it would look like access had been
 * granted in the console while the endpoint kept refusing, which is the worst way
 * for an authorization system to be wrong.
 */
public final class Permissions {

  /**
   * Reach the HR admin console at all.
   *
   * <p>This was once the whole of it — one permission covering the escalation queue, the ratings
   * and the knowledge base together, on the argument that the console does not degrade gracefully
   * for a partial grant because the tickets tab holds a colleague's harassment report either way.
   * That argument was right about the risk and wrong about the remedy: it made "maintains the
   * policy library" and "reads every POSH report" the same grant, so the only way to let somebody
   * upload a document was to show them the queue.
   *
   * <p>What it means now is narrower and still load-bearing: the front door. It admits nobody to
   * any tab on its own — each area below is its own grant — but every one of those is checked
   * <em>in addition</em> to this, so removing it removes the console wholesale, and a role holding
   * only area permissions reaches nothing.
   */
  public static final String ADMIN_CONSOLE = "admin.console";

  /**
   * The escalation queue, minus anything routed as sensitive.
   *
   * <p>Ordinary escalations: a leave question the corpus could not answer, a reimbursement nobody
   * could find the rule for. The queue is most of the console's day-to-day work and the thing HR
   * most often wants to delegate.
   */
  public static final String ADMIN_TICKETS = "admin.tickets";

  /**
   * Escalations routed to the HR Head — harassment, grievances, misconduct.
   *
   * <p>Held apart from {@link #ADMIN_TICKETS} because this is the objection that kept the console
   * a single grant, and answering it is what makes splitting safe rather than cosmetic. Without
   * it, "you can work the escalation queue" would silently also mean "you can read every POSH
   * report", which is precisely the delegation nobody intends to make.
   *
   * <p>Additive, not a replacement: a reviewer needs both this and {@link #ADMIN_TICKETS}, since
   * this one grants a subset of the same queue rather than a separate place to look.
   */
  public static final String ADMIN_TICKETS_SENSITIVE = "admin.tickets.sensitive";

  /**
   * The weekly digest and the answer ratings behind it.
   *
   * <p>What employees asked and how the answers were rated. Aggregate rather than personal, which
   * is why it is separable from the queue: it says the assistant is failing on notice periods, not
   * that a named person asked about one.
   */
  public static final String ADMIN_DIGEST = "admin.digest";

  /**
   * Upload, edit and remove the policy documents the assistant answers from.
   *
   * <p>The grant that most wants to exist alone. Somebody maintaining the policy library changes
   * what every employee is told, which is real power and no reason at all to show them the
   * escalation queue.
   */
  public static final String ADMIN_KNOWLEDGE = "admin.knowledge";

  /**
   * Manage roles, permissions and who holds them.
   *
   * <p>The permission that can grant every other permission, including itself, so treat it as the
   * administrator grant it is. Kept separate mainly so that it can be withheld: everything else
   * here is recoverable by someone who holds this, and this is not.
   */
  public static final String ADMIN_ACCESS = "admin.access";

  /**
   * Maintain the variable pay plan — the company result, the bands, the weightings.
   *
   * <p>Its own permission because it is the one screen in the console whose values change what
   * every employee is told they will be paid. Entering a revenue figure here moves the company
   * component for every non-sales employee at once, which is a different kind of act from
   * uploading a policy document or working the escalation queue, and belongs to whoever owns
   * compensation rather than to everyone who can open the console.
   */
  public static final String ADMIN_PAYROLL = "admin.payroll";

  /**
   * See the audit trail and the sign-in list.
   *
   * <p>Separate from {@link #ADMIN_CONSOLE} because it is the one part of the console that is
   * <em>about</em> the other users of the console. Someone who uploads policies and works the
   * escalation queue does not need to see which colleagues signed in and when, and the record of
   * who changed access is worth less if everyone who can change it can also edit its history's
   * audience.
   */
  public static final String ADMIN_AUDIT = "admin.audit";

  /** @param label what the console's checkbox says */
  public record Definition(String key, String label, String description) {}

  /** Drives the permission checkboxes in the console, so the two cannot drift. */
  public static final List<Definition> CATALOGUE =
      List.of(
          new Definition(
              ADMIN_CONSOLE,
              "Open the HR admin console",
              "Required for every other permission below — without it the console is not "
                  + "reachable at all. On its own it opens the console and nothing inside it."),
          new Definition(
              ADMIN_TICKETS,
              "Work the escalation queue",
              "Read and respond to escalations raised by employees. Excludes anything routed "
                  + "as sensitive — that needs the permission below as well."),
          new Definition(
              ADMIN_TICKETS_SENSITIVE,
              "See sensitive escalations",
              "Harassment, grievance and misconduct reports routed to the HR Head. Grant only "
                  + "to the people who handle them; it is additive to the queue permission "
                  + "above, not a replacement for it."),
          new Definition(
              ADMIN_DIGEST,
              "See the weekly digest and answer ratings",
              "What employees asked, where the assistant fell short, and how answers were "
                  + "rated. Aggregate figures rather than any named person's questions."),
          new Definition(
              ADMIN_KNOWLEDGE,
              "Manage the knowledge base",
              "Upload, replace and remove the policy documents every answer is drawn from."),
          new Definition(
              ADMIN_ACCESS,
              "Manage roles and access",
              "Create roles, change what they grant, and assign them. This permission can "
                  + "grant every other permission, including itself."),
          new Definition(
              ADMIN_PAYROLL,
              "Maintain the variable pay plan",
              "Enter the company's achieved revenue and GRR, and adjust the bands, grade "
                  + "weightings and rating payouts the variable pay calculation uses."),
          new Definition(
              ADMIN_AUDIT,
              "See the audit trail and sign-ins",
              "Review every policy upload and access change, and see which employees have "
                  + "signed in and when."));

  /**
   * Everything an area permission can be, excluding the front door itself.
   *
   * <p>Used to give the break-glass administrators and the migration of pre-split roles the full
   * set without listing it twice and having the two drift.
   */
  public static final List<String> AREAS =
      List.of(
          ADMIN_TICKETS,
          ADMIN_TICKETS_SENSITIVE,
          ADMIN_DIGEST,
          ADMIN_KNOWLEDGE,
          ADMIN_ACCESS,
          ADMIN_PAYROLL,
          ADMIN_AUDIT);

  private static final List<String> KEYS = CATALOGUE.stream().map(Definition::key).toList();

  private Permissions() {}

  public static boolean isKnown(String permission) {
    return KEYS.contains(permission);
  }

  public static List<String> keys() {
    return KEYS;
  }
}
