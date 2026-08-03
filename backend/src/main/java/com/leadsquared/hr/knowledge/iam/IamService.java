package com.leadsquared.hr.knowledge.iam;

import com.leadsquared.hr.knowledge.model.IamRole;
import com.leadsquared.hr.knowledge.model.IamUser;
import com.leadsquared.hr.knowledge.model.Permissions;
import com.leadsquared.hr.knowledge.store.IamRoleRepository;
import com.leadsquared.hr.knowledge.store.IamUserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Roles, who holds them, and what that permits.
 *
 * <p>This is the authority on access, but deliberately not the <i>only</i> one.
 * Two rules sit outside it and both exist to stop the same failure — an
 * organisation locked out of the page that grants access:
 *
 * <ul>
 *   <li>An Entra app role ({@code HR.Admin}) still grants admin. That is the answer
 *       HR's IT would give, and it survives this database entirely.
 *   <li>{@code hr.auth.admin-emails} still grants admin. This is the break-glass:
 *       on a fresh deployment the assignment collection is empty, so if it were the
 *       sole authority nobody could open the console to make the first grant.
 * </ul>
 *
 * <p>Every lookup on the authorization path is fail-soft. Mongo being unreachable
 * already degrades this application rather than breaking it — the knowledge base
 * keeps answering — and an outage must not become a lockout, so a failed read falls
 * back to the two rules above instead of denying.
 */
@Service
public class IamService {

  private static final Logger log = LoggerFactory.getLogger(IamService.class);

  /** Lower-case, digits, hyphens. This ends up in URLs and in the database. */
  private static final Pattern ROLE_NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

  private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s.]+\\.[^@\\s]+$");

  private static final int MAX_LABEL_CHARS = 60;
  private static final int MAX_DESCRIPTION_CHARS = 240;

  /** The role that grants the console, and the one a fresh install starts with. */
  public static final String HR_ADMIN_ROLE = "hr-admin";

  public static final String EMPLOYEE_ROLE = "employee";

  private final IamRoleRepository roles;
  private final IamUserRepository users;

  public IamService(IamRoleRepository roles, IamUserRepository users) {
    this.roles = roles;
    this.users = users;
  }

  // -------------------------------------------------------------------------
  // Seeding
  // -------------------------------------------------------------------------

  /**
   * Creates the two roles the application refers to by name.
   *
   * <p>After startup rather than during it, and swallowing failure, because Mongo is
   * optional at boot here — {@code application.yml} imports its credentials
   * optionally and the service is expected to run without them.
   */
  @EventListener(ApplicationReadyEvent.class)
  void seedBuiltInRoles() {
    try {
      ensureBuiltIn(
          HR_ADMIN_ROLE,
          "HR admin",
          "Full access to the HR admin console.",
          Set.of(Permissions.ADMIN_CONSOLE));
      ensureBuiltIn(
          EMPLOYEE_ROLE,
          "Employee",
          "Can use the assistant. No access to the admin console.",
          Set.of());
    } catch (RuntimeException e) {
      log.warn("Could not seed the built-in IAM roles — is MongoDB reachable? {}", e.toString());
    }
  }

  private void ensureBuiltIn(String name, String label, String description, Set<String> permissions) {
    if (roles.existsByName(name)) return;
    roles.save(
        new IamRole(
            null, name, label, description, permissions, true, Instant.now().toString(), "system"));
    log.info("Created built-in IAM role {}", name);
  }

  // -------------------------------------------------------------------------
  // Reading access
  // -------------------------------------------------------------------------

  /**
   * The permissions granted to whichever of these addresses has a record.
   *
   * @param identities every form of one account's address, from {@code
   *     CurrentUser.identitiesOf}
   */
  public Set<String> permissionsFor(Collection<String> identities) {
    if (identities == null || identities.isEmpty()) return Set.of();

    try {
      List<String> lowered = identities.stream().filter(s -> s != null).map(this::normaliseEmail).toList();
      List<IamUser> matches = users.findByEmailIn(lowered);
      if (matches.isEmpty()) return Set.of();

      Set<String> assigned = new LinkedHashSet<>();
      for (IamUser user : matches) {
        if (user.roles() != null) assigned.addAll(user.roles());
      }
      if (assigned.isEmpty()) return Set.of();

      Set<String> granted = new LinkedHashSet<>();
      for (IamRole role : roles.findAll()) {
        // A stale assignment naming a deleted role contributes nothing rather than
        // failing the whole lookup.
        if (assigned.contains(role.name()) && role.permissions() != null) {
          granted.addAll(role.permissions());
        }
      }
      return granted;

    } catch (RuntimeException e) {
      // Fail soft: see the class comment. An unreachable store must not lock the
      // organisation out, and the Entra app role plus the configured break-glass
      // list are still checked by the caller.
      log.warn("IAM lookup failed, falling back to configuration: {}", e.toString());
      return Set.of();
    }
  }

  public boolean grantsAdminConsole(Collection<String> identities) {
    return permissionsFor(identities).contains(Permissions.ADMIN_CONSOLE);
  }

  // -------------------------------------------------------------------------
  // Roles
  // -------------------------------------------------------------------------

  public List<IamRole> listRoles() {
    List<IamRole> all = new ArrayList<>(roles.findAll());
    // Built-ins first, then alphabetical: the console reads as "what ships" above
    // "what we added".
    all.sort(
        (a, b) -> {
          if (a.builtIn() != b.builtIn()) return a.builtIn() ? -1 : 1;
          return a.name().compareTo(b.name());
        });
    return all;
  }

  public IamRole createRole(String rawName, String label, String description, Set<String> permissions, String actor) {
    String name = normaliseRoleName(rawName);

    if (!ROLE_NAME.matcher(name).matches()) {
      throw new IllegalArgumentException(
          "A role name uses lower-case letters, numbers and hyphens — for example \"payroll-viewer\".");
    }
    if (roles.existsByName(name)) {
      throw new IllegalArgumentException("A role named \"" + name + "\" already exists.");
    }

    return roles.save(
        new IamRole(
            null,
            name,
            cleanLabel(label, name),
            cleanDescription(description),
            validatedPermissions(permissions),
            false,
            Instant.now().toString(),
            actor));
  }

  /**
   * Edits a role. A built-in keeps its permissions.
   *
   * <p>Renaming is not offered at all: assignments reference the name, so a rename
   * is a migration rather than an edit, and a half-applied one silently revokes
   * everyone holding the old name.
   */
  public IamRole updateRole(String rawName, String label, String description, Set<String> permissions, String actor) {
    IamRole existing = requireRole(rawName);

    IamRole updated = existing.withDetails(cleanLabel(label, existing.name()), cleanDescription(description));

    if (permissions != null && !existing.builtIn()) {
      updated = updated.withPermissions(validatedPermissions(permissions));
    } else if (permissions != null && existing.builtIn()) {
      throw new IllegalArgumentException(
          "\"" + existing.label() + "\" is a built-in role, so its permissions are fixed. "
              + "Create a new role if you need a different set.");
    }

    return roles.save(updated);
  }

  /**
   * Deletes a role and withdraws it from everyone holding it.
   *
   * <p>The assignments are cleaned up rather than left dangling: a name that no
   * longer resolves is inert today, but it would silently come back to life the
   * moment someone created a new role that happened to reuse the name.
   */
  public void deleteRole(String rawName) {
    IamRole existing = requireRole(rawName);

    if (existing.builtIn()) {
      throw new IllegalArgumentException(
          "\"" + existing.label() + "\" is a built-in role and cannot be deleted.");
    }

    int withdrawn = 0;
    for (IamUser user : users.findAll()) {
      if (user.roles() == null || !user.roles().contains(existing.name())) continue;
      List<String> remaining =
          user.roles().stream().filter(r -> !r.equals(existing.name())).toList();
      users.save(user.withRoles(remaining));
      withdrawn++;
    }

    roles.deleteByName(existing.name());
    log.info("Deleted IAM role {} and withdrew it from {} user(s)", existing.name(), withdrawn);
  }

  // -------------------------------------------------------------------------
  // Users
  // -------------------------------------------------------------------------

  public List<IamUser> listUsers() {
    return users.findAllByOrderByCreatedAtDesc();
  }

  /** Adds a record, or updates the one already there. Re-adding is not an error. */
  public IamUser upsertUser(String rawEmail, String name, List<String> roleNames, String actor) {
    String email = normaliseEmail(rawEmail);

    if (!EMAIL.matcher(email).matches()) {
      throw new IllegalArgumentException("Enter a valid work email address.");
    }

    List<String> assigned = validatedRoles(roleNames);
    String displayName = name == null || name.isBlank() ? email.split("@")[0] : name.trim();

    Optional<IamUser> existing = users.findByEmail(email);
    if (existing.isPresent()) {
      return users.save(existing.get().withName(displayName).withRoles(assigned));
    }

    return users.save(
        new IamUser(null, email, displayName, assigned, Instant.now().toString(), actor));
  }

  public IamUser setUserRoles(String rawEmail, List<String> roleNames) {
    String email = normaliseEmail(rawEmail);
    IamUser existing =
        users
            .findByEmail(email)
            .orElseThrow(() -> new IllegalArgumentException("No access record for " + email + "."));

    return users.save(existing.withRoles(validatedRoles(roleNames)));
  }

  public void deleteUser(String rawEmail) {
    String email = normaliseEmail(rawEmail);
    IamUser existing =
        users
            .findByEmail(email)
            .orElseThrow(() -> new IllegalArgumentException("No access record for " + email + "."));

    users.delete(existing);
    log.info("Removed IAM record for {}", email);
  }

  // -------------------------------------------------------------------------
  // Validation
  // -------------------------------------------------------------------------

  private IamRole requireRole(String rawName) {
    String name = normaliseRoleName(rawName);
    return roles
        .findByName(name)
        .orElseThrow(() -> new IllegalArgumentException("No role named \"" + name + "\"."));
  }

  private List<String> validatedRoles(List<String> roleNames) {
    if (roleNames == null) return List.of();

    List<String> out = new ArrayList<>();
    for (String raw : roleNames) {
      if (raw == null || raw.isBlank()) continue;
      String name = normaliseRoleName(raw);
      if (!roles.existsByName(name)) {
        throw new IllegalArgumentException("No role named \"" + name + "\".");
      }
      if (!out.contains(name)) out.add(name);
    }
    return out;
  }

  /**
   * Rejects permissions this build does not define.
   *
   * <p>An unknown string would save happily and grant nothing, so the console would
   * show access that the endpoints go on refusing.
   */
  private static Set<String> validatedPermissions(Set<String> permissions) {
    if (permissions == null) return Set.of();

    Set<String> out = new LinkedHashSet<>();
    for (String permission : permissions) {
      if (permission == null || permission.isBlank()) continue;
      String value = permission.trim();
      if (!Permissions.isKnown(value)) {
        throw new IllegalArgumentException("Unknown permission \"" + value + "\".");
      }
      out.add(value);
    }
    return out;
  }

  private String normaliseRoleName(String raw) {
    return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
  }

  private String normaliseEmail(String raw) {
    return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
  }

  private static String cleanLabel(String label, String fallback) {
    if (label == null || label.isBlank()) return fallback;
    String trimmed = label.trim();
    return trimmed.length() <= MAX_LABEL_CHARS ? trimmed : trimmed.substring(0, MAX_LABEL_CHARS);
  }

  private static String cleanDescription(String description) {
    if (description == null || description.isBlank()) return "";
    String trimmed = description.trim();
    return trimmed.length() <= MAX_DESCRIPTION_CHARS
        ? trimmed
        : trimmed.substring(0, MAX_DESCRIPTION_CHARS);
  }
}
