/**
 * Browser-side client for the three revenue-function pay policies — US, India Sales, PS & CSM.
 *
 * Separate from `variable-pay-api.ts`, which serves the Non-Sales plan. The two are different
 * shapes: Non-Sales pays on a company result and an appraisal rating weighted by grade, while
 * these pay on weighted business metrics by function and level. One editor for both would have to
 * hide most of itself most of the time.
 *
 * Gated by `admin.payroll` in the Spring filter chain, so a caller without it gets a 403.
 */

export type SlabKind = 'ACHIEVEMENT_MULTIPLIES' | 'ACHIEVEMENT_BANDED' | 'VARIANCE_POINTS';

export interface Band {
  name: string;
  /** null on the open bottom band. Exclusive — the policies write ">40–80%". */
  fromExclusive: number | null;
  /** null on the open top band. */
  toInclusive: number | null;
  factorPercent: number;
}

export interface Slab {
  key: string;
  label: string;
  kind: SlabKind;
  /** India only: the 50% tier pays solely if the BU target is also met. */
  buTargetGates: boolean;
  bands: Band[];
}

export interface Component {
  name: string;
  weightPercent: number;
  slabKey: string;
  frequency: string;
  note: string | null;
}

export interface Kicker {
  name: string;
  ratePercent: number;
  basis: string;
  frequency: string;
}

export interface Role {
  key: string;
  function: string;
  level: string;
  components: Component[];
  kickers: Kicker[];
}

export interface OrgValue {
  key: string;
  label: string;
  /** null where HR has not declared it — not the same as zero. */
  value: number | null;
  unit: string;
  note: string | null;
}

export interface FunctionPayPlan {
  id: string | null;
  planKey: string;
  label: string;
  fyLabel: string;
  policyVersion: string;
  currency: string;
  slabs: Slab[];
  roles: Role[];
  orgValues: OrgValue[];
  updatedAt: string;
  updatedBy: string;
}

async function json<T>(res: Response): Promise<T> {
  const body = (await res.json().catch(() => null)) as (T & { error?: string }) | null;
  if (!res.ok) throw new Error(body?.error || `Request failed (${res.status})`);
  if (!body) throw new Error('Empty response from the server.');
  return body;
}

export async function fetchFunctionPayPlans(): Promise<{ plans: FunctionPayPlan[] }> {
  return json<{ plans: FunctionPayPlan[] }>(
    await fetch('/api/payroll/function-pay/plans', { cache: 'no-store' })
  );
}

export async function saveFunctionPayPlan(plan: FunctionPayPlan): Promise<FunctionPayPlan> {
  return json<FunctionPayPlan>(
    await fetch(`/api/payroll/function-pay/plans/${encodeURIComponent(plan.planKey)}`, {
      method: 'PUT',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(plan),
    })
  );
}

/** What a slab does with its input, in words the console can show beside the bands. */
export function slabKindLabel(kind: SlabKind): string {
  switch (kind) {
    case 'ACHIEVEMENT_MULTIPLIES':
      return 'factor × achievement% × component';
    case 'VARIANCE_POINTS':
      return 'variance in points vs target; factor alone';
    default:
      return 'factor applies to the component alone';
  }
}
