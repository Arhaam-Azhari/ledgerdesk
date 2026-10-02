import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./accrual-tests",
  workers: 1,
  outputDir: "accrual-results",
  use: { baseURL: "http://127.0.0.1:5178", screenshot: "only-on-failure" },
  webServer: [
    {
      command:
        "java -jar target/ledgerdesk-0.1.0.jar --spring.profiles.active=demo --server.port=8085 --spring.datasource.url='jdbc:h2:mem:accruals-browser;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1'",
      cwd: "../backend",
      url: "http://127.0.0.1:8085/api/csrf",
      timeout: 60000,
    },
    {
      command: "npm run dev -- --port 5178",
      url: "http://127.0.0.1:5178",
      env: { LEDGERDESK_API_TARGET: "http://127.0.0.1:8085" },
    },
  ],
});
