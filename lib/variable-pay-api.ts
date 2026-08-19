/**
 * Browser-side client for the variable pay plan.
 *
 * The plan is the policy as data: what the company achieved this year, the bands that turn those
 * results into funding levels, how much of a payout rides on the company versus the individual at
 * each grade, and what each appraisal rating funds. It is editable because every one of those
 * changes — the achieved figures annually, the thresholds between policy versions, and the policy
 * reserves the right to revise any of it.
 *
 * Gated by `admin.payroll` in the Spring filter chain, so a caller without it gets a 403 rather
 * than a partial result.
 */

/** One achievement band and the funding multiplier it carries. */
export interface Band {
  name: string;
  /** null on the open bottom band ("below 405 Cr"). */
  fromInclusive: number | null;
  /** null on the open top band ("430 Cr and above"). */
  toExclusive: number | null;
  funding: number;
}

/** How the payout splits between company and individual at a range of grades. */
export interface GradeWeight {
  fromLevel: number;
  toLevel: number;
  companyPercent: number;
  individualPercent: number;
}

/** What an appraisal rating funds on the individual share. */
export interface RatingPayout {
  rating: number;
  label: string;
  payoutPercent: number;
}

export interface VariablePayPlan {
  id: string | null;
  planKey: string;
  fyLabel: string;
  policyVersion: string;
  /** null until HR declares the year's result — not the same as a result of zero. */
  revenueActualCr: number | null;
  grrActualPercent: number | null;
  revenueBands: Band[];
  grrBands: Band[];
  revenueWeightPercent: number;
  grrWeightPercent: number;
  gradeWeights: GradeWeight[];
  ratingPayouts: RatingPayout[];
  excludedVerticals: string[];
  updatedAt: string;
  updatedBy: string;
}

async function json<T>(res: Response): Promise<T> {
  const body = (await res.json().catch(() => null)) as (T & { error?: string }) | null;
  if (!res.ok) throw new Error(body?.error || `Request failed (${res.status})`);
  if (!body) throw new Error('Empty response from the server.');
  return body;
}

export async function fetchVariablePayPlan(): Promise<VariablePayPlan> {
  return json<VariablePayPlan>(
    await fetch('/api/payroll/variable-pay/plan', { cache: 'no-store' })
  );
}

export async function saveVariablePayPlan(plan: VariablePayPlan): Promise<VariablePayPlan> {
  return json<VariablePayPlan>(
    await fetch('/api/payroll/variable-pay/plan', {
      method: 'PUT',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(plan),
    })
  );
}

/**
 * Which band a value falls in, for showing the effect of a figure as it is typed.
 *
 * Mirrors `VariablePayPlan.Band.covers` on the server: lower bound inclusive, upper exclusive, so
 * 413 is Target rather than Near Target. This is presentation only — the payout every employee is
 * told is computed server-side from the stored plan, never here.
 */
export function bandFor(bands: Band[], value: number | null): Band | null {
  if (value === null || Number.isNaN(value)) return null;
  return (
    bands.find(
      (b) =>
        (b.fromInclusive === null || value >= b.fromInclusive) &&
        (b.toExclusive === null || value < b.toExclusive)
    ) ?? null
  );
}
