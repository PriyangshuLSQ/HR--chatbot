import { Analytics } from '@vercel/analytics/next'
import type { Metadata, Viewport } from 'next'
import { Inter, JetBrains_Mono } from 'next/font/google'
import { ChatbotAuthProvider } from '@/lib/chatbot-auth'
import './globals.css'

/*
 * Inter, which is what the interfaces this is measured against use or approximate —
 * ChatGPT's Söhne and Claude's Styrene are both licensed, and Inter is the open
 * face designed for the same job: dense UI text on screen at small sizes.
 *
 * Loaded through next/font rather than a stylesheet link, which matters for more
 * than tidiness. It self-hosts the files at build time, so there is no request to
 * Google on page load — no third-party round trip in front of the first paint, and
 * no employee's IP handed to an ad network on the way to asking about their payslip.
 * It also injects a size-adjusted local fallback, which is what stops the layout
 * jumping when the webfont lands.
 */
const inter = Inter({
  subsets: ['latin'],
  display: 'swap',
  variable: '--font-inter',
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
    <html lang="en" className={`${inter.variable} ${mono.variable}`}>
      <body className="antialiased">
        <ChatbotAuthProvider>
          {children}
        </ChatbotAuthProvider>
        {process.env.NODE_ENV === 'production' && <Analytics />}
      </body>
    </html>
  )
}
