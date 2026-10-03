import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./period-tests",
  workers: 1,
  outputDir: "period-results",
  use: { baseURL: "http://127.0.0.1:5187", screenshot: "only-on-failure" },
  webServer: [
    {
      command:
        "java -jar target/ledgerdesk-0.1.0.jar --spring.profiles.active=demo --server.port=8095 --app.reviewer.username=period-reviewer --app.reviewer.password=period-reviewer-password --spring.datasource.url='jdbc:h2:mem:period-browser;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1'",
      cwd: "../backend",
      url: "http://127.0.0.1:8095/api/csrf",
      timeout: 60000,
    },
    {
      command: "npm run dev -- --port 5187",
      url: "http://127.0.0.1:5187",
      env: { LEDGERDESK_API_TARGET: "http://127.0.0.1:8095" },
    },
  ],
});
