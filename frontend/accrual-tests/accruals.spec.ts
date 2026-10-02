import { test, expect } from "@playwright/test";

test("record an accrual, retry uncertain results and reverse in a later open period", async ({
  page,
  request,
}) => {
  const auth = {
    Authorization: `Basic ${Buffer.from("demo:demo-local-only").toString("base64")}`,
  };
  async function state() {
    const response = await request.get("/api/state", { headers: auth });
    expect(response.ok()).toBe(true);
    return response.json();
  }
  async function reports(start: string, end: string) {
    const response = await request.get(
      `/api/reports?startsOn=${start}&endsOn=${end}`,
      { headers: auth },
    );
    expect(response.ok()).toBe(true);
    return response.json();
  }
  async function post(path: string, body: object, key: string) {
    const csrf = await request.get("/api/csrf").then((r) => r.json());
    const response = await request.post(path, {
      headers: {
        ...auth,
        [csrf.headerName]: csrf.token,
        "Idempotency-Key": key,
      },
      data: body,
    });
    expect(response.ok(), await response.text()).toBe(true);
  }
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto("/");
  await page.getByLabel("Username").fill("demo");
  await page.getByLabel("Password", { exact: true }).fill("demo-local-only");
  await page.getByRole("button", { name: "Open workspace" }).click();
  await page.getByRole("button", { name: "Accruals", exact: true }).click();
  await expect(
    page.getByText("No accrued expenses recorded yet."),
  ).toBeVisible();
  await page.getByLabel("Accrual date", { exact: true }).fill("2026-10-31");
  await page
    .getByLabel("Accrual category", { exact: true })
    .selectOption("5200");
  const memo = "October professional fees; supplier bill pending";
  await page.getByLabel("Accrual memo", { exact: true }).fill(memo);
  await page.getByLabel("Accrual amount (USD)", { exact: true }).fill("0");
  await page
    .getByRole("button", { name: "Post accrued expense", exact: true })
    .click();
  await expect(page.getByRole("alert")).toHaveText(
    "Enter a positive amount with at most two decimal places.",
  );
  expect((await state()).accruals).toHaveLength(0);
  await page
    .getByLabel("Accrual amount (USD)", { exact: true })
    .fill("125.371");
  await page
    .getByRole("button", { name: "Post accrued expense", exact: true })
    .click();
  expect(
    await page
      .getByLabel("Accrual amount (USD)", { exact: true })
      .evaluate((input: HTMLInputElement) => input.validity.patternMismatch),
  ).toBe(true);
  expect((await state()).accruals).toHaveLength(0);
  await page.getByLabel("Accrual amount (USD)", { exact: true }).fill("125.37");
  await expect(page.getByLabel("Accrual entry preview")).toContainText(
    "Debit 5200 · Professional services: $125.37",
  );
  await expect(page.getByLabel("Accrual entry preview")).toContainText(
    "Credit 2100 · Accrued expenses: $125.37",
  );
  await page.screenshot({
    path: "accrual-results/accrual-editor.png",
    fullPage: true,
  });

  // Retry the same draft when posting succeeds but the refresh is interrupted.
  let failRefresh = true;
  await page.route("**/api/state", async (route) => {
    if (failRefresh) {
      failRefresh = false;
      await route.abort();
    } else await route.continue();
  });
  await page
    .getByRole("button", { name: "Post accrued expense", exact: true })
    .click();
  await expect(page.getByRole("alert")).toBeVisible();
  expect((await state()).accruals).toHaveLength(1);
  await expect(page.getByLabel("Accrual memo", { exact: true })).toHaveValue(
    memo,
  );
  await page
    .getByRole("button", { name: "Post accrued expense", exact: true })
    .click();
  await expect(page.getByRole("status")).toHaveText("Accrued expense posted.");
  await expect(page.getByLabel("Accrual memo", { exact: true })).toHaveValue(
    "",
  );
  await page.unroute("**/api/state");
  let data = await state();
  expect(data.accruals).toHaveLength(1);
  expect(data.ledger).toHaveLength(2);
  expect(data.bills).toHaveLength(0);
  const october = await reports("2026-10-01", "2026-10-31");
  expect(Number(october.profitLoss.expenses)).toBe(125.37);
  expect(Number(october.balanceSheet.totalAssets)).toBe(0);
  expect(Number(october.balanceSheet.totalLiabilities)).toBe(125.37);
  expect(Number(october.balanceSheet.difference)).toBe(0);
  expect(Number(october.payables.total)).toBe(0);
  await page.screenshot({
    path: "accrual-results/accrual-history.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await expect(
    page.getByRole("button", { name: "Post accrued expense", exact: true }),
  ).toBeVisible();
  await page.screenshot({
    path: "accrual-results/mobile-accruals.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 1280, height: 900 });

  await page.getByRole("button", { name: "Reports", exact: true }).click();
  await page.getByLabel("Report start").fill("2026-10-01");
  await page.getByLabel("Report end").fill("2026-10-31");
  await page.getByRole("button", { name: "Run reports", exact: true }).click();
  await expect(
    page.getByRole("row").filter({ hasText: "Net profit" }),
  ).toContainText("-$125.37");
  await page
    .getByRole("button", { name: "Balance sheet", exact: true })
    .click();
  await expect(
    page.getByRole("row").filter({ hasText: "Accrued expenses" }),
  ).toContainText("$125.37");
  await expect(
    page.getByRole("row").filter({ hasText: "Equation difference" }),
  ).toContainText("$0.00");
  await page.screenshot({
    path: "accrual-results/accrual-balance-sheet.png",
    fullPage: true,
  });
  await post(
    "/api/bank/reconciliations",
    {
      startsOn: "2026-10-01",
      endsOn: "2026-10-31",
      openingBalance: "0",
      closingBalance: "0",
    },
    "close-october",
  );
  await page.getByRole("button", { name: "Accruals", exact: true }).click();
  await page.getByLabel("Accrual date", { exact: true }).fill("2026-10-31");
  await page
    .getByLabel("Accrual memo", { exact: true })
    .fill("Another closed-period estimate");
  await page.getByLabel("Accrual amount (USD)", { exact: true }).fill("20");
  await page
    .getByRole("button", { name: "Post accrued expense", exact: true })
    .click();
  await expect(page.getByRole("alert")).toContainText("closed period");
  expect((await state()).accruals).toHaveLength(1);
  await page.getByLabel("Accrual memo", { exact: true }).fill("");
  await page.getByLabel("Accrual amount (USD)", { exact: true }).fill("");
  await page
    .getByRole("button", { name: `Reverse accrual ${memo}`, exact: true })
    .click();
  await page.getByLabel("Accrual reversal date").fill("2026-10-31");
  await page
    .getByLabel("Accrual reversal reason")
    .fill("Reverse estimate before entering supplier bill");
  await page
    .getByRole("button", { name: "Confirm accrual reversal", exact: true })
    .click();
  await expect(page.getByRole("alert")).toContainText("closed period");
  await page.getByLabel("Accrual reversal date").fill("2026-11-01");
  failRefresh = true;
  await page.route("**/api/state", async (route) => {
    if (failRefresh) {
      failRefresh = false;
      await route.abort();
    } else await route.continue();
  });
  await page
    .getByRole("button", { name: "Confirm accrual reversal", exact: true })
    .click();
  await expect(page.getByRole("alert")).toBeVisible();
  expect((await state()).ledger).toHaveLength(4);
  await expect(page.getByLabel("Accrual reversal reason")).toHaveValue(
    "Reverse estimate before entering supplier bill",
  );
  await page
    .getByRole("button", { name: "Confirm accrual reversal", exact: true })
    .click();
  await expect(page.getByRole("status")).toHaveText(
    "Accrual reversed. Original entry retained.",
  );
  await expect(
    page.getByText("Reversed 2026-11-01", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", {
      name: "Reverse an accrued expense",
      exact: true,
    }),
  ).toHaveCount(0);
  await page.unroute("**/api/state");
  data = await state();
  expect(data.accruals).toHaveLength(1);
  expect(data.ledger).toHaveLength(4);
  expect(
    data.audit.filter(
      (a: { action: string }) => a.action === "EXPENSE_ACCRUAL_REVERSED",
    ),
  ).toHaveLength(1);
  expect(await reports("2026-10-01", "2026-10-31")).toEqual(october);
  const november = await reports("2026-11-01", "2026-11-01");
  expect(Number(november.profitLoss.expenses)).toBe(-125.37);
  expect(Number(november.balanceSheet.totalLiabilities)).toBe(0);
  expect(Number(november.balanceSheet.difference)).toBe(0);
  await page.screenshot({
    path: "accrual-results/accrual-reversal.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "Reload workspace" }).click();
  await expect(
    page.getByText("Reversed 2026-11-01", { exact: true }),
  ).toBeVisible();
  expect((await state()).ledger).toEqual(data.ledger);
});
