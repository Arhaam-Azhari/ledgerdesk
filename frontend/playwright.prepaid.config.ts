import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./prepaid-tests",
  workers: 1,
  outputDir: "prepaid-results",
  use: { baseURL: "http://127.0.0.1:5180", screenshot: "only-on-failure" },
  webServer: [
    {
      command:
        "java -jar target/ledgerdesk-0.1.0.jar --spring.profiles.active=demo --server.port=8087 --spring.datasource.url='jdbc:h2:mem:prepaid-browser;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1'",
      cwd: "../backend",
      url: "http://127.0.0.1:8087/api/csrf",
      timeout: 60000,
    },
    {
      command: "npm run dev -- --port 5180",
      url: "http://127.0.0.1:5180",
      env: { LEDGERDESK_API_TARGET: "http://127.0.0.1:8087" },
    },
  ],
});
