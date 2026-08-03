package com.leadsquared.hr.knowledge.web;

import com.leadsquared.hr.knowledge.iam.IamService;
import com.leadsquared.hr.knowledge.model.IamRole;
import com.leadsquared.hr.knowledge.model.IamUser;
import com.leadsquared.hr.knowledge.model.Permissions;
import com.leadsquared.hr.knowledge.security.CurrentUser;
import com.leadsquared.hr.knowledge.security.SignedInUser;
import java.util.List;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Access management — roles, and who holds them.
 *
 * <p>Every route here is admin-only, enforced in the filter chain by {@code
 * AdminConsoleAccess} rather than by anything in this class. That is deliberate: an
 * endpoint that hands out the console must not depend on a controller author
 * remembering to check, and the check has to be the live one so a revoked admin
 * cannot use their stale session to grant it back.
 */
@RestController
@RequestMapping("/api/iam")
public class IamController {

  private final IamService iam;
  private final CurrentUser currentUser;

  public IamController(IamService iam, CurrentUser currentUser) {
    this.iam = iam;
    this.currentUser = currentUser;
  }

  // -------------------------------------------------------------------------
  // Roles
  // -------------------------------------------------------------------------

  /**
   * @param permissions the closed set a role may grant, so the console renders
   *     checkboxes from the server's definition instead of a hard-coded copy that
   *     can drift out of step with what the backend enforces
   */
  public record RolesResponse(List<IamRole> roles, List<Permissions.Definition> permissions) {}

  @GetMapping("/roles")
  public RolesResponse roles() {
    return new RolesResponse(iam.listRoles(), Permissions.CATALOGUE);
  }

  public record RoleRequest(String name, String label, String description, Set<String> permissions) {}

  @PostMapping("/roles")
  public ResponseEntity<?> createRole(@RequestBody(required = false) RoleRequest body) {
    if (body == null) return ApiErrors.badRequest("Expected a JSON body.");

    return ResponseEntity.ok(
        iam.createRole(body.name(), body.label(), body.description(), body.permissions(), actor()));
  }

  @PatchMapping("/roles/{name}")
  public ResponseEntity<?> updateRole(
      @PathVariable String name, @RequestBody(required = false) RoleRequest body) {

    if (body == null) return ApiErrors.badRequest("Expected a JSON body.");

    return ResponseEntity.ok(
        iam.updateRole(name, body.label(), body.description(), body.permissions(), actor()));
  }

  @DeleteMapping("/roles/{name}")
  public ResponseEntity<?> deleteRole(@PathVariable String name) {
    iam.deleteRole(name);
    return ResponseEntity.ok(new Ok(true));
  }

  // -------------------------------------------------------------------------
  // Users
  // -------------------------------------------------------------------------

  /**
   * @param breakGlass addresses that hold the console through {@code
   *     hr.auth.admin-emails} rather than through an assignment. Surfaced so the
   *     console cannot imply it is the whole picture — someone deleting the last
   *     admin record here would otherwise think they had locked everyone out.
   */
  public record UsersResponse(List<IamUser> users, List<String> breakGlass, String signedInAs) {}

  @GetMapping("/users")
  public UsersResponse users() {
    return new UsersResponse(
        iam.listUsers(), List.copyOf(adminEmails()), currentUser.get().map(SignedInUser::email).orElse(null));
  }

  public record UserRequest(String email, String name, List<String> roles) {}

  @PostMapping("/users")
  public ResponseEntity<?> addUser(@RequestBody(required = false) UserRequest body) {
    if (body == null) return ApiErrors.badRequest("Expected a JSON body.");

    return ResponseEntity.ok(iam.upsertUser(body.email(), body.name(), body.roles(), actor()));
  }

  @PatchMapping("/users/{email}")
  public ResponseEntity<?> setRoles(
      @PathVariable String email, @RequestBody(required = false) UserRequest body) {

    if (body == null) return ApiErrors.badRequest("Expected a JSON body.");

    return ResponseEntity.ok(iam.setUserRoles(email, body.roles()));
  }

  /**
   * Removes an access record.
   *
   * <p>Refuses to remove your own, which is the one mistake in here that cannot be
   * undone from the UI: an admin who revokes themselves loses the page they would
   * need in order to put it back.
   */
  @DeleteMapping("/users/{email}")
  public ResponseEntity<?> removeUser(@PathVariable String email) {
    String me = currentUser.get().map(SignedInUser::email).orElse(null);
    if (me != null && me.equalsIgnoreCase(email.trim())) {
      return ApiErrors.badRequest(
          "That is your own access. Ask another admin to remove it, so you cannot lock yourself out.");
    }

    iam.deleteUser(email);
    return ResponseEntity.ok(new Ok(true));
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private String actor() {
    return currentUser.get().map(SignedInUser::email).orElse("unknown");
  }

  private Set<String> adminEmails() {
    return currentUser.configuredAdmins();
  }

  /** The {@code {ok: true}} body the frontend's delete helpers expect. */
  private record Ok(boolean ok) {}
}
