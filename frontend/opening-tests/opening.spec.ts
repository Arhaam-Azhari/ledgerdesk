import { test, expect } from "@playwright/test";

test("carry a cleared opening through reports and the first two statements", async ({
  page,
  request,
}) => {
  const headers = {
    Authorization: `Basic ${Buffer.from("demo:demo-local-only").toString("base64")}`,
  };
  async function state() {
    const response = await request.get("/api/state", { headers });
    expect(response.ok()).toBe(true);
    return response.json();
  }
  await page.goto("/");
  await page.getByLabel("Username").fill("demo");
  await page.getByLabel("Password", { exact: true }).fill("demo-local-only");
  await page.getByRole("button", { name: "Open workspace" }).click();
  await page
    .getByRole("button", { name: "Opening bank balance", exact: true })
    .click();
  await page.getByLabel("Prior books end date").fill("2026-09-30");
  await page
    .getByLabel("Opening balance note")
    .fill("Cleared balance from September books");
  for (const invalid of ["-1.00", "1.001", "1e3", "1000000000000"]) {
    await page.getByLabel("Cleared bank balance (USD)").fill(invalid);
    await expect(
      page.getByRole("button", { name: "Record opening balance" }),
    ).toBeDisabled();
  }
  await page.getByLabel("Cleared bank balance (USD)").fill("0");
  await expect(
    page.getByRole("button", { name: "Record opening balance" }),
  ).toBeEnabled();
  await page.getByLabel("Cleared bank balance (USD)").fill("1000.25");
  await page.screenshot({
    path: "opening-results/opening-setup.png",
    fullPage: true,
  });
  page.once("dialog", (dialog) => dialog.dismiss());
  await page.getByRole("button", { name: "Record opening balance" }).click();
  expect((await state()).openingBankBalances).toHaveLength(0);

  // Retain the request after a successful post whose workspace refresh fails.
  let failRefresh = true;
  await page.route("**/api/state", async (route) => {
    if (failRefresh) {
      failRefresh = false;
      await route.abort();
    } else await route.continue();
  });
  page.on("dialog", (dialog) => dialog.accept());
  await page.getByRole("button", { name: "Record opening balance" }).click();
  await expect(page.getByRole("alert")).toBeVisible();
  expect((await state()).openingBankBalances).toHaveLength(1);
  await expect(page.getByLabel("Cleared bank balance (USD)")).toHaveValue(
    "1000.25",
  );
  await page.getByRole("button", { name: "Record opening balance" }).click();
  await expect(page.getByRole("status")).toHaveText(
    "Opening bank balance recorded.",
  );
  await page.unroute("**/api/state");
  const saved = await state();
  expect(saved.openingBankBalances).toHaveLength(1);
  expect(saved.ledger).toHaveLength(2);
  expect(
    saved.audit.filter(
      (a: { action: string }) => a.action === "OPENING_BANK_BALANCE_RECORDED",
    ),
  ).toHaveLength(1);
  await expect(
    page.getByRole("heading", { name: "Recorded opening bank balance" }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Record opening balance" }),
  ).toHaveCount(0);
  await page.screenshot({
    path: "opening-results/opening-recorded.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "opening-results/opening-mobile.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 1280, height: 900 });

  await page
    .getByRole("button", { name: "Cash activity", exact: true })
    .click();
  await page.getByLabel("Cash activity start").fill("2026-10-01");
  await page.getByLabel("Cash activity end").fill("2026-10-31");
  await page.getByRole("button", { name: "Run cash activity" }).click();
  const cash = await request.get(
    "/api/reports/cash-activity?startsOn=2026-10-01&endsOn=2026-10-31",
    { headers },
  );
  expect(cash.ok()).toBe(true);
  expect(await cash.json()).toMatchObject({
    openingCash: "1000.25",
    closingCash: "1000.25",
    receipts: "0.00",
    payments: "0.00",
    movements: [],
  });
  await expect(
    page.getByText("Cash bridge · 2026-10-01 to 2026-10-31"),
  ).toBeVisible();
  await page.getByRole("button", { name: "Reports", exact: true }).click();
  await page.getByLabel("Report start").fill("2026-10-01");
  await page.getByLabel("Report end").fill("2026-10-31");
  await page.getByRole("button", { name: "Run reports", exact: true }).click();
  await expect(
    page.getByRole("row").filter({ hasText: "Net profit" }),
  ).toContainText("$0.00");
  await page
    .getByRole("button", { name: "Balance sheet", exact: true })
    .click();
  await expect(
    page.getByRole("row").filter({ hasText: "Total assets" }),
  ).toContainText("$1,000.25");
  await expect(
    page.getByRole("row").filter({ hasText: "Total equity" }),
  ).toContainText("$1,000.25");

  await page
    .getByRole("button", { name: "Reconciliation", exact: true })
    .click();
  await expect(page.getByLabel("Statement start")).toHaveValue("2026-10-01");
  await expect(page.getByLabel("Opening balance", { exact: true })).toHaveValue(
    "1000.25",
  );
  await page.getByLabel("Statement end").fill("2026-10-31");
  await page.getByLabel("Closing balance", { exact: true }).fill("1000.25");
  await page.getByRole("button", { name: "Preview reconciliation" }).click();
  await expect(page.getByText("No outstanding book entries.")).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Close statement", exact: true }),
  ).toBeEnabled();
  await page.screenshot({
    path: "opening-results/opening-reconciliation.png",
    fullPage: true,
  });
  await page
    .getByRole("button", { name: "Close statement", exact: true })
    .click();
  await expect(page.getByRole("status")).toHaveText(
    "Statement reconciliation saved. The period is closed.",
  );
  await expect(page.getByLabel("Statement start")).toHaveValue("2026-11-01");
  await expect(page.getByLabel("Opening balance", { exact: true })).toHaveValue(
    "1000.25",
  );
  await page.getByLabel("Statement end").fill("2026-11-30");
  await page.getByLabel("Closing balance", { exact: true }).fill("1000.25");
  await page.getByRole("button", { name: "Preview reconciliation" }).click();
  await page
    .getByRole("button", { name: "Close statement", exact: true })
    .click();
  await expect(page.getByRole("status")).toHaveText(
    "Statement reconciliation saved. The period is closed.",
  );
  expect((await state()).bankReconciliations).toHaveLength(2);
  expect((await state()).ledger).toEqual(saved.ledger);
  await page
    .getByRole("button", { name: "Opening bank balance", exact: true })
    .click();
  await page.getByRole("button", { name: "Reload workspace" }).click();
  await expect(
    page.getByRole("heading", { name: "Recorded opening bank balance" }),
  ).toBeVisible();
});
