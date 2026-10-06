import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./opening-books-tests",
  workers: 1,
  outputDir: "opening-books-results",
  use: { baseURL: "http://127.0.0.1:5198", screenshot: "only-on-failure" },
  webServer: [
    {
      command: "java -jar target/ledgerdesk-0.1.0.jar --spring.profiles.active=demo --server.port=8098 --app.accounts.persistent=true --app.reviewer.username=reviewer --app.reviewer.password=reviewer-local-only --spring.datasource.url='jdbc:h2:mem:opening-books-browser;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1'",
      cwd: "../backend", url: "http://127.0.0.1:8098/api/csrf", timeout: 60000,
    },
    {
      command: "npm run dev -- --port 5198", url: "http://127.0.0.1:5198",
      env: { LEDGERDESK_API_TARGET: "http://127.0.0.1:8098" },
    },
  ],
});
