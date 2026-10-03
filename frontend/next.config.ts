import type { NextConfig } from "next";

const keycloakOrigin = (() => {
  const keycloakUrl = process.env.NEXT_PUBLIC_KEYCLOAK_URL;
  if (!keycloakUrl) return undefined;

  try {
    return new URL(keycloakUrl).origin;
  } catch {
    return undefined;
  }
})();

const connectSources = [
  "'self'",
  keycloakOrigin,
  "https://*.maptiler.com",
  "https://tile.openstreetmap.org",
  "https://kaart.maaamet.ee",
]
  .filter(Boolean)
  .join(" ");
const contentSecurityPolicy = [
  "default-src 'self'",
  `connect-src ${connectSources}`,
  "img-src 'self' data: blob: https://*.maptiler.com https://tile.openstreetmap.org https://kaart.maaamet.ee",
  "style-src 'self' 'unsafe-inline' https://*.maptiler.com",
  "script-src 'self' 'unsafe-inline'",
  "font-src 'self' data:",
  "worker-src 'self' blob:",
  "frame-ancestors 'none'",
  "base-uri 'self'",
  "form-action 'self'",
].join("; ");

const securityHeaders = [
  { key: "Content-Security-Policy", value: contentSecurityPolicy },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "X-Frame-Options", value: "DENY" },
  { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=()" },
  { key: "Strict-Transport-Security", value: "max-age=31536000; includeSubDomains" },
];

const nextConfig: NextConfig = {
  output: "standalone",
  poweredByHeader: false,
  async headers() {
    return [
      {
        source: "/(.*)",
        headers: securityHeaders,
      },
    ];
  },
};

export default nextConfig;
