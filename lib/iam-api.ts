/**
 * Browser-side client for access management.
 *
 * Nothing here decides access — it only reads and writes what the server decides.
 * Every route is admin-gated in the Spring filter chain, so a non-admin calling
 * these directly gets a 403 rather than a partial result, and the console's own
 * guard is a courtesy on top of that rather than the control.
 */

/** A permission the server recognises. The console renders checkboxes from this. */
export interface PermissionDefinition {
  key: string;
  label: string;
  description: string;
}

export interface Role {
  id: string;
  /** Stable identifier. Assignments reference this, so it is never renamed. */
  name: string;
  label: string;
  description: string;
  permissions: string[];
  /** Ships with the app: cannot be deleted, and its permissions are fixed. */
  builtIn: boolean;
  createdAt: string;
  createdBy: string;
}

export interface IamUser {
  id: string;
  email: string;
  name: string;
  roles: string[];
  createdAt: string;
  createdBy: string;
}

export interface RolesResponse {
  roles: Role[];
  permissions: PermissionDefinition[];
}

export interface UsersResponse {
  users: IamUser[];
  /**
   * Accounts that hold the console through `hr.auth.admin-emails` instead of an
   * assignment. Shown so the page cannot imply the list below is everyone who can
   * get in.
   */
  breakGlass: string[];
  /** So the console can refuse to let you revoke yourself. */
  signedInAs: string | null;
}

/** The permission that grants the admin console. Mirrors `Permissions.ADMIN_CONSOLE`. */
export const ADMIN_CONSOLE = 'admin.console';

async function json<T>(res: Response): Promise<T> {
  const body = (await res.json().catch(() => null)) as (T & { error?: string }) | null;
  if (!res.ok) throw new Error(body?.error || `Request failed (${res.status})`);
  if (!body) throw new Error('Empty response from the server.');
  return body;
}

// ---------------------------------------------------------------------------
// Roles
// ---------------------------------------------------------------------------

export async function fetchRoles(): Promise<RolesResponse> {
  return json<RolesResponse>(await fetch('/api/iam/roles', { cache: 'no-store' }));
}

export async function createRole(input: {
  name: string;
  label: string;
  description: string;
  permissions: string[];
}): Promise<Role> {
  return json<Role>(
    await fetch('/api/iam/roles', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(input),
    })
  );
}

export async function updateRole(
  name: string,
  input: { label?: string; description?: string; permissions?: string[] }
): Promise<Role> {
  return json<Role>(
    await fetch(`/api/iam/roles/${encodeURIComponent(name)}`, {
      method: 'PATCH',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(input),
    })
  );
}

export async function deleteRole(name: string): Promise<void> {
  await json(await fetch(`/api/iam/roles/${encodeURIComponent(name)}`, { method: 'DELETE' }));
}

// ---------------------------------------------------------------------------
// Users
// ---------------------------------------------------------------------------

export async function fetchIamUsers(): Promise<UsersResponse> {
  return json<UsersResponse>(await fetch('/api/iam/users', { cache: 'no-store' }));
}

/** Adds a record, or replaces the roles on the one already there. */
export async function addIamUser(input: {
  email: string;
  name: string;
  roles: string[];
}): Promise<IamUser> {
  return json<IamUser>(
    await fetch('/api/iam/users', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(input),
    })
  );
}

export async function setIamUserRoles(email: string, roles: string[]): Promise<IamUser> {
  return json<IamUser>(
    await fetch(`/api/iam/users/${encodeURIComponent(email)}`, {
      method: 'PATCH',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ roles }),
    })
  );
}

export async function removeIamUser(email: string): Promise<void> {
  await json(await fetch(`/api/iam/users/${encodeURIComponent(email)}`, { method: 'DELETE' }));
}

// ---------------------------------------------------------------------------
// Audit trail and sign-ins — HR Ops Admin only
// ---------------------------------------------------------------------------

/** One administrative action. Mirrors `AdminAuditEvent`. */
export interface AuditEvent {
  id: string;
  at: string;
  /** The signed-in email that performed it, or `unknown` if there was no session. */
  actor: string;
  /** A stable key: `policy.uploaded`, `access.granted`, and so on. */
  action: string;
  target: string;
  detail: string;
}

export interface AuditResponse {
  events: AuditEvent[];
  /** The action keys the server recognises, so the filter cannot offer a dead option. */
  actions: string[];
}

export async function fetchAudit(action?: string): Promise<AuditResponse> {
  const query = action ? `?action=${encodeURIComponent(action)}` : '';
  return json<AuditResponse>(await fetch(`/api/admin/audit${query}`, { cache: 'no-store' }));
}

/** One account's sign-in history, collapsed to a row. Mirrors `LoginRecord`. */
export interface LoginRecord {
  id: string;
  email: string;
  name: string;
  /** Present when the HR extract knows this address; null for joiners and admin accounts. */
  employeeCode: string | null;
  firstLoginAt: string;
  lastLoginAt: string;
  loginCount: number;
  method: string;
}

export async function fetchLogins(): Promise<{ logins: LoginRecord[] }> {
  return json<{ logins: LoginRecord[] }>(await fetch('/api/admin/logins', { cache: 'no-store' }));
}

/** What the console calls each action. Unknown keys fall back to the key itself. */
export const AUDIT_ACTION_LABELS: Record<string, string> = {
  'policy.uploaded': 'Policy uploaded',
  'policy.deleted': 'Policy deleted',
  'knowledge.reindexed': 'Knowledge re-indexed',
  'access.granted': 'Access granted',
  'access.changed': 'Access changed',
  'access.revoked': 'Access revoked',
  'role.updated': 'Role edited',
};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Whether a set of role names carries admin-console access, for display. */
export function grantsAdminConsole(roleNames: string[], roles: Role[]): boolean {
  return roleNames.some((name) =>
    roles.some((role) => role.name === name && role.permissions.includes(ADMIN_CONSOLE))
  );
}

/** Suggests a role name from a label, so nobody has to think about slug rules. */
export function slugify(label: string): string {
  return label
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
}
