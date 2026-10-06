import { test, expect } from "@playwright/test";

test("owner sets the business name and retries an uncertain save without duplicates", async ({ page, request }) => {
  const auth = { Authorization: `Basic ${Buffer.from("demo:demo-local-only").toString("base64")}` };
  async function login(username: string, password: string) {
    await page.getByLabel("Username", { exact: true }).fill(username);
    await page.getByLabel("Password", { exact: true }).fill(password);
    await page.getByRole("button", { name: "Open workspace →", exact: true }).click();
  }
  await page.goto("/");
  await login("demo", "demo-local-only");
  await expect(page.getByRole("heading", { name: "Start your books", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Set business details", exact: true }).click();
  await page.getByLabel("Business name", { exact: true }).fill("Harbor Design Studio");
  await page.route("**/api/state", route => route.fulfill({ status: 503, contentType: "application/json", body: JSON.stringify({ message: "Reload interrupted. Retry your save." }) }), { times: 1 });
  await page.getByRole("button", { name: "Save business details", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("Reload interrupted");
  await expect(page.getByLabel("Business name", { exact: true })).toHaveValue("Harbor Design Studio");
  await page.getByRole("button", { name: "Save business details", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("Business details saved");
  await expect(page.locator(".workspace")).toHaveText("Harbor Design Studio");
  const state = await (await request.get("/api/state", { headers: auth })).json();
  expect(state.businessVersion).toBe(1);
  expect(state.audit.filter((event: { action: string }) => event.action === "BUSINESS_UPDATED")).toHaveLength(1);
  expect(state.ledger).toHaveLength(0);
  await page.setViewportSize({ width: 390, height: 844 });
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: "business-results/business-mobile.png", fullPage: true });
  await page.getByRole("button", { name: "Lock workspace", exact: true }).click();
  await login("reviewer", "reviewer-local-only");
  await expect(page.getByRole("heading", { name: "Read-only reviewer workspace", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Business settings", exact: true })).toHaveCount(0);
  await expect(page.getByRole("heading", { name: "Start your books", exact: true })).toHaveCount(0);
  await expect(page.locator(".workspace")).toHaveText("Harbor Design Studio");
});
