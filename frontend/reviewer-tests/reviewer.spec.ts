import { test, expect } from "@playwright/test";
test("reviewer reads reports, cannot post and can lock then switch to owner", async ({
  page,
  request,
}) => {
  test.setTimeout(60000);
  async function login(username: string, password: string) {
    await page.getByLabel("Username").fill(username);
    await page.getByLabel("Password", { exact: true }).fill(password);
    await page.getByRole("button", { name: "Open workspace" }).click();
  }
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto("/");
  await login("reviewer", "reviewer-local-only");
  await expect(
    page.getByRole("heading", { name: "Read-only reviewer workspace" }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Reports", exact: true }),
  ).toBeVisible();
  await expect(page.getByRole("navigation").getByRole("button")).toHaveText(["Period close", "Reports", "Cash activity", "General ledger", "Trial balance", "Activity"]);
  await expect(
    page.getByRole("button", { name: "Invoices", exact: true }),
  ).toHaveCount(0);
  await page.getByLabel("Report start").fill("2026-10-01");
  await page.getByLabel("Report end").fill("2026-10-31");
  await page.getByRole("button", { name: "Run reports", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Export CSV" }),
  ).toBeVisible();
  await page.screenshot({
    path: "reviewer-results/reviewer-reports.png",
    fullPage: true,
  });
  await page
    .getByRole("button", { name: "Cash activity", exact: true })
    .click();
  await page.getByLabel("Cash activity start").fill("2026-10-01");
  await page.getByLabel("Cash activity end").fill("2026-10-31");
  await page.getByRole("button", { name: "Run cash activity" }).click();
  await expect(
    page.getByText("No recorded cash movements in this period."),
  ).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect
    .poll(() =>
      page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    )
    .toBe(true);
  await page.screenshot({
    path: "reviewer-results/mobile-reviewer.png",
    fullPage: true,
  });
  const csrf = await request.get("/api/csrf").then((r) => r.json());
  const denied = await request.post("/api/vendors", {
    headers: {
      Authorization: `Basic ${Buffer.from("reviewer:reviewer-local-only").toString("base64")}`,
      [csrf.headerName]: csrf.token,
      "Idempotency-Key": "blocked",
    },
    data: { name: "Blocked supplier", email: "blocked@example.test" },
  });
  expect(denied.status()).toBe(403);
  await page.getByRole("button", { name: "Lock workspace" }).click();
  await login("demo", "demo-local-only");
  await expect(
    page.getByRole("heading", { name: "Read-only reviewer workspace" }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "Invoices", exact: true }),
  ).toBeVisible();
});
