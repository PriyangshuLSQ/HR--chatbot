package com.leadsquared.hr.knowledge.model;

import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A named bundle of permissions that can be assigned to people.
 *
 * <p>Roles carry a <i>set</i> of permissions rather than a single admin flag, so
 * that the next thing HR wants to delegate — reading the escalation queue without
 * being able to edit the knowledge base, say — is a new permission rather than a
 * new column. Today exactly one permission is defined; see {@link Permissions}.
 *
 * <p>Field names are the wire contract with {@code lib/iam-api.ts}.
 *
 * @param name the stable identifier, lower-case and hyphenated. Assignments on
 *     {@link IamUser} reference this, not the id, so a role reads the same in the
 *     database as it does in the console.
 * @param label what the console shows. Free text, renameable.
 * @param builtIn roles this application depends on. They cannot be deleted or have
 *     their permissions edited — {@code hr-admin} losing {@link
 *     Permissions#ADMIN_CONSOLE} would leave nobody able to reach the page that
 *     hands it back out.
 */
@Document(collection = "iam_roles")
public record IamRole(
    @Id String id,
    @Indexed(unique = true) String name,
    String label,
    String description,
    Set<String> permissions,
    boolean builtIn,
    String createdAt,
    String createdBy) {

  public boolean grants(String permission) {
    return permissions != null && permissions.contains(permission);
  }

  public IamRole withPermissions(Set<String> updated) {
    return new IamRole(id, name, label, description, updated, builtIn, createdAt, createdBy);
  }

  public IamRole withDetails(String newLabel, String newDescription) {
    return new IamRole(id, name, newLabel, newDescription, permissions, builtIn, createdAt, createdBy);
  }
}
