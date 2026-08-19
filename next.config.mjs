/** @type {import('next').NextConfig} */

// The knowledge base API is a Spring Boot service (see `backend/`). Proxying
// rather than pointing the browser at :8080 directly keeps every fetch in
// `lib/knowledge/api.ts` on a relative `/api/...` path — same origin, so no CORS
// configuration, no build-time API URL, and nothing in the frontend that has to
// know the backend is Java.
const API_BACKEND = process.env.KNOWLEDGE_API_URL || 'http://127.0.0.1:8080'

const nextConfig = {
  typescript: {
    ignoreBuildErrors: true,
  },
  experimental: {
    /*
     * The proxy above buffers request bodies and defaults to 10 MB, which made it — not the
     * backend — the real cap on a policy upload. The two limits happened to be identical while
     * the backend also allowed 10 MB, so raising only the Java side changed nothing: a 12 MB
     * .docx died here with `Request body exceeded 10MB` and the admin saw "Internal Server
     * Error", with nothing in the backend log because the request never arrived.
     *
     * Matched to the backend's `spring.servlet.multipart.max-request-size` so there is one
     * binding constraint and it is the one that can explain itself. KnowledgeController rejects
     * an oversized file with a readable per-file message; this layer must not pre-empt that with
     * a socket hang-up.
     *
     * NOT `middlewareClientMaxBodySize`, which the error message still names: it is deprecated in
     * Next 16, and setting both keys is a startup error.
     */
    proxyClientMaxBodySize: '180mb',

    /*
     * Milliseconds, and the default is 30000 — which a policy upload can genuinely exceed. A
     * scanned PDF is OCR'd page by page through the vision API before anything is indexed, and
     * that ran 31.8s for a six-page file: the backend answered 200 with a per-file result, the
     * proxy had already hung up at 30s, and the console showed "Request failed (500)" for an
     * upload the server was still handling correctly.
     *
     * Five minutes covers a batch of scans. It is a ceiling on a human-initiated admin action
     * with a progress indicator behind it, not on anything an employee waits for.
     */
    proxyTimeout: 300_000,
  },
  images: {
    unoptimized: true,
  },
  async rewrites() {
    return [
      {
        source: '/api/:path*',
        destination: `${API_BACKEND}/api/:path*`,
      },
      // Microsoft Entra sign-in kickoff. `/oauth2/authorization/azure-ad` is
      // Spring Security's own path and sits outside /api, so it needs forwarding
      // explicitly. The callback — /api/auth/callback/azure-ad, the URI registered
      // in Entra — is already covered by the /api rule above.
      //
      // Both stay on this origin so the session cookie belongs to :3000, which is
      // where the browser is.
      {
        source: '/oauth2/:path*',
        destination: `${API_BACKEND}/oauth2/:path*`,
      },
    ]
  },
}

export default nextConfig
