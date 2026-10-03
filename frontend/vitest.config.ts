import path from "node:path";
import { coverageConfigDefaults, defineConfig } from "vitest/config";

export default defineConfig({
  // Next.js keeps JSX as `preserve` for its own compiler. Vitest executes
  // source components directly through Vite/esbuild, so opt into React's
  // automatic JSX runtime instead of relying on a global `React` variable.
  esbuild: {
    jsx: "automatic",
    jsxImportSource: "react",
  },
  resolve: {
    alias: { "@": path.resolve(__dirname, ".") },
  },
  test: {
    include: ["**/*.test.ts", "**/*.test.tsx"],
    exclude: ["tests/e2e/**", "node_modules/**", ".next/**"],
    coverage: {
      provider: "v8",
      include: [
        "app/api/**/route.ts",
        "lib/server-auth.ts",
        "lib/api/**/*.ts",
      ],
      exclude: [
        ...coverageConfigDefaults.exclude,
        "lib/generated/**",
      ],
      reporter: ["text", "lcov", "html"],
      reportsDirectory: "coverage",
      thresholds: {
        // Keep broad feature-module measurement visible while the migration
        // expands. The security-sensitive BFF/auth/transport layer has the
        // first blocking threshold required by #391.
        branches: 3,
        functions: 3,
        lines: 5,
        statements: 5,
        "**/{server-auth.ts,core.ts,transport.ts,route.ts}": {
          branches: 45,
          lines: 60,
          statements: 60,
        },
      },
    },
  },
});
