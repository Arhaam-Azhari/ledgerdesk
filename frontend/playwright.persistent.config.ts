import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./persistent-tests",
  workers: 1,
  outputDir: "persistent-results",
  use: { baseURL: "http://127.0.0.1:5184", screenshot: "only-on-failure" },
  webServer: {
    command: "npm run dev -- --port 5184",
    url: "http://127.0.0.1:5184",
    env: { LEDGERDESK_API_TARGET: "http://127.0.0.1:8091" },
  },
});
