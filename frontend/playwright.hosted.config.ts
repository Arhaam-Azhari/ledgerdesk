import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./hosted-tests",
  workers: 1,
  outputDir: "hosted-results",
  use: {
    baseURL: "https://localhost",
    // Only the disposable installation uses Caddy's private localhost certificate.
    ignoreHTTPSErrors: true,
    screenshot: "only-on-failure",
  },
});
