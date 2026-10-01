import { test, expect } from "@playwright/test";

test("post a bill, pay part, record an expense, and attach and download supporting files", async ({
  page,
  request,
}) => {
  await expect
    .poll(
      async () =>
        request
          .get("/api/csrf")
          .then((r) => r.status())
          .catch(() => 0),
      { timeout: 20000 },
    )
    .toBe(200);
  await page.goto("/");
  await page.getByLabel("Username").fill("demo");
  await page.getByLabel("Password", { exact: true }).fill("demo-local-only");
  await page.getByRole("button", { name: "Open workspace" }).click();
  await page.getByRole("button", { name: "Vendors", exact: true }).click();
  for (const [name, email] of [
    ["Harbor Supply", "accounts@harbor.example"],
    ["Cloudline Tools", "billing@cloudline.example"],
  ]) {
    await page.getByLabel("Name", { exact: true }).fill(name);
    await page.getByLabel("Email", { exact: true }).fill(email);
    await page.getByRole("button", { name: "Add vendor", exact: true }).click();
    await expect(page.getByRole("status")).toContainText("Vendor added");
  }
  await page.getByRole("button", { name: "Bills", exact: true }).click();
  const billForm = page
    .locator("form")
    .filter({
      has: page.getByRole("button", { name: "Post bill", exact: true }),
    });
  await billForm
    .getByRole("combobox", { name: "Vendor", exact: true })
    .selectOption({ label: "Harbor Supply" });
  await billForm.getByLabel("Bill reference").fill("SUP-104");
  await billForm.getByLabel("Description").fill("Office supplies for October");
  await billForm
    .getByRole("combobox", { name: "Expense category", exact: true })
    .selectOption({ label: "Office supplies" });
  await billForm.getByLabel("Bill date").fill("2026-10-01");
  await billForm.getByLabel("Due date").fill("2026-10-31");
  await billForm.getByLabel("Amount (USD)").fill("600");
  await billForm
    .getByRole("button", { name: "Post bill", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText("Bill posted");
  const allBills = page
    .locator("section")
    .filter({
      has: page.getByRole("heading", { name: "All bills", exact: true }),
    });
  const bill = allBills.getByRole("row").filter({ hasText: "SUP-104" });
  await expect(bill).toContainText("$600.00");
  const paymentForm = page
    .locator("form")
    .filter({
      has: page.getByRole("button", {
        name: "Record bill payment",
        exact: true,
      }),
    });
  await paymentForm.getByLabel("Payment date").fill("2026-10-02");
  await paymentForm.getByLabel("Amount (USD)").fill("200");
  await paymentForm
    .getByRole("button", { name: "Record bill payment", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText("Bill payment recorded");
  await expect(bill).toContainText("$400.00");
  await expect(bill).toContainText("Part paid");
  const receipts = page
    .locator("section")
    .filter({
      has: page.getByRole("heading", {
        name: "Supporting receipts",
        exact: true,
      }),
    });
  await receipts
    .getByLabel("Receipt file")
    .setInputFiles("tests/fixtures/supply-receipt.png");
  await receipts
    .getByRole("button", { name: "Attach receipt", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText("Receipt attached");
  await expect(
    receipts.getByRole("row").filter({ hasText: "supply-receipt.png" }),
  ).toHaveCount(1);
  const downloadPromise = page.waitForEvent("download");
  await receipts
    .getByRole("button", {
      name: "Download receipt supply-receipt.png",
      exact: true,
    })
    .click();
  const downloaded = await downloadPromise;
  expect(downloaded.suggestedFilename()).toMatch(/^receipt-[a-f0-9-]+\.png$/);
  await downloaded.saveAs("test-results/downloaded-supply-receipt.png");
  const { readFile } = await import("node:fs/promises");
  expect(
    (await readFile("test-results/downloaded-supply-receipt.png"))
      .subarray(1, 4)
      .toString(),
  ).toBe("PNG");
  await page.screenshot({ path: "test-results/bills.png", fullPage: true });
  await page.getByRole("button", { name: "Expenses", exact: true }).click();
  const expenseForm = page
    .locator("form")
    .filter({
      has: page.getByRole("button", { name: "Record expense", exact: true }),
    });
  await expenseForm
    .getByRole("combobox", { name: "Vendor", exact: true })
    .selectOption({ label: "Cloudline Tools" });
  await expenseForm.getByLabel("Description").fill("October design software");
  await expenseForm
    .getByRole("combobox", { name: "Expense category", exact: true })
    .selectOption({ label: "Software subscriptions" });
  await expenseForm.getByLabel("Expense date").fill("2026-10-03");
  await expenseForm.getByLabel("Amount (USD)").fill("50");
  await expenseForm
    .getByRole("button", { name: "Record expense", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText("Expense recorded");
  const expenseReceipts = page
    .locator("section")
    .filter({
      has: page.getByRole("heading", {
        name: "Supporting receipts",
        exact: true,
      }),
    });
  await expenseReceipts
    .getByLabel("Receipt file")
    .setInputFiles("tests/fixtures/software-receipt.jpg");
  await expenseReceipts
    .getByRole("button", { name: "Attach receipt", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText("Receipt attached");
  await expect(
    expenseReceipts
      .getByRole("row")
      .filter({ hasText: "software-receipt.jpg" }),
  ).toHaveCount(1);
  await page.screenshot({ path: "test-results/expenses.png", fullPage: true });
  await page.getByRole("button", { name: "Vendors", exact: true }).click();
  const vendor = page
    .getByRole("row")
    .filter({ hasText: "accounts@harbor.example" });
  await expect(vendor).toContainText("$600.00");
  await expect(vendor).toContainText("$200.00");
  await expect(vendor).toContainText("$400.00");
  await vendor
    .getByRole("button", { name: "Harbor Supply", exact: true })
    .click();
  await page.screenshot({
    path: "test-results/vendor-balances.png",
    fullPage: true,
  });
  await page
    .getByRole("button", { name: "Trial balance", exact: true })
    .click();
  const totals = page.locator("tfoot th");
  await expect(totals.nth(1)).toHaveText(await totals.nth(2).innerText());
  await page.screenshot({
    path: "test-results/purchases-trial-balance.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "Overview", exact: true }).click();
  await page.screenshot({
    path: "test-results/purchases-overview.png",
    fullPage: true,
  });
});

test("reject duplicate bills and forged receipts, then reverse a mistaken purchase on a narrow screen", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await page.getByLabel("Username").fill("demo");
  await page.getByLabel("Password", { exact: true }).fill("demo-local-only");
  await page.getByRole("button", { name: "Open workspace" }).click();
  await page.getByRole("button", { name: "Bills", exact: true }).click();
  const form = page
    .locator("form")
    .filter({
      has: page.getByRole("button", { name: "Post bill", exact: true }),
    });
  await form
    .getByRole("combobox", { name: "Vendor", exact: true })
    .selectOption({ label: "Harbor Supply" });
  await form.getByLabel("Bill reference").fill("sup-104");
  await form.getByLabel("Description").fill("Duplicate attempt");
  await form.getByLabel("Amount (USD)").fill("600");
  await form.getByRole("button", { name: "Post bill", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("already recorded");
  await form.getByLabel("Bill reference").fill("WRONG-ENTRY");
  await form.getByLabel("Amount (USD)").fill("10");
  await form.getByRole("button", { name: "Post bill", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("Bill posted");
  const receiptForm = page
    .locator("form")
    .filter({
      has: page.getByRole("button", { name: "Attach receipt", exact: true }),
    });
  await receiptForm
    .getByLabel("Receipt file")
    .setInputFiles({
      name: "fake.pdf",
      mimeType: "application/pdf",
      buffer: Buffer.from("This is not a PDF"),
    });
  await receiptForm
    .getByRole("button", { name: "Attach receipt", exact: true })
    .click();
  await expect(page.getByRole("alert")).toContainText("not a PDF");
  const correction = page
    .locator("form")
    .filter({
      has: page.getByRole("button", { name: "Void bill", exact: true }),
    });
  page.once("dialog", (dialog) => dialog.accept());
  await correction
    .getByRole("button", { name: "Void bill", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText("Bill voided");
  await page.getByRole("button", { name: "Expenses", exact: true }).click();
  const expense = page
    .locator("form")
    .filter({
      has: page.getByRole("button", { name: "Record expense", exact: true }),
    });
  await expense.getByLabel("Description").fill("Mistaken duplicate expense");
  await expense.getByLabel("Amount (USD)").fill("10");
  await expense
    .getByRole("button", { name: "Record expense", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText("Expense recorded");
  const reverse = page
    .locator("form")
    .filter({
      has: page.getByRole("button", { name: "Reverse expense", exact: true }),
    });
  const option = await reverse
    .getByLabel("Recorded expense")
    .locator("option")
    .filter({ hasText: "Mistaken duplicate expense" })
    .getAttribute("value");
  await reverse.getByLabel("Recorded expense").selectOption(option!);
  page.once("dialog", (dialog) => dialog.accept());
  await reverse
    .getByRole("button", { name: "Reverse expense", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText("Expense reversed");
  await page.getByRole("button", { name: "Overview", exact: true }).click();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "test-results/mobile-purchases.png",
    fullPage: true,
  });
});
