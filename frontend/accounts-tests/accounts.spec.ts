import { test, expect } from "@playwright/test";
test("owner creates accounts, changes access and passwords, and last owner is protected", async ({
  page,
  request,
}) => {
  test.setTimeout(60000);
  const auth = (name: string, password: string) => ({
    Authorization: `Basic ${Buffer.from(`${name}:${password}`).toString("base64")}`,
  });
  async function identity(name: string, password: string) {
    return request.get("/api/access", { headers: auth(name, password) });
  }
  async function login(password: string) {
    await page.getByLabel("Username").fill("demo");
    await page.getByLabel("Password", { exact: true }).fill(password);
    await page.getByRole("button", { name: "Open workspace" }).click();
  }
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto("/");
  await login("demo-local-only");
  await page.getByRole("button", { name: "Accounts", exact: true }).click();
  await page.getByLabel("New account username").fill("new-reviewer");
  await page.getByLabel("New account password").fill("first-reviewer-password");
  await page
    .getByRole("button", { name: "Create account", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText("Account created");
  const row = page.locator("article").filter({
    has: page.getByRole("heading", { name: "new-reviewer", exact: true }),
  });
  await expect(row).toBeVisible();
  expect((await identity("new-reviewer", "first-reviewer-password")).ok()).toBe(
    true,
  );
  await page.screenshot({
    path: "account-results/account-editor.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect
    .poll(() =>
      page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    )
    .toBe(true);
  await page.screenshot({
    path: "account-results/mobile-accounts.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.getByLabel("Enabled for new-reviewer", { exact: true }).uncheck();
  await page
    .getByRole("button", { name: "Save access for new-reviewer", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText("Account access saved");
  await expect(row).toContainText("Disabled");
  expect(
    (await identity("new-reviewer", "first-reviewer-password")).status(),
  ).toBe(401);
  await page.getByLabel("Enabled for new-reviewer", { exact: true }).check();
  await page
    .getByRole("button", { name: "Save access for new-reviewer", exact: true })
    .click();
  await expect(row).toContainText("Enabled");
  await page
    .getByRole("button", {
      name: "Change password for new-reviewer",
      exact: true,
    })
    .click();
  await page
    .getByLabel("Replacement password for new-reviewer", { exact: true })
    .fill("replacement-reviewer-password");
  await page
    .getByRole("button", {
      name: "Confirm password change for new-reviewer",
      exact: true,
    })
    .click();
  await expect(page.getByRole("status")).toContainText("Password changed");
  expect(
    (await identity("new-reviewer", "first-reviewer-password")).status(),
  ).toBe(401);
  expect(
    (await identity("new-reviewer", "replacement-reviewer-password")).ok(),
  ).toBe(true);
  await page
    .getByLabel("Role for new-reviewer", { exact: true })
    .selectOption("OWNER");
  await page
    .getByRole("button", { name: "Save access for new-reviewer", exact: true })
    .click();
  await expect(row).toContainText("OWNER");
  expect(
    (
      await request.get("/api/accounts", {
        headers: auth("new-reviewer", "replacement-reviewer-password"),
      })
    ).ok(),
  ).toBe(true);
  await page
    .getByLabel("Role for new-reviewer", { exact: true })
    .selectOption("REVIEWER");
  await page
    .getByRole("button", { name: "Save access for new-reviewer", exact: true })
    .click();
  await expect(row).toContainText("REVIEWER");
  const accounts = await request
    .get("/api/accounts", { headers: auth("demo", "demo-local-only") })
    .then((r) => r.json());
  const ownerId = accounts.find(
    (a: { username: string }) => a.username === "demo",
  ).id;
  const csrf = await request.get("/api/csrf").then((r) => r.json());
  const blocked = await request.post(`/api/accounts/${ownerId}/access`, {
    headers: {
      ...auth("demo", "demo-local-only"),
      [csrf.headerName]: csrf.token,
    },
    data: { role: "REVIEWER", enabled: true },
  });
  expect(blocked.status()).toBe(400);
  expect((await blocked.json()).message).toContain(
    "at least one enabled owner",
  );
  await page
    .getByRole("button", { name: "Change password for demo", exact: true })
    .click();
  await page.getByLabel("Your current password").fill("demo-local-only");
  await page
    .getByLabel("Replacement password for demo", { exact: true })
    .fill("owner-replacement-pässword");
  await page
    .getByRole("button", {
      name: "Confirm password change for demo",
      exact: true,
    })
    .click();
  await expect(page.getByRole("status")).toContainText(
    "Sign in with your new password",
  );
  expect((await identity("demo", "demo-local-only")).status()).toBe(401);
  await login("owner-replacement-pässword");
  await page.getByRole("button", { name: "Accounts", exact: true }).click();
  await expect(row).toBeVisible();
  await page.screenshot({
    path: "account-results/account-password-changed.png",
    fullPage: true,
  });
});
