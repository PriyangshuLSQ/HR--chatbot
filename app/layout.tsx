import { Analytics } from '@vercel/analytics/next'
import type { Metadata, Viewport } from 'next'
import { Figtree, JetBrains_Mono } from 'next/font/google'
import { ChatbotAuthProvider } from '@/lib/chatbot-auth'
import { THEME_BOOTSTRAP } from '@/lib/theme'
import './globals.css'

/*
 * Figtree, chosen to read like Claude's interface.
 *
 * Claude sets Styrene (Commercial Type) and Tiempos (Klim); both are licensed and
 * cannot ship here. Styrene is a geometric grotesque — wide, low contrast, generous
 * x-height, terminals with a bit of personality — so the shortlist was the open faces
 * with that character: Figtree, Plus Jakarta Sans, DM Sans, Manrope.
 *
 * Figtree won on how it behaves in THIS app rather than in a specimen. Rendered side
 * by side at the sizes actually used here, Plus Jakarta Sans and Manrope both went
 * cramped under the -0.02em tracking the headings carry ("answers in seconds" nearly
 * collides), and DM Sans draws a hyphen long enough to read as an en-dash in
 * "half-sentences". Figtree holds its spacing at display and body size both.
 *
 * It replaced Inter, which is excellent but deliberately neutral and close to
 * Helvetica — right for a dense dashboard, and not what was asked for.
 *
 * Loaded through next/font rather than a stylesheet link, which matters for more
 * than tidiness. It self-hosts the files at build time, so there is no request to
 * Google on page load — no third-party round trip in front of the first paint, and
 * no employee's IP handed to an ad network on the way to asking about their payslip.
 * It also injects a size-adjusted local fallback, which is what stops the layout
 * jumping when the webfont lands.
 */
const sans = Figtree({
  subsets: ['latin'],
  display: 'swap',
  variable: '--font-ui',
})

/* For `code` spans — policy answers quote figures like `80C` and `Form 16`. */
const mono = JetBrains_Mono({
  subsets: ['latin'],
  display: 'swap',
  variable: '--font-mono-face',
})

export const metadata: Metadata = {
  title: 'Robin — HR Assistant | LeadSquared',
  description: 'Robin, the AI HR assistant for employee queries and policy support. Powered by LeadSquared.',
  generator: 'v0.app',
  icons: {
    icon: [
      {
        url: '/icon-light-32x32.png',
        media: '(prefers-color-scheme: light)',
      },
      {
        url: '/icon-dark-32x32.png',
        media: '(prefers-color-scheme: dark)',
      },
      {
        url: '/icon.svg',
        type: 'image/svg+xml',
      },
    ],
    apple: '/apple-icon.png',
  },
}

export const viewport: Viewport = {
  colorScheme: 'light dark',
  themeColor: [
    { media: '(prefers-color-scheme: light)', color: '#1e40af' },
    { media: '(prefers-color-scheme: dark)', color: '#60a5fa' },
  ],
}

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode
}>) {
  return (
    <html
      lang="en"
      className={`${sans.variable} ${mono.variable}`}
      suppressHydrationWarning
    >
      <head>
        {/*
          Runs before the first paint, so every page opens in the theme the employee chose —
          including the ones with no toggle of their own. Each page used to restore this in its
          own effect, which meant a frame of the wrong theme on the pages that remembered and
          permanently the wrong theme on /tickets, which did not.

          suppressHydrationWarning because this script mutates <html> before React hydrates, so
          the server-rendered attributes and the live DOM legitimately differ.
        */}
        <script dangerouslySetInnerHTML={{ __html: THEME_BOOTSTRAP }} suppressHydrationWarning />
      </head>
      <body className="antialiased">
        <ChatbotAuthProvider>
          {children}
        </ChatbotAuthProvider>
        {process.env.NODE_ENV === 'production' && <Analytics />}
      </body>
    </html>
  )
}
