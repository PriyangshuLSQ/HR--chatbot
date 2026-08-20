package com.leadsquared.hr.knowledge.iam;

import com.leadsquared.hr.knowledge.model.IamRole;
import com.leadsquared.hr.knowledge.model.IamUser;
import com.leadsquared.hr.knowledge.model.Permissions;
import com.leadsquared.hr.knowledge.store.IamRoleRepository;
import com.leadsquared.hr.knowledge.store.IamUserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

  /**
   * The role that grants the console, and the one a fresh install starts with.
   *
   * <p>Kept as a constant after being retired so {@link #migrateLegacyRoles} can find and
   * replace the assignments it left behind. Nothing creates it any more.
   */
  public static final String LEGACY_HR_ADMIN_ROLE = "hr-admin";

  public static final String LEGACY_EMPLOYEE_ROLE = "employee";

  /**
   * Retired. Kept only so the startup cleanup can recognise and remove them.
   *
   * <p>These were the whole role set: HR Ops worked the console, HR Ops Admin added the audit
   * trail, and both were re-asserted from constants on every boot. Now that each area of the
   * console is its own permission, roles are defined in the console instead — an organisation's
   * split of duties is theirs to describe, and two shapes decided here fit nobody exactly.
   *
   * <p>Nothing creates them. {@link #retireBuiltInRoles()} removes them if they are present, and
   * anyone left without a role falls back to {@code hr.auth.admin-emails} — see that method.
   */
  public static final String HR_OPS_ROLE = "hr-ops";

  public static final String HR_OPS_ADMIN_ROLE = "hr-ops-admin";

  private final IamRoleRepository roles;
  private final IamUserRepository users;

  /** Addresses to grant HR Ops Admin on startup. Empty in any deployment that does not set it. */
  private final List<String> seedAdmins;

  public IamService(
      IamRoleRepository roles,
      IamUserRepository users,
      @Value("${hr.auth.seed-hr-ops-admins:}") String seedHrOpsAdmins) {
    this.roles = roles;
    this.users = users;
    this.seedAdmins =
        seedHrOpsAdmins == null || seedHrOpsAdmins.isBlank()
            ? List.of()
            : Arrays.stream(seedHrOpsAdmins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

  // -------------------------------------------------------------------------
  // Seeding
  // -------------------------------------------------------------------------

  /**
   * Brings the stored roles in line with a console that no longer ships any.
   *
   * <p>After startup rather than during it, and swallowing failure, because Mongo is
   * optional at boot here — {@code application.yml} imports its credentials
   * optionally and the service is expected to run without them.
   */
  @EventListener(ApplicationReadyEvent.class)
  void reconcileRoles() {
    try {
      migrateLegacyRoles();
      migrateToAreaPermissions();
      retireBuiltInRoles();
    } catch (RuntimeException e) {
      log.warn("Could not reconcile the IAM roles — is MongoDB reachable? {}", e.toString());
    }
  }

  /**
   * Removes the roles this application used to ship, and withdraws them from whoever holds them.
   *
   * <p>Every role is defined in the console now. Leaving HR Ops and HR Ops Admin in place would
   * have been the more cautious change, but not an honest one: they were re-asserted from
   * constants on every boot, so they would have sat in the list looking editable and silently
   * reverting, which is worse than not being there.
   *
   * <p><b>What stops this locking everyone out.</b> Withdrawing a role can leave an account with
   * none, and with nothing seeded there may briefly be no role granting {@link
   * Permissions#ADMIN_ACCESS} at all — nobody able to hand access back out. The answer is
   * {@code hr.auth.admin-emails}: those addresses hold every permission without any assignment,
   * decided in {@code PermissionChecker} before the store is consulted. That list is the way back
   * in, and it is why it must stay populated. An empty {@code admin-emails} beside an empty role
   * table is an organisation locked out of its own console with no path back through the UI.
   *
   * <p>Idempotent: after the first boot there is nothing marked built-in, and this does nothing.
   */
  private void retireBuiltInRoles() {
    List<IamRole> retired = roles.findAll().stream().filter(IamRole::builtIn).toList();
    if (retired.isEmpty()) return;

    for (IamRole role : retired) {
      int withdrawn = 0;
      for (IamUser user : users.findAll()) {
        if (user.roles() == null || !user.roles().contains(role.name())) continue;
        users.save(
            user.withRoles(user.roles().stream().filter(r -> !r.equals(role.name())).toList()));
        withdrawn++;
      }
      roles.deleteByName(role.name());
      log.warn(
          "Retired the built-in role {} and withdrew it from {} account(s). Roles are defined in"
              + " the console now — recreate what this organisation actually needs there. Anyone"
              + " left without a role reaches the console only via hr.auth.admin-emails.",
          role.name(),
          withdrawn);
    }
  }

  /**
   * Retired with the role it assigned.
   *
   * <p>{@code hr.auth.seed-hr-ops-admins} handed the HR Ops Admin role to a configured address on
   * startup, so a test account could reach the console before anyone existed to grant it access.
   * There is no such role to hand out any more, and re-creating one here would put back exactly
   * the ships-its-own-roles behaviour that was just removed.
   *
   * <p>Nothing is lost: the addresses that need a way in are covered by {@code
   * hr.auth.admin-emails}, which grants every permission without an assignment. The property is
   * now inert, and left documented here rather than silently ignored — a config key that looks
   * like it does something is worse than one that is gone.
   */
  @SuppressWarnings("unused")
  private void seedAdminAssignments() {
    for (String email : seedAdmins) {
      String normalised = normaliseEmail(email);
      if (normalised.isBlank()) continue;

      Optional<IamUser> existing = users.findByEmail(normalised);
      if (existing.isPresent()) {
        if (existing.get().roles() != null && existing.get().roles().contains(HR_OPS_ADMIN_ROLE)) {
          continue;
        }
        // Present but without the role. Left alone: see above — this may be a revocation.
        log.info(
            "{} already has an access record without {}; leaving it as configured in the console",
            normalised,
            HR_OPS_ADMIN_ROLE);
        continue;
      }

      users.save(
          new IamUser(
              null,
              normalised,
              normalised.split("@")[0],
              List.of(HR_OPS_ADMIN_ROLE),
              Instant.now().toString(),
              "system:seed"));
      log.info("Seeded {} with the {} role", normalised, HR_OPS_ADMIN_ROLE);
    }
  }

  private void ensureBuiltIn(String name, String label, String description, Set<String> permissions) {
    Optional<IamRole> existing = roles.findByName(name);
    if (existing.isPresent()) {
      // Permissions are code, not data: a built-in whose grants drift from the constants above
      // would make the catalogue a lie. Re-asserted on every boot so adding a permission to a
      // built-in role ships by deploying, not by remembering to edit the database.
      IamRole role = existing.get();
      if (!permissions.equals(role.permissions())) {
        roles.save(role.withPermissions(permissions));
        log.info("Updated built-in role {} permissions to {}", name, permissions);
      }
      return;
    }
    roles.save(
        new IamRole(
            null, name, label, description, permissions, true, Instant.now().toString(), "system"));
    log.info("Created built-in IAM role {}", name);
  }

  /**
   * Gives pre-split roles the area permissions their console grant used to imply.
   *
   * <p>{@code admin.console} was once the whole console: holding it meant the escalation queue,
   * the digest, the knowledge base and access management together. Now it only opens the door, so
   * a role saved before this change would still list one permission and reach nothing — every tab
   * gone, with no error to explain it. This restores what those roles could already do.
   *
   * <p>With one deliberate exception: {@link Permissions#ADMIN_TICKETS_SENSITIVE} is not granted.
   * Harassment and grievance reports were reachable by anyone with the console, and making that an
   * explicit decision is the entire reason the queue was split — migrating it automatically would
   * hand it back to everyone and leave the split cosmetic. It is a real reduction in access for
   * anyone who had it, so it is logged rather than done quietly.
   *
   * <p>Built-ins are skipped: {@link #ensureBuiltIn} re-asserts those from the constants on every
   * boot, and would overwrite anything decided here on the next start anyway.
   */
  private void migrateToAreaPermissions() {
    int upgraded = 0;
    for (IamRole role : roles.findAll()) {
      if (role.builtIn()) continue;
      if (!role.grants(Permissions.ADMIN_CONSOLE)) continue;

      // Already split, or deliberately narrowed since. Only a role that has the front door and
      // nothing behind it can be a pre-split one.
      boolean hasAnyArea = Permissions.AREAS.stream().anyMatch(role::grants);
      if (hasAnyArea) continue;

      Set<String> granted = new LinkedHashSet<>(role.permissions());
      granted.add(Permissions.ADMIN_TICKETS);
      granted.add(Permissions.ADMIN_DIGEST);
      granted.add(Permissions.ADMIN_KNOWLEDGE);
      granted.add(Permissions.ADMIN_ACCESS);

      roles.save(role.withPermissions(granted));
      upgraded++;
      log.info(
          "Role {} carried only admin.console, which used to mean the whole console — granted the"
              + " areas it already reached. Sensitive escalations were NOT granted: assign"
              + " {} in the console if this role is meant to handle them.",
          role.name(),
          Permissions.ADMIN_TICKETS_SENSITIVE);
    }
    if (upgraded > 0) {
      log.info("Upgraded {} pre-split role(s) to per-area permissions", upgraded);
    }
  }

  /**
   * Moves anyone holding a retired role onto the equivalent new one, then removes the old roles.
   *
   * <p>Runs before the old roles are deleted, and that order is the point: {@code hr-admin} held
   * the console, so dropping it without reassigning would lock out every admin whose access came
   * from an assignment rather than the break-glass list — including, potentially, everyone.
   *
   * <p>{@code hr-admin} maps to {@link #HR_OPS_ADMIN_ROLE} rather than plain HR Ops because it
   * granted unrestricted console access, and silently demoting existing admins would be a
   * surprising way to learn about a migration.
   */
  private void migrateLegacyRoles() {
    int moved = 0;
    for (IamUser user : users.findAll()) {
      if (user.roles() == null || user.roles().isEmpty()) continue;

      List<String> updated = new ArrayList<>();
      for (String role : user.roles()) {
        if (LEGACY_HR_ADMIN_ROLE.equals(role)) {
          // Dropped rather than remapped. Its old target, HR Ops Admin, is itself retired now, so
          // pointing at it would assign a name that resolves to nothing — access that reads as
          // granted in the console and is refused at every endpoint. An account left with no role
          // reaches the console through hr.auth.admin-emails, or is granted one there.
          log.warn(
              "Dropped the retired {} role from an access record. Roles are defined in the console"
                  + " now — grant this account one if it still needs the console.",
              LEGACY_HR_ADMIN_ROLE);
        } else if (LEGACY_EMPLOYEE_ROLE.equals(role)) {
          // Granted nothing, and every signed-in account is an employee by default — see
          // IamUser. Dropping it changes no one's access.
          continue;
        } else if (!updated.contains(role)) {
          updated.add(role);
        }
      }

      if (!updated.equals(user.roles())) {
        users.save(user.withRoles(updated));
        moved++;
      }
    }
    if (moved > 0) log.info("Migrated {} access record(s) off the retired roles", moved);

    for (String retired : List.of(LEGACY_HR_ADMIN_ROLE, LEGACY_EMPLOYEE_ROLE)) {
      roles
          .findByName(retired)
          .ifPresent(
              role -> {
                roles.deleteByName(retired);
                log.info("Removed retired IAM role {}", retired);
              });
    }
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

  /**
   * Creates a role with a chosen set of permissions.
   *
   * <p>This threw for a while — the role set was fixed at HR Ops and HR Ops Admin, because with
   * {@code admin.console} as the only meaningful grant a third role could differ from those two in
   * name only. That is no longer true: the console is now seven permissions covering its separate
   * areas, so "maintains the policy library", "works the escalation queue" and "handles grievances"
   * are genuinely different sets rather than three labels for the same access.
   *
   * <p>The name is derived from the label and never changes afterwards. Assignments on {@link
   * IamUser} reference it, so a rename would be a migration rather than an edit — see {@link
   * #updateRole}, which offers everything except that.
   *
   * @throws IllegalArgumentException if the label is empty, the derived name collides with an
   *     existing role, or any permission is not in {@link Permissions#CATALOGUE}
   */
  public IamRole createRole(
      String rawName, String label, String description, Set<String> permissions, String actor) {

    String name = normaliseRoleName(rawName == null || rawName.isBlank() ? label : rawName);
    name = name.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+)|(-+$)", "");

    if (name.isBlank()) {
      throw new IllegalArgumentException("A role needs a name.");
    }
    if (roles.findByName(name).isPresent()) {
      throw new IllegalArgumentException("A role called \"" + name + "\" already exists.");
    }

    // Validated before the save, not after: an unknown permission stored against a role reads as
    // granted access in the console while every endpoint goes on refusing it.
    Set<String> granted = validatedPermissions(permissions);

    IamRole created =
        new IamRole(
            null,
            name,
            cleanLabel(label, name),
            cleanDescription(description),
            granted,
            // Never built-in. Those are re-asserted from the constants on every boot, so a role
            // created here that claimed to be one would have its permissions overwritten at the
            // next restart.
            false,
            Instant.now().toString(),
            actor);

    IamRole saved = roles.save(created);
    log.info("Created IAM role {} with {} by {}", name, granted, actor);
    return saved;
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
    // No role is undeletable any more — there is no set the console is built around. What keeps a
    // deletion from being final is hr.auth.admin-emails, which reaches the console with no
    // assignment at all and can grant a replacement.

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
