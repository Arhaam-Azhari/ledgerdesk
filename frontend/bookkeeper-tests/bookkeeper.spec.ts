import { test, expect } from "@playwright/test";

test("bookkeeper posts routine records and cannot use owner controls", async ({ page, request }) => {
  test.setTimeout(60000);
  const auth = (name: string, password: string) => ({
    Authorization: `Basic ${Buffer.from(`${name}:${password}`).toString("base64")}`,
  });
  const owner = auth("demo", "demo-local-only");
  const bookkeeper = auth("studio-books", "bookkeeper-local-only");
  const token = await (await request.get("/api/csrf")).json();
  const headers = { ...owner, [token.headerName]: token.token };
  expect((await request.post("/api/accounts", {
    headers, data: { username: "studio-books", password: "bookkeeper-local-only", role: "BOOKKEEPER" },
  })).ok()).toBe(true);
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto("/");
  await page.getByLabel("Username").fill("studio-books");
  await page.getByLabel("Password", { exact: true }).fill("bookkeeper-local-only");
  await page.getByRole("button", { name: "Open workspace" }).click();
  await expect(page.getByRole("heading", { name: "Bookkeeper workspace" })).toBeVisible();
  for (const name of ["Accounts", "Owner transfers", "Opening bank balance"])
    await expect(page.getByRole("button", { name, exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "Vendors", exact: true }).click();
  await page.getByLabel("Name", { exact: true }).fill("Harbor Supplies");
  await page.getByLabel("Email", { exact: true }).fill("accounts@example.test");
  await page.getByRole("button", { name: "Add vendor", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("Vendor added");
  await expect(page.getByRole("cell", { name: "Harbor Supplies", exact: true })).toBeVisible();
  await page.screenshot({ path: "bookkeeper-results/bookkeeper-vendor.png", fullPage: true });
  await page.getByRole("button", { name: "Period close", exact: true }).click();
  await expect(page.getByRole("button", { name: "Close accounting period", exact: true })).toHaveCount(0);
  await expect(page.getByLabel("Review note", { exact: true })).toHaveCount(0);
  await page.setViewportSize({ width: 390, height: 844 });
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: "bookkeeper-results/bookkeeper-mobile.png", fullPage: true });
  const csrf = await (await request.get("/api/csrf")).json();
  for (const path of ["/api/accounts", "/api/equity", "/api/opening-bank-balance", "/api/accounting-periods", "/api/bank/reconciliations/example/reopen"])
    expect((await request.post(path, { headers: { ...bookkeeper, [csrf.headerName]: csrf.token }, data: {} })).status()).toBe(403);
  expect((await request.get("/api/accounts", { headers: bookkeeper })).status()).toBe(403);
});
