import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./opening-tests",
  workers: 1,
  outputDir: "opening-results",
  use: { baseURL: "http://127.0.0.1:5186", screenshot: "only-on-failure" },
  webServer: [
    {
      command:
        "java -jar target/ledgerdesk-0.1.0.jar --spring.profiles.active=demo --server.port=8094 --app.reviewer.username=opening-reviewer --app.reviewer.password=opening-reviewer-password --spring.datasource.url='jdbc:h2:mem:opening-browser;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1'",
      cwd: "../backend",
      url: "http://127.0.0.1:8094/api/csrf",
      timeout: 60000,
    },
    {
      command: "npm run dev -- --port 5186",
      url: "http://127.0.0.1:5186",
      env: { LEDGERDESK_API_TARGET: "http://127.0.0.1:8094" },
    },
  ],
});
