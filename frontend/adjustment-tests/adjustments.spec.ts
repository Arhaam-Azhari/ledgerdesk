import { test, expect } from "@playwright/test";

test("post a balanced split, retry uncertain results and retain a dated reversal", async ({
  page,
  request,
}) => {
  const auth = {
    Authorization: `Basic ${Buffer.from("demo:demo-local-only").toString("base64")}`,
  };
  async function state() {
    const r = await request.get("/api/state", { headers: auth });
    expect(r.ok()).toBe(true);
    return r.json();
  }
  async function post(path: string, body: object, key: string) {
    const csrf = await request.get("/api/csrf").then((r) => r.json());
    const r = await request.post(path, {
      headers: {
        ...auth,
        [csrf.headerName]: csrf.token,
        "Idempotency-Key": key,
      },
      data: body,
    });
    const result = await r.json();
    expect(r.ok(), JSON.stringify(result)).toBe(true);
    return result.id;
  }
  const vendor = await post(
    "/api/vendors",
    { name: "Setup supplier", email: "accounts@example.test" },
    "vendor",
  );
  await post(
    "/api/expenses",
    {
      vendorId: vendor,
      description: "Setup purchase",
      spentOn: "2026-10-01",
      accountCode: "5000",
      amount: "150",
    },
    "expense",
  );
  await page.goto("/");
  await page.getByLabel("Username").fill("demo");
  await page.getByLabel("Password", { exact: true }).fill("demo-local-only");
  await page.getByRole("button", { name: "Open workspace" }).click();
  await page.getByRole("button", { name: "Adjustments", exact: true }).click();
  await expect(page.getByText("No adjustments recorded yet.")).toBeVisible();
  await page.getByLabel("Adjustment date", { exact: true }).fill("2026-10-02");
  await page
    .getByLabel("Adjustment memo", { exact: true })
    .fill("Split setup purchase into software and services");
  await page.getByLabel("Credit line 1").fill("150");
  await page.getByLabel("Debit line 2").fill("100.01");
  await page.getByRole("button", { name: "Add line", exact: true }).click();
  await page.getByLabel("Debit line 3").fill("40");
  await page
    .getByRole("button", { name: "Post adjustment", exact: true })
    .click();
  await expect(page.getByRole("alert")).toHaveText(
    "Debits and credits must balance exactly before posting.",
  );
  expect((await state()).adjustments).toHaveLength(0);
  await page.getByLabel("Debit line 3").fill("49.99");
  await page.getByLabel("Category line 3").selectOption("5100");
  await page
    .getByRole("button", { name: "Post adjustment", exact: true })
    .click();
  await expect(page.getByRole("alert")).toHaveText(
    "Choose each category once.",
  );
  await page.getByLabel("Category line 3").selectOption("5200");
  await expect(page.getByLabel("Adjustment totals")).toContainText(
    "Difference: $0.00",
  );
  await page.screenshot({
    path: "adjustment-results/adjustment-editor.png",
    fullPage: true,
  });

  // An uncertain refresh must not turn a successful command into a second journal.
  let failRefresh = true;
  await page.route("**/api/state", async (route) => {
    if (failRefresh) {
      failRefresh = false;
      await route.abort();
    } else await route.continue();
  });
  await page
    .getByRole("button", { name: "Post adjustment", exact: true })
    .click();
  await expect(page.getByRole("alert")).toBeVisible();
  expect((await state()).adjustments).toHaveLength(1);
  await expect(page.getByLabel("Adjustment memo", { exact: true })).toHaveValue(
    "Split setup purchase into software and services",
  );
  await page
    .getByRole("button", { name: "Post adjustment", exact: true })
    .click();
  await expect(page.getByRole("status")).toHaveText("Adjustment posted.");
  await expect(page.getByLabel("Adjustment memo", { exact: true })).toHaveValue(
    "",
  );
  await page.unroute("**/api/state");
  let data = await state();
  expect(data.adjustments).toHaveLength(1);
  expect(data.adjustmentLines).toHaveLength(3);
  expect(data.ledger).toHaveLength(5);
  expect(data.expenses[0].account_code).toBe("5000");
  await expect(
    page.getByRole("row").filter({ hasText: "Software subscriptions" }),
  ).toContainText("$100.01");
  await page.screenshot({
    path: "adjustment-results/adjustment-history.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await expect(
    page.getByRole("button", { name: "Post adjustment", exact: true }),
  ).toBeVisible();
  await page.screenshot({
    path: "adjustment-results/mobile-adjustments.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.getByRole("button", { name: "Reports", exact: true }).click();
  await page.getByLabel("Report start").fill("2026-10-01");
  await page.getByLabel("Report end").fill("2026-10-31");
  await page.getByRole("button", { name: "Run reports", exact: true }).click();
  await expect(
    page.getByRole("row").filter({ hasText: "Net profit" }),
  ).toContainText("-$150.00");
  await expect(
    page.getByRole("row").filter({ hasText: "Software subscriptions" }),
  ).toContainText("$100.01");
  await page.getByRole("button", { name: "Adjustments", exact: true }).click();
  await page
    .getByRole("button", {
      name: "Reverse adjustment Split setup purchase into software and services",
      exact: true,
    })
    .click();
  await page.getByLabel("Adjustment reversal date").fill("2026-11-01");
  await page
    .getByLabel("Adjustment reversal reason")
    .fill("Allocation entered against the wrong purchase");
  failRefresh = true;
  await page.route("**/api/state", async (route) => {
    if (failRefresh) {
      failRefresh = false;
      await route.abort();
    } else await route.continue();
  });
  await page
    .getByRole("button", { name: "Reverse adjustment", exact: true })
    .click();
  await expect(page.getByRole("alert")).toBeVisible();
  expect((await state()).adjustmentLines).toHaveLength(6);
  await page
    .getByRole("button", { name: "Reverse adjustment", exact: true })
    .click();
  await expect(page.getByRole("status")).toHaveText(
    "Adjustment reversed. Original lines retained.",
  );
  await expect(
    page.getByText("Reversed 2026-11-01", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Original lines", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Reversal lines", exact: true }),
  ).toBeVisible();
  await page.unroute("**/api/state");
  data = await state();
  expect(data.adjustments).toHaveLength(1);
  expect(data.adjustmentLines).toHaveLength(6);
  expect(data.ledger).toHaveLength(8);
  expect(
    data.audit.filter(
      (a: { action: string }) => a.action === "JOURNAL_ADJUSTMENT_REVERSED",
    ),
  ).toHaveLength(1);
  await page.screenshot({
    path: "adjustment-results/adjustment-reversal.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "Reports", exact: true }).click();
  await page.getByLabel("Report start").fill("2026-10-01");
  await page.getByLabel("Report end").fill("2026-10-31");
  await page.getByRole("button", { name: "Run reports", exact: true }).click();
  await expect(
    page.getByRole("row").filter({ hasText: "Software subscriptions" }),
  ).toContainText("$100.01");
  await page.getByLabel("Report end").fill("2026-11-01");
  await page.getByRole("button", { name: "Run reports", exact: true }).click();
  await expect(
    page.getByRole("row").filter({ hasText: "Software subscriptions" }),
  ).toContainText("$0.00");
  await expect(
    page.getByRole("row").filter({ hasText: "Office supplies" }),
  ).toContainText("$150.00");
  await expect(
    page.getByRole("row").filter({ hasText: "Net profit" }),
  ).toContainText("-$150.00");
  await page.getByRole("button", { name: "Adjustments", exact: true }).click();
  await page.getByRole("button", { name: "Reload workspace" }).click();
  await expect(
    page.getByText("Reversed 2026-11-01", { exact: true }),
  ).toBeVisible();
  expect((await state()).ledger).toEqual(data.ledger);
});
