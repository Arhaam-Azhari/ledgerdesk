import { test, expect } from "@playwright/test";
import { readFile } from "node:fs/promises";

test("run dated reports, export each view and preserve earlier balances after later payments", async ({
  page,
  request,
}) => {
  const auth = {
    Authorization: `Basic ${Buffer.from("demo:demo-local-only").toString("base64")}`,
  };
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
    return result.id as string;
  }
  const customer = await post(
    "/api/customers",
    { name: "Cedar Studio", email: "hello@cedar.example" },
    "customer",
  );
  const vendor = await post(
    "/api/vendors",
    { name: "Harbor Supply", email: "accounts@harbor.example" },
    "vendor",
  );
  const invoice = await post(
    "/api/invoices",
    {
      customerId: customer,
      description: "October design work",
      issuedOn: "2026-10-01",
      dueOn: "2026-10-15",
      amount: "1200",
    },
    "invoice",
  );
  await post(
    `/api/invoices/${invoice}/payments`,
    { paidOn: "2026-10-03", amount: "700" },
    "payment",
  );
  const bill = await post(
    "/api/bills",
    {
      vendorId: vendor,
      reference: "SUP-104",
      description: "Office supplies",
      issuedOn: "2026-10-01",
      dueOn: "2026-10-15",
      accountCode: "5000",
      amount: "600",
    },
    "bill",
  );
  await post(
    `/api/bills/${bill}/payments`,
    { paidOn: "2026-10-04", amount: "200" },
    "bill-payment",
  );
  await post(
    "/api/expenses",
    {
      vendorId: vendor,
      description: "Design software",
      spentOn: "2026-10-05",
      accountCode: "5100",
      amount: "50",
    },
    "expense",
  );
  await post(
    `/api/invoices/${invoice}/payments`,
    { paidOn: "2026-11-01", amount: "500" },
    "later-payment",
  );
  await post(
    `/api/bills/${bill}/payments`,
    { paidOn: "2026-11-01", amount: "400" },
    "later-bill-payment",
  );
  const formulaParty = await post(
    "/api/customers",
    { name: "=2+2", email: "formula@example.test" },
    "formula-customer",
  );
  await post(
    "/api/invoices",
    {
      customerId: formulaParty,
      description: "Export text fixture",
      issuedOn: "2026-11-01",
      dueOn: "2026-11-30",
      amount: "25",
    },
    "future-invoice",
  );
  const before = await request
    .get("/api/state", { headers: auth })
    .then((r) => r.json());
  await page.goto("/");
  await page.getByLabel("Username").fill("demo");
  await page.getByLabel("Password", { exact: true }).fill("demo-local-only");
  await page.getByRole("button", { name: "Open workspace" }).click();
  await page.getByRole("button", { name: "Reports", exact: true }).click();
  await page.getByLabel("Report start").fill("2026-10-01");
  await page.getByLabel("Report end").fill("2026-10-31");
  await page.getByRole("button", { name: "Run reports", exact: true }).click();
  const result = page
    .locator("section")
    .filter({
      has: page.getByRole("button", { name: "Export CSV", exact: true }),
    });
  await expect(result).toContainText("2026-10-01 to 2026-10-31");
  await expect(
    result.getByRole("row").filter({ hasText: "Net profit" }),
  ).toContainText("$550.00");
  await page.screenshot({
    path: "report-results/profit-loss.png",
    fullPage: true,
  });
  async function exported() {
    const download = page.waitForEvent("download");
    await page.getByRole("button", { name: "Export CSV", exact: true }).click();
    const file = await download;
    expect(file.suggestedFilename()).toContain("ledgerdesk-");
    return readFile((await file.path())!, "utf8");
  }
  expect(await exported()).toContain('"Net profit","Total","550.00"');
  await page
    .getByRole("button", { name: "Balance sheet", exact: true })
    .click();
  await expect(
    result.getByRole("row").filter({ hasText: "Total assets" }),
  ).toContainText("$950.00");
  await expect(
    result.getByRole("row").filter({ hasText: "Accumulated earnings" }),
  ).toContainText("$550.00");
  expect(await exported()).toContain('"Equation difference","0.00"');
  await page.screenshot({
    path: "report-results/balance-sheet.png",
    fullPage: true,
  });
  await page
    .getByRole("button", { name: "Trial balance report", exact: true })
    .click();
  await expect(
    result.getByRole("row").filter({ hasText: "Debit total" }),
  ).toContainText("$1,600.00");
  expect(await exported()).toContain('"Totals","1600.00","1600.00"');
  await page
    .getByRole("button", { name: "Receivables aging", exact: true })
    .click();
  await expect(result).toContainText("Cedar Studio");
  await expect(
    result.getByRole("row").filter({ hasText: "Total outstanding" }),
  ).toContainText("$500.00");
  expect(await exported()).toContain(
    '"Cedar Studio","2026-10-15","16","1–30 days","500.00"',
  );
  await page.screenshot({
    path: "report-results/receivables-aging.png",
    fullPage: true,
  });
  await page
    .getByRole("button", { name: "Payables aging", exact: true })
    .click();
  await expect(result).toContainText("SUP-104");
  expect(await exported()).toContain(
    '"SUP-104","Harbor Supply","2026-10-15","16","1–30 days","400.00"',
  );
  await page.getByLabel("Report end").fill("2026-11-01");
  await expect(
    page.getByRole("button", { name: "Export CSV", exact: true }),
  ).toHaveCount(0);
  await page.getByRole("button", { name: "Run reports", exact: true }).click();
  await page
    .getByRole("button", { name: "Receivables aging", exact: true })
    .click();
  await expect(result).not.toContainText("Cedar Studio");
  await expect(
    result.getByRole("row").filter({ hasText: "Total outstanding" }),
  ).toContainText("$25.00");
  expect(await exported()).toContain('"\'=2+2"');
  await page
    .getByRole("button", { name: "Reload workspace", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Export CSV", exact: true }),
  ).toHaveCount(0);
  await page.getByLabel("Report start").fill("2026-12-01");
  await page.getByRole("button", { name: "Run reports", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("valid report period");
  await page.getByLabel("Report start").fill("2026-10-01");
  await page.getByLabel("Report end").fill("2026-10-31");
  let failed = false;
  await page.route("**/api/reports?*", async (route) => {
    if (!failed) {
      failed = true;
      await route.fulfill({
        status: 503,
        contentType: "application/json",
        body: "{}",
      });
    } else await route.continue();
  });
  await page.getByRole("button", { name: "Run reports", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("could not be completed");
  await page.getByRole("button", { name: "Run reports", exact: true }).click();
  await expect(result).toContainText("2026-10-31");
  await page.unroute("**/api/reports?*");
  await page.setViewportSize({ width: 390, height: 844 });
  await page
    .getByRole("button", { name: "Receivables aging", exact: true })
    .click();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "report-results/mobile-reports.png",
    fullPage: true,
  });
  const after = await request
    .get("/api/state", { headers: auth })
    .then((r) => r.json());
  expect(after.ledger).toEqual(before.ledger);
  expect(after.trialBalance).toEqual(before.trialBalance);
  expect(after.audit).toEqual(before.audit);
});
