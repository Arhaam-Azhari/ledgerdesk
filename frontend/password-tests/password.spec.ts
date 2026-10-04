import { test, expect } from "@playwright/test";

test("stored roles change their own password and recover from an uncertain response", async ({ page, request }) => {
  test.setTimeout(90000);
  const auth = (name: string, password: string) => ({ Authorization: `Basic ${Buffer.from(`${name}:${password}`).toString("base64")}` });
  const csrf = await (await request.get("/api/csrf")).json();
  expect((await request.post("/api/accounts", {
    headers: { ...auth("demo", "demo-local-only"), [csrf.headerName]: csrf.token },
    data: { username: "studio-books", password: "bookkeeper-local-only", role: "BOOKKEEPER" },
  })).ok()).toBe(true);
  async function login(name: string, password: string) {
    await page.getByLabel("Username").fill(name);
    await page.getByLabel("Password", { exact: true }).fill(password);
    await page.getByRole("button", { name: "Open workspace" }).click();
    await expect(page.getByRole("button", { name: "Change my password", exact: true })).toBeVisible();
  }
  async function fill(current: string, next: string, confirmation = next) {
    await page.getByLabel("Current login password", { exact: true }).fill(current);
    await page.getByLabel("New login password", { exact: true }).fill(next);
    await page.getByLabel("Confirm new login password", { exact: true }).fill(confirmation);
  }
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto("/");
  for (const [name, oldPassword, role] of [
    ["studio-books", "bookkeeper-local-only", "BOOKKEEPER"],
    ["reviewer", "reviewer-local-only", "REVIEWER"],
    ["demo", "demo-local-only", "OWNER"],
  ]) {
    const next = `replacement-${name}-password`;
    await login(name, oldPassword);
    await page.getByRole("button", { name: "Change my password", exact: true }).click();
    if (role === "BOOKKEEPER") {
      await fill(oldPassword, next, "different-confirmation");
      await page.getByRole("button", { name: "Save my password", exact: true }).click();
      await expect(page.getByRole("alert")).toContainText("do not match");
      expect((await request.get("/api/access", { headers: auth(name, oldPassword) })).ok()).toBe(true);
      await fill("wrong-current-password", next);
      await page.getByRole("button", { name: "Save my password", exact: true }).click();
      await expect(page.getByRole("alert")).toContainText("Enter your current password");
      await expect(page.getByLabel("Current login password", { exact: true })).toHaveValue("");
      await page.getByRole("button", { name: "Cancel password change", exact: true }).click();
      await expect(page.getByRole("heading", { name: "Change your password", exact: true })).toHaveCount(0);
      await page.getByRole("button", { name: "Change my password", exact: true }).click();
      await fill(oldPassword, next);
      await page.screenshot({ path: "password-results/password-editor.png", fullPage: true });
      await page.setViewportSize({ width: 390, height: 844 });
      await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      await page.screenshot({ path: "password-results/password-mobile.png", fullPage: true });
      await page.setViewportSize({ width: 1280, height: 900 });
      // Save on the server, then lose the response to exercise the recovery instructions.
      await page.route("**/api/me/password", async (route) => {
        const response = await route.fetch();
        expect(response.ok()).toBe(true);
        await route.abort("failed");
      }, { times: 1 });
      await page.getByRole("button", { name: "Save my password", exact: true }).click();
      await expect(page.getByRole("alert")).toBeVisible();
      await expect(page.getByLabel("New login password", { exact: true })).toHaveValue("");
      await page.getByRole("button", { name: "Lock workspace", exact: true }).click();
    } else {
      await fill(oldPassword, next);
      await page.getByRole("button", { name: "Save my password", exact: true }).click();
      await expect(page.getByRole("button", { name: "Open workspace" })).toBeVisible();
      await expect(page.getByRole("status")).toContainText("Password changed");
    }
    expect((await request.get("/api/access", { headers: auth(name, oldPassword) })).status()).toBe(401);
    const identity = await request.get("/api/access", { headers: auth(name, next) });
    expect(identity.ok()).toBe(true);
    expect((await identity.json()).role).toBe(role);
    await login(name, next);
    if (role !== "OWNER") {
      await expect(page.getByRole("button", { name: "Accounts", exact: true })).toHaveCount(0);
      expect((await request.get("/api/accounts", { headers: auth(name, next) })).status()).toBe(403);
    }
    await page.getByRole("button", { name: "Lock workspace", exact: true }).click();
  }
});
