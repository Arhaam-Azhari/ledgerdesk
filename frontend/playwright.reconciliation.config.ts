import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './reconciliation-tests', workers: 1, outputDir: 'reconciliation-results',
  use: { baseURL: 'http://127.0.0.1:5174', screenshot: 'only-on-failure' },
  webServer: [
    { command: 'java -jar target/ledgerdesk-0.1.0.jar --spring.profiles.active=demo --server.port=8081 --spring.datasource.url=jdbc:h2:mem:reconciliation-browser', cwd: '../backend', url: 'http://127.0.0.1:8081/api/csrf', timeout: 60000 },
    { command: 'npm run dev -- --port 5174', url: 'http://127.0.0.1:5174', env: { LEDGERDESK_API_TARGET: 'http://127.0.0.1:8081' } },
  ],
});
