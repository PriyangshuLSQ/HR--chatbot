/**
 * Robin's face, and the LeadSquared attribution.
 *
 * One place, because the avatar appears on every bot message, in three headers
 * and on the sign-in page — and because the crop needs thinking about exactly
 * once. `robin.png` is a 1024×1536 portrait, so a square avatar has to discard a
 * third of it; `object-position: top` keeps the head rather than centring on the
 * middle of the body.
 */

export const ROBIN_NAME = 'Robin';

/**
 * One 160px derivative, generated from the original with `sips` (22 KB against
 * the source's 2.5 MB).
 *
 * The original is fine as an asset and wasteful as an avatar: it is 1024×1536 and
 * `next.config.mjs` sets `images.unoptimized`, so nothing downsizes it at build
 * time — every chat page load would fetch 2.5 MB to draw a 30px circle.
 *
 * 160px covers every use here with room for high-density screens: 30px avatars at
 * 5x, the 44px sign-in badge at 3.6x. A second larger file was generated first and
 * deleted — nothing rendered big enough to reach for it.
 */
const SRC = '/robin-160.png';

/**
 * @param size rendered box in px.
 * @param rounded a circle by default; the headers use a squared-off badge to
 *     match the surrounding chrome.
 */
export function RobinAvatar({
  size = 30,
  rounded = 'full',
  ring = false,
}: {
  size?: number;
  rounded?: 'full' | 'badge';
  ring?: boolean;
}) {
  return (
    <img
      src={SRC}
      alt=""
      width={size}
      height={size}
      // Decorative: every place this appears already labels the speaker in text,
      // so announcing "Robin" again would just be noise on a screen reader.
      aria-hidden
      style={{
        width: size,
        height: size,
        flexShrink: 0,
        objectFit: 'cover',
        objectPosition: 'top',
        borderRadius: rounded === 'full' ? '50%' : Math.max(6, Math.round(size * 0.28)),
        background: 'var(--primary-soft)',
        boxShadow: ring ? '0 0 0 1px var(--border)' : undefined,
      }}
    />
  );
}

/**
 * The attribution line. Shown on the sign-in page, under the chat sidebar and in
 * the admin console footer — the three places someone lands and stays.
 */
export function PoweredByLeadSquared({ align = 'center' }: { align?: 'center' | 'left' }) {
  return (
    <p
      style={{
        fontSize: '0.6875rem',
        color: 'var(--faint)',
        textAlign: align,
        letterSpacing: '0.02em',
      }}
    >
      Powered by <strong style={{ fontWeight: 600 }}>LeadSquared</strong>
    </p>
  );
}
