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
