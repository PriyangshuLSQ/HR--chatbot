/**
 * Robin's mark, and the LeadSquared attribution.
 *
 * One place, because the avatar appears on every bot message, in three headers and on the
 * sign-in page.
 *
 * `robin.png` is now a 1254×1254 square app icon — a bird on a rounded-square gradient. The
 * previous artwork was a 1024×1536 portrait, which is why this file used to crop to the top
 * third: a square avatar had to discard the body. A square source needs no crop at all, so that
 * is gone.
 */

export const ROBIN_NAME = 'Robin';

/**
 * One 160px derivative, generated from the original with
 * `sips -Z 160 robin.png --out robin-160.png` (24 KB against the source's 800 KB).
 *
 * The original is fine as an asset and wasteful as an avatar: `next.config.mjs` sets
 * `images.unoptimized`, so nothing downsizes it at build time — every chat page load would fetch
 * the full file to draw a 30px badge.
 *
 * 160px covers every use here with room for high-density screens: 30px avatars at 5x, the 44px
 * sign-in badge at 3.6x.
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
        // Square source into a square box, so `cover` scales without cropping and there is no
        // object-position to choose. The old top-crop was there for a portrait.
        objectFit: 'cover',
        borderRadius: rounded === 'full' ? '50%' : Math.max(6, Math.round(size * 0.28)),
        // Shows only in the corners a circular mask leaves, and behind the icon's own transparent
        // margin — the artwork carries its own gradient.
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
