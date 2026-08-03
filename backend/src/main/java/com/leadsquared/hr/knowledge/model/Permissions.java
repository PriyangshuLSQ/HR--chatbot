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
   * Reach the HR admin console and the APIs behind it — the escalation queue, the
   * answer ratings, the knowledge base, and this page itself.
   *
   * <p>Deliberately one permission covering all of it. Splitting it would imply the
   * console degrades gracefully for a partial grant, and it does not: the tickets
   * tab holds a colleague's harassment report either way.
   */
  public static final String ADMIN_CONSOLE = "admin.console";

  /** @param label what the console's checkbox says */
  public record Definition(String key, String label, String description) {}

  /** Drives the permission checkboxes in the console, so the two cannot drift. */
  public static final List<Definition> CATALOGUE =
      List.of(
          new Definition(
              ADMIN_CONSOLE,
              "Access the HR admin console",
              "Open the admin site and everything behind it: the escalation queue, "
                  + "answer ratings, the knowledge base, and access management itself."));

  private static final List<String> KEYS = CATALOGUE.stream().map(Definition::key).toList();

  private Permissions() {}

  public static boolean isKnown(String permission) {
    return KEYS.contains(permission);
  }

  public static List<String> keys() {
    return KEYS;
  }
}
