import { test, expect } from "@playwright/test";

test("receive an actual bill once, preserve closed history and retain its link through payment and voiding", async ({
  page,
  request,
}) => {
  test.setTimeout(60000);
  const auth = {
    Authorization: `Basic ${Buffer.from("demo:demo-local-only").toString("base64")}`,
  };
  async function state() {
    const response = await request.get("/api/state", { headers: auth });
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
    const result = await response.json();
    expect(response.ok(), JSON.stringify(result)).toBe(true);
    return result.id;
  }
  async function reports(start: string, end: string) {
    const response = await request.get(
      `/api/reports?startsOn=${start}&endsOn=${end}`,
      { headers: auth },
    );
    expect(response.ok()).toBe(true);
    return response.json();
  }
  const memo = "October professional fees; supplier bill pending";
  const accrual = await post(
    "/api/accruals",
    { postedOn: "2026-10-31", memo, accountCode: "5200", amount: "125.37" },
    "accrual",
  );
  const october = await reports("2026-10-01", "2026-10-31");
  await post(
    "/api/bank/reconciliations",
    {
      startsOn: "2026-10-01",
      endsOn: "2026-10-31",
      openingBalance: "0",
      closingBalance: "0",
    },
    "close",
  );
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto("/");
  await page.getByLabel("Username").fill("demo");
  await page.getByLabel("Password", { exact: true }).fill("demo-local-only");
  await page.getByRole("button", { name: "Open workspace" }).click();
  await page.getByRole("button", { name: "Accruals", exact: true }).click();
  await expect(
    page.getByRole("button", { name: `Receive bill for ${memo}`, exact: true }),
  ).toBeDisabled();
  await expect(
    page.getByText("Add a vendor in Vendors before receiving its bill."),
  ).toBeVisible();
  const vendor = await post(
    "/api/vendors",
    { name: "Professional services supplier", email: "accounts@example.test" },
    "vendor",
  );
  await page.getByRole("button", { name: "Reload workspace" }).click();
  await expect(
    page.getByRole("button", { name: `Receive bill for ${memo}`, exact: true }),
  ).toBeEnabled();
  await page
    .getByRole("button", { name: `Receive bill for ${memo}`, exact: true })
    .click();
  await page
    .getByLabel("Supplier for this bill", { exact: true })
    .selectOption(vendor);
  await page
    .getByLabel("Supplier bill reference", { exact: true })
    .fill("FEES-100");
  await page
    .getByLabel("Supplier bill description", { exact: true })
    .fill("Actual October professional fees");
  await page
    .getByLabel("Supplier bill date", { exact: true })
    .fill("2026-10-31");
  await page
    .getByLabel("Supplier bill due date", { exact: true })
    .fill("2026-12-01");
  await page.getByLabel("Actual bill amount (USD)", { exact: true }).fill("0");
  await page
    .getByRole("button", {
      name: "Post bill and reverse estimate",
      exact: true,
    })
    .click();
  await expect(page.getByRole("alert")).toHaveText(
    "Enter a positive bill amount with at most two decimal places.",
  );
  await page
    .getByLabel("Actual bill amount (USD)", { exact: true })
    .fill("140.001");
  await page
    .getByRole("button", {
      name: "Post bill and reverse estimate",
      exact: true,
    })
    .click();
  expect(
    await page
      .getByLabel("Actual bill amount (USD)", { exact: true })
      .evaluate((input: HTMLInputElement) => input.validity.patternMismatch),
  ).toBe(true);
  await page
    .getByLabel("Actual bill amount (USD)", { exact: true })
    .fill("140");
  await page
    .getByRole("button", {
      name: "Post bill and reverse estimate",
      exact: true,
    })
    .click();
  await expect(page.getByRole("alert")).toContainText("closed period");
  let data = await state();
  expect(data.bills).toHaveLength(0);
  expect(data.ledger).toHaveLength(2);
  expect(data.accruals[0].reversal_id).toBeNull();
  await page
    .getByLabel("Supplier bill date", { exact: true })
    .fill("2026-11-01");
  await expect(page.getByLabel("Bill handoff preview")).toContainText(
    "Expense change on bill date: $14.63",
  );
  // Reload clears the server notice while preserving the open handoff draft.
  await page.getByRole("button", { name: "Reload workspace" }).click();
  await expect(
    page.getByLabel("Supplier bill reference", { exact: true }),
  ).toHaveValue("FEES-100");
  await expect(page.getByRole("status")).toHaveText("Workspace reloaded.");
  await expect(page.getByRole("button", { name: "Post bill and reverse estimate", exact: true })).toBeEnabled();
  await page.screenshot({
    path: "handoff-results/handoff-editor.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await expect(
    page.getByRole("button", {
      name: "Post bill and reverse estimate",
      exact: true,
    }),
  ).toBeVisible();
  await page.screenshot({
    path: "handoff-results/mobile-handoff.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 1280, height: 900 });
  let failRefresh = true;
  await page.route("**/api/state", async (route) => {
    if (failRefresh) {
      failRefresh = false;
      await route.abort();
    } else await route.continue();
  });
  await page
    .getByRole("button", {
      name: "Post bill and reverse estimate",
      exact: true,
    })
    .click();
  await expect(page.getByRole("alert")).toBeVisible();
  data = await state();
  expect(data.bills).toHaveLength(1);
  expect(data.ledger).toHaveLength(6);
  await expect(
    page.getByLabel("Supplier bill reference", { exact: true }),
  ).toHaveValue("FEES-100");
  await page
    .getByRole("button", {
      name: "Post bill and reverse estimate",
      exact: true,
    })
    .click();
  await expect(page.getByRole("status")).toHaveText(
    "Supplier bill posted. Estimate reversed and linked.",
  );
  await expect(
    page.getByRole("heading", {
      name: "Receive the supplier bill",
      exact: true,
    }),
  ).toHaveCount(0);
  await page.unroute("**/api/state");
  data = await state();
  expect(data.bills).toHaveLength(1);
  expect(data.ledger).toHaveLength(6);
  expect(data.accruals[0].bill_reference).toBe("FEES-100");
  expect(data.accruals[0].bill_id).toBe(data.bills[0].id);
  expect(
    data.audit.filter(
      (a: { action: string }) => a.action === "ACCRUAL_BILL_POSTED",
    ),
  ).toHaveLength(1);
  await expect(
    page.getByText("Linked bill FEES-100", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: `Receive bill for ${memo}`, exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByText("Reversed 2026-11-01", { exact: true }),
  ).toBeVisible();
  await page.screenshot({
    path: "handoff-results/linked-bill-history.png",
    fullPage: true,
  });
  expect(await reports("2026-10-01", "2026-10-31")).toEqual(october);
  const november = await reports("2026-11-01", "2026-11-01");
  expect(Number(november.profitLoss.expenses)).toBe(14.63);
  expect(Number(november.balanceSheet.totalLiabilities)).toBe(140);
  expect(Number(november.balanceSheet.difference)).toBe(0);
  expect(Number(november.payables.total)).toBe(140);
  expect(Number(november.balanceSheet.totalAssets)).toBe(0);
  await page
    .getByRole("button", { name: "View bills and payments", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Bills", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("row").filter({ hasText: "FEES-100" }),
  ).toContainText("$140.00");
  await page.getByRole("button", { name: "Reports", exact: true }).click();
  await page.getByLabel("Report start").fill("2026-11-01");
  await page.getByLabel("Report end").fill("2026-11-01");
  await page.getByRole("button", { name: "Run reports", exact: true }).click();
  await expect(
    page
      .getByRole("row")
      .filter({
        has: page.getByRole("cell", { name: "Expenses", exact: true }),
      }),
  ).toContainText("$14.63");
  await page.screenshot({
    path: "handoff-results/handoff-profit-report.png",
    fullPage: true,
  });
  const billId = data.bills[0].id;
  await post(
    `/api/bills/${billId}/payments`,
    { paidOn: "2026-11-02", amount: "40" },
    "payment",
  );
  const paid = await reports("2026-10-01", "2026-11-02");
  expect(Number(paid.profitLoss.expenses)).toBe(140);
  expect(Number(paid.payables.total)).toBe(100);
  expect(Number(paid.balanceSheet.totalAssets)).toBe(-40);
  const second = await post(
    "/api/accruals",
    {
      postedOn: "2026-11-01",
      memo: "Separate software estimate",
      accountCode: "5100",
      amount: "20",
    },
    "second",
  );
  const other = await post(
    `/api/accruals/${second}/bill`,
    {
      vendorId: vendor,
      reference: "OTHER",
      description: "Other bill",
      issuedOn: "2026-11-02",
      dueOn: "2026-11-02",
      amount: "25",
    },
    "other",
  );
  await post(`/api/bills/${other}/void`, { date: "2026-11-03" }, "void");
  await page.getByRole("button", { name: "Accruals", exact: true }).click();
  await page.getByRole("button", { name: "Reload workspace" }).click();
  await expect(
    page.getByText("Linked bill OTHER", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText(
      "The bill was voided; the estimate reversal remains in history. Review the obligation before recording a replacement.",
    ),
  ).toBeVisible();
  data = await state();
  expect(
    data.accruals.find((a: { id: string }) => a.id === accrual).bill_id,
  ).toBe(billId);
  expect(
    data.accruals.find((a: { id: string }) => a.id === second).bill_status,
  ).toBe("VOID");
  expect(await reports("2026-10-01", "2026-10-31")).toEqual(october);
});
