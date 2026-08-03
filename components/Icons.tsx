'use client';

/**
 * Inline SVG icon set. Kept local rather than pulled from an icon package so
 * the bundle stays small and every icon inherits `currentColor`.
 */

interface IconProps {
  size?: number;
  className?: string;
  strokeWidth?: number;
}

function svgProps(size: number, strokeWidth: number) {
  return {
    width: size,
    height: size,
    viewBox: '0 0 24 24',
    fill: 'none' as const,
    stroke: 'currentColor',
    strokeWidth,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
    'aria-hidden': true,
    focusable: 'false' as const,
  };
}

export const MenuIcon = ({ size = 20, strokeWidth = 2 }: IconProps) => (
  <svg {...svgProps(size, strokeWidth)}>
    <line x1="3" y1="6" x2="21" y2="6" />
    <line x1="3" y1="12" x2="21" y2="12" />
    <line x1="3" y1="18" x2="21" y2="18" />
  </svg>
);

export const SendIcon = ({ size = 18, strokeWidth = 2 }: IconProps) => (
  <svg {...svgProps(size, strokeWidth)}>
    <path d="M22 2 11 13" />
    <path d="M22 2 15 22l-4-9-9-4Z" />
  </svg>
);

export const PlusIcon = ({ size = 18, strokeWidth = 2 }: IconProps) => (
  <svg {...svgProps(size, strokeWidth)}>
    <line x1="12" y1="5" x2="12" y2="19" />
    <line x1="5" y1="12" x2="19" y2="12" />
  </svg>
);

export const UploadIcon = ({ size = 18, strokeWidth = 2 }: IconProps) => (
  <svg {...svgProps(size, strokeWidth)}>
    <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
    <path d="m17 8-5-5-5 5" />
    <line x1="12" y1="3" x2="12" y2="15" />
  </svg>
);

export const FileIcon = ({ size = 16, strokeWidth = 2 }: IconProps) => (
  <svg {...svgProps(size, strokeWidth)}>
    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Z" />
    <path d="M14 2v6h6" />
  </svg>
);

export const RefreshIcon = ({ size = 15, strokeWidth = 2 }: IconProps) => (
  <svg {...svgProps(size, strokeWidth)}>
    <path d="M21 12a9 9 0 1 1-2.64-6.36" />
    <path d="M21 3v6h-6" />
  </svg>
);

export const ThumbUpIcon = ({ size = 15, strokeWidth = 1.9 }: IconProps) => (
  <svg {...svgProps(size, strokeWidth)}>
    <path d="M7 10v11H4a1 1 0 0 1-1-1v-9a1 1 0 0 1 1-1Z" />
    <path d="M7 10l4.2-7.1a1.6 1.6 0 0 1 3 .8V9h4.3a2 2 0 0 1 2 2.4l-1.4 7A2 2 0 0 1 17.1 20H7" />
  </svg>
);

export const ThumbDownIcon = ({ size = 15, strokeWidth = 1.9 }: IconProps) => (
  <svg {...svgProps(size, strokeWidth)}>
    <path d="M7 14V3H4a1 1 0 0 0-1 1v9a1 1 0 0 0 1 1Z" />
    <path d="M7 14l4.2 7.1a1.6 1.6 0 0 0 3-.8V15h4.3a2 2 0 0 0 2-2.4l-1.4-7A2 2 0 0 0 17.1 4H7" />
  </svg>
);

export const CheckIcon = ({ size = 16, strokeWidth = 2.4 }: IconProps) => (
  <svg {...svgProps(size, strokeWidth)}>
    <polyline points="20 6 9 17 4 12" />
  </svg>
);

export const CheckCircleIcon = ({ size = 16, strokeWidth = 2 }: IconProps) => (
  <svg {...svgProps(size, strokeWidth)}>
    <circle cx="12" cy="12" r="9" />
    <polyline points="8.5 12.2 11 14.7 15.8 9.6" />
  </svg>
);

export const ClockIcon = ({ size = 16, strokeWidth = 2 }: IconProps) => (
  <svg {...svgProps(size, strokeWidth)}>
    <circle cx="12" cy="12" r="9" />
    <polyline points="12 7.5 12 12 15.5 13.8" />
  </svg>
);

export const AlertIcon = ({ size = 16, strokeWidth = 2 }: IconProps) => (
  <svg {...svgProps(size, strokeWidth)}>
    <path d="M10.3 3.6 1.9 18a2 2 0 0 0 1.7 3h16.8a2 2 0 0 0 1.7-3L13.7 3.6a2 2 0 0 0-3.4 0Z" />
    <line x1="12" y1="9" x2="12" y2="13.5" />
    <line x1="12" y1="17" x2="12.01" y2="17" />
  </svg>
);

export const ShieldIcon = ({ size = 16, strokeWidth = 2 }: IconProps) => (
  <svg {...svgProps(size, strokeWidth)}>
    <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10Z" />
  </svg>
);

export const XIcon = ({ size = 18, strokeWidth = 2 }: IconProps) => (
  <svg {...svgProps(size, strokeWidth)}>
    <line x1="18" y1="6" x2="6" y2="18" />
    <line x1="6" y1="6" x2="18" y2="18" />
  </svg>
);

export const TicketIcon = ({ size = 16, strokeWidth = 2 }: IconProps) => (
  <svg {...svgProps(size, strokeWidth)}>
    <path d="M3 9V6a1 1 0 0 1 1-1h16a1 1 0 0 1 1 1v3a3 3 0 0 0 0 6v3a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-3a3 3 0 0 0 0-6Z" />
    <line x1="12" y1="8" x2="12" y2="16" strokeDasharray="2 2.5" />
  </svg>
);

export const CalendarIcon = ({ size = 16, strokeWidth = 2 }: IconProps) => (
  <svg {...svgProps(size, strokeWidth)}>
    <rect x="3" y="5" width="18" height="16" rx="2" />
    <line x1="3" y1="10" x2="21" y2="10" />
    <line x1="8" y1="3" x2="8" y2="6" />
    <line x1="16" y1="3" x2="16" y2="6" />
  </svg>
);

export const WalletIcon = ({ size = 16, strokeWidth = 2 }: IconProps) => (
  <svg {...svgProps(size, strokeWidth)}>
    <rect x="2.5" y="6" width="19" height="13" rx="2.5" />
    <path d="M2.5 10.5h19" />
    <circle cx="17" cy="15" r="1.2" fill="currentColor" stroke="none" />
  </svg>
);

export const LaptopIcon = ({ size = 16, strokeWidth = 2 }: IconProps) => (
  <svg {...svgProps(size, strokeWidth)}>
    <rect x="4" y="5" width="16" height="11" rx="1.5" />
    <path d="M2 19.5h20" />
  </svg>
);

export const SparkIcon = ({ size = 16, strokeWidth = 2 }: IconProps) => (
  <svg {...svgProps(size, strokeWidth)}>
    <path d="M12 3v3.5M12 17.5V21M4.5 12H8M16 12h3.5M6.7 6.7l2.5 2.5M14.8 14.8l2.5 2.5M17.3 6.7l-2.5 2.5M9.2 14.8l-2.5 2.5" />
  </svg>
);

export const TeamsIcon = ({ size = 18 }: IconProps) => (
  <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden focusable="false">
    <path
      d="M13.4 7.4h6.9c.6 0 1 .4 1 1v5.3a4.5 4.5 0 0 1-4.5 4.5 4.5 4.5 0 0 1-4.4-3.7V8.4c0-.6.4-1 1-1Z"
      fill="#5059c9"
    />
    <circle cx="18.1" cy="4.6" r="2.3" fill="#5059c9" />
    <circle cx="11.4" cy="4.3" r="2.9" fill="#7b83eb" />
    <path
      d="M3.6 7.4h11.2c.5 0 .9.4.9.9v6.3a6.5 6.5 0 0 1-13 0V8.3c0-.5.4-.9.9-.9Z"
      fill="#7b83eb"
    />
    <path d="M11.6 5.6H6.2v13.9a6.5 6.5 0 0 0 5.4-6.4Z" fill="#000" opacity=".12" />
    <rect x="1" y="6.2" width="11" height="11.6" rx="1.2" fill="#4b53bc" />
    <path d="M9.2 9.6H3.8v1.5h2v5.1h1.5v-5.1h1.9Z" fill="#fff" />
  </svg>
);

export const LogoutIcon = ({ size = 16, strokeWidth = 2 }: IconProps) => (
  <svg {...svgProps(size, strokeWidth)}>
    <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
    <polyline points="16 17 21 12 16 7" />
    <line x1="21" y1="12" x2="9" y2="12" />
  </svg>
);

export const SunIcon = ({ size = 16, strokeWidth = 2 }: IconProps) => (
  <svg {...svgProps(size, strokeWidth)}>
    <circle cx="12" cy="12" r="4" />
    <path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M19.1 4.9l-1.4 1.4M6.3 17.7l-1.4 1.4" />
  </svg>
);

export const MoonIcon = ({ size = 16, strokeWidth = 2 }: IconProps) => (
  <svg {...svgProps(size, strokeWidth)}>
    <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z" />
  </svg>
);

export const SearchIcon = ({ size = 16, strokeWidth = 2 }: IconProps) => (
  <svg {...svgProps(size, strokeWidth)}>
    <circle cx="11" cy="11" r="7" />
    <line x1="16.5" y1="16.5" x2="21" y2="21" />
  </svg>
);

export const TrashIcon = ({ size = 15, strokeWidth = 2 }: IconProps) => (
  <svg {...svgProps(size, strokeWidth)}>
    <polyline points="3 6 21 6" />
    <path d="M19 6l-.8 14a2 2 0 0 1-2 1.9H7.8a2 2 0 0 1-2-1.9L5 6" />
    <path d="M10 11v6M14 11v6M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2" />
  </svg>
);

export const EditIcon = ({ size = 15, strokeWidth = 2 }: IconProps) => (
  <svg {...svgProps(size, strokeWidth)}>
    <path d="M11 4H5a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2h13a2 2 0 0 0 2-2v-6" />
    <path d="M18.4 2.6a2 2 0 0 1 2.8 2.8L12 14.6l-4 1 1-4Z" />
  </svg>
);

export const BotIcon = ({ size = 18, strokeWidth = 1.9 }: IconProps) => (
  <svg {...svgProps(size, strokeWidth)}>
    <rect x="4" y="8" width="16" height="12" rx="3" />
    <path d="M12 8V4.5" />
    <circle cx="12" cy="3.2" r="1.3" fill="currentColor" stroke="none" />
    <circle cx="9.2" cy="13.8" r="1.15" fill="currentColor" stroke="none" />
    <circle cx="14.8" cy="13.8" r="1.15" fill="currentColor" stroke="none" />
  </svg>
);

/** Sound on — speaker cone with two arcs. */
export const SpeakerIcon = ({ size = 16, strokeWidth = 2 }: IconProps) => (
  <svg {...svgProps(size, strokeWidth)}>
    <path d="M11 5 6 9H3v6h3l5 4V5Z" />
    <path d="M15.5 8.5a5 5 0 0 1 0 7" />
    <path d="M18.5 6a9 9 0 0 1 0 12" />
  </svg>
);

/** Sound off — the same cone, arcs replaced by a cross so the two read as a pair. */
export const SpeakerOffIcon = ({ size = 16, strokeWidth = 2 }: IconProps) => (
  <svg {...svgProps(size, strokeWidth)}>
    <path d="M11 5 6 9H3v6h3l5 4V5Z" />
    <path d="M16 9l5 6" />
    <path d="M21 9l-5 6" />
  </svg>
);

/** Conversations — a speech bubble. Used by the collapsed sidebar rail. */
export const ChatIcon = ({ size = 16, strokeWidth = 2 }: IconProps) => (
  <svg {...svgProps(size, strokeWidth)}>
    <path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5Z" />
  </svg>
);

/** Collapse the sidebar — chevrons pointing back toward the edge. */
export const CollapseIcon = ({ size = 16, strokeWidth = 2 }: IconProps) => (
  <svg {...svgProps(size, strokeWidth)}>
    <path d="M11 17l-5-5 5-5" />
    <path d="M18 17l-5-5 5-5" />
  </svg>
);
