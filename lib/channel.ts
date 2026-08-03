/**
 * Delivery channels.
 *
 * The same assistant is reachable from Microsoft Teams (via Entra ID SSO) and
 * from the HR portal. The channel is stamped on every ticket and every piece of
 * feedback so analytics can compare experience across surfaces — a query that
 * fails in Teams but works on the portal usually points at a client issue
 * rather than a knowledge-base gap.
 */

export interface Channel {
  id: 'portal' | 'teams';
  label: string;
  /** How the session was authenticated on this surface. */
  auth: string;
}

export const CHANNELS: Channel[] = [
  { id: 'portal', label: 'HR Portal', auth: 'Portal session' },
  { id: 'teams', label: 'Microsoft Teams', auth: 'Microsoft Entra ID SSO' },
];

/**
 * Detects the surface the assistant is embedded in.
 *
 * The Teams web/desktop client hosts apps in an iframe and appends its own
 * query hints. Falling back to the portal is the safe default: it never
 * over-claims an SSO session that isn't there.
 */
export function getChannel(): Channel {
  const portal = CHANNELS[0];
  const teams = CHANNELS[1];
  if (typeof window === 'undefined') return portal;

  try {
    const params = new URLSearchParams(window.location.search);
    if (params.get('channel') === 'teams' || params.has('tid') || params.has('teamsContext')) {
      return teams;
    }
    // Carried over from the sign-in surface, so the channel survives navigation.
    const stored = window.localStorage.getItem('hr_channel');
    if (stored === 'teams') return teams;
    if (stored === 'portal') return portal;
    // Teams renders embedded apps in an iframe; a bare portal visit does not.
    const framed = window.self !== window.top;
    if (framed && /teams\.microsoft\.com/i.test(document.referrer)) return teams;
  } catch {
    // Cross-origin frame access throws — treat as the portal.
  }

  return portal;
}
