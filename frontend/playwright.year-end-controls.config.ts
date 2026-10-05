import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./year-end-control-tests",
  workers: 1,
  outputDir: "year-end-control-results",
  use: { baseURL: "http://127.0.0.1:5197", screenshot: "only-on-failure" },
  webServer: [
    {
      command: "java -jar target/ledgerdesk-0.1.0.jar --spring.profiles.active=demo --server.port=8097 --spring.datasource.url='jdbc:h2:mem:year-end-controls;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1'",
      cwd: "../backend", url: "http://127.0.0.1:8097/api/csrf", timeout: 60000,
    },
    {
      command: "npm run dev -- --port 5197", url: "http://127.0.0.1:5197",
      env: { LEDGERDESK_API_TARGET: "http://127.0.0.1:8097" },
    },
  ],
});
