import { test, expect } from "@playwright/test";

test("cookie sign-in survives reload, signs out and expires after an account change", async ({ page, context }) => {
  test.setTimeout(90000);
  const basicRequests: string[] = [];
  page.on("request", request => {
    if (request.url().includes("/api/") && request.headers().authorization) basicRequests.push(request.url());
  });
  async function login(password: string, name = "demo") {
    await page.getByLabel("Username", { exact: true }).fill(name);
    await page.getByLabel("Password", { exact: true }).fill(password);
    await page.getByRole("button", { name: "Open workspace" }).click();
  }
  async function post(path: string, data: object) {
    const csrf = await (await page.request.get("/api/csrf")).json();
    return page.request.post(path, { headers: { [csrf.headerName]: csrf.token, "Idempotency-Key": crypto.randomUUID() }, data });
  }
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Open workspace" })).toBeEnabled();
  await login("wrong-password");
  await expect(page.getByRole("alert")).toContainText("Check your username and password");
  await expect(page.getByLabel("Password", { exact: true })).toHaveValue("");
  await login("demo-local-only");
  await expect(page.getByRole("button", { name: "Sign out", exact: true })).toBeVisible();
  const cookie = (await context.cookies()).find(cookie => cookie.name === "JSESSIONID");
  expect(cookie).toMatchObject({ httpOnly: true, sameSite: "Strict" });
  // This fixture uses loopback HTTP; the deployment profile requires Secure cookies.
  expect((await post("/api/vendors", { name: "Session supplier", email: "supplier@example.test" })).ok()).toBe(true);
  await page.reload();
  await expect(page.getByRole("button", { name: "Sign out", exact: true })).toBeVisible();
  await page.screenshot({ path: "session-results/session-workspace.png", fullPage: true });

  // The server signs out, but the browser loses the confirmation and must allow a retry.
  await page.route("**/api/session/logout", async route => {
    expect((await route.fetch()).ok()).toBe(true);
    await route.fulfill({ status: 503, contentType: "application/json", body: JSON.stringify({ message: "Sign-out confirmation lost. Try again." }) });
  }, { times: 1 });
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("confirmation lost");
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(page.getByRole("button", { name: "Open workspace" })).toBeVisible();
  expect((await page.request.get("/api/access")).status()).toBe(401);

  await login("demo-local-only");
  await expect(page.getByRole("button", { name: "Change my password", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Change my password", exact: true }).click();
  await page.getByLabel("Current login password", { exact: true }).fill("demo-local-only");
  await page.getByLabel("New login password", { exact: true }).fill("session-replacement-password");
  await page.getByLabel("Confirm new login password", { exact: true }).fill("session-replacement-password");
  await page.getByRole("button", { name: "Save my password", exact: true }).click();
  await expect(page.getByRole("button", { name: "Open workspace" })).toBeVisible();
  // Reload with the stale cookie: public login setup must remain available.
  await page.reload();
  await expect(page.getByRole("button", { name: "Open workspace" })).toBeEnabled();
  await login("demo-local-only");
  await expect(page.getByRole("alert")).toContainText("Check your username and password");
  await login("session-replacement-password");
  await expect(page.getByRole("button", { name: "Sign out", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Sign out", exact: true }).click();

  await login("reviewer-local-only", "reviewer");
  await expect(page.getByRole("button", { name: "Sign out", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Accounts", exact: true })).toHaveCount(0);
  expect((await post("/api/vendors", { name: "Blocked supplier", email: "blocked@example.test" })).status()).toBe(403);
  await page.setViewportSize({ width: 390, height: 844 });
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: "session-results/session-reviewer-mobile.png", fullPage: true });
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(page.getByRole("button", { name: "Open workspace" })).toBeVisible();
  expect(basicRequests).toEqual([]);
});
