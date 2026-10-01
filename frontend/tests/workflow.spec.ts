import { test, expect } from "@playwright/test";

test("post an invoice, record a partial payment, and inspect balanced entries", async ({
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
  await expect(
    page.getByRole("heading", { name: "Overview", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Invoices", exact: true }).click();
  const invoiceForm = page
    .locator("form")
    .filter({
      has: page.getByRole("button", { name: "Post invoice", exact: true }),
    });
  const description = `Brand identity ${Date.now()}`;
  await invoiceForm.getByLabel("Description").fill(description);
  await invoiceForm.getByLabel("Invoice date").fill("2026-09-01");
  await invoiceForm.getByLabel("Due date").fill("2026-09-30");
  await invoiceForm.getByLabel("Amount (USD)").fill("1200");
  await invoiceForm
    .getByRole("button", { name: "Post invoice", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText("Invoice posted");
  const row = page
    .locator("section")
    .filter({
      has: page.getByRole("heading", { name: "All invoices", exact: true }),
    })
    .getByRole("row")
    .filter({ hasText: description });
  await expect(row).toContainText("$1,200.00");
  const paymentForm = page
    .locator("form")
    .filter({
      has: page.getByRole("button", { name: "Record payment", exact: true }),
    });
  const option = await paymentForm
    .getByLabel("Open invoice")
    .locator("option")
    .filter({ hasText: description })
    .getAttribute("value");
  await paymentForm.getByLabel("Open invoice").selectOption(option!);
  await paymentForm.getByLabel("Payment date").fill("2026-09-03");
  await paymentForm.getByLabel("Amount (USD)").fill("700");
  await paymentForm
    .getByRole("button", { name: "Record payment", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText("Payment recorded");
  await expect(row).toContainText("$500.00");
  await expect(row).toContainText("Part paid");
  await page
    .getByRole("button", { name: "General ledger", exact: true })
    .click();
  await expect(
    page.getByRole("row").filter({ hasText: description }),
  ).toHaveCount(2);
  await page
    .getByRole("button", { name: "Trial balance", exact: true })
    .click();
  const totals = page.locator("tfoot th");
  await expect(totals.nth(1)).toHaveText(await totals.nth(2).innerText());
  await page.screenshot({
    path: "test-results/trial-balance.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "Overview", exact: true }).click();
  await page.screenshot({ path: "test-results/overview.png", fullPage: true });
});

test("save and edit a draft, post its number, download a PDF, and check the customer balance", async ({
  page,
  request,
}) => {
  await page.goto("/");
  await page.getByLabel("Username").fill("demo");
  await page.getByLabel("Password", { exact: true }).fill("demo-local-only");
  await page.getByRole("button", { name: "Open workspace" }).click();
  await page.getByRole("button", { name: "Customers", exact: true }).click();
  await page.getByLabel("Name", { exact: true }).fill("Oak & Elm Studio");
  await page
    .getByLabel("Email", { exact: true })
    .fill("accounts@oak-elm.example");
  await page.getByRole("button", { name: "Add customer", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("Customer added");
  await page.getByRole("button", { name: "Invoices", exact: true }).click();
  const form = page
    .locator("form")
    .filter({
      has: page.getByRole("button", { name: "Save draft", exact: true }),
    });
  const description = "Website design - launch package";
  await form
    .getByRole("combobox", { name: "Customer", exact: true })
    .selectOption({ label: "Oak & Elm Studio" });
  await form.getByLabel("Description").fill(description);
  await form.getByLabel("Invoice date").fill("2026-09-01");
  await form.getByLabel("Due date").fill("2026-09-30");
  await form.getByLabel("Amount (USD)").fill("1250");
  const auth = {
    Authorization: `Basic ${Buffer.from("demo:demo-local-only").toString("base64")}`,
  };
  const before = await request
    .get("/api/state", { headers: auth })
    .then((r) => r.json());
  await form.getByRole("button", { name: "Save draft", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("Draft saved");
  const after = await request
    .get("/api/state", { headers: auth })
    .then((r) => r.json());
  expect(after.ledger).toEqual(before.ledger);
  expect(after.invoices).toEqual(before.invoices);
  const drafts = page
    .locator("section")
    .filter({
      has: page.getByRole("heading", { name: "Saved drafts", exact: true }),
    });
  const draft = drafts.getByRole("row").filter({ hasText: description });
  await expect(draft).toContainText("$1,250.00");
  await page.screenshot({ path: "test-results/drafts.png", fullPage: true });
  await draft.getByRole("button", { name: "Edit draft", exact: true }).click();
  const editForm = page
    .locator("form")
    .filter({
      has: page.getByRole("button", { name: "Save changes", exact: true }),
    });
  await expect(editForm.getByLabel("Description")).toHaveValue(description);
  await editForm.getByLabel("Amount (USD)").fill("1500");
  await editForm
    .getByRole("button", { name: "Save changes", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText("Draft updated");
  await expect(draft).toContainText("$1,500.00");
  page.once("dialog", (dialog) => dialog.accept());
  await draft.getByRole("button", { name: "Post draft", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("Invoice posted from");
  await expect(draft).toHaveCount(0);
  const postedSection = page
    .locator("section")
    .filter({
      has: page.getByRole("heading", { name: "All invoices", exact: true }),
    });
  const posted = postedSection
    .getByRole("row")
    .filter({ hasText: description });
  await expect(posted).toContainText(/INV-\d+/);
  const paymentForm = page
    .locator("form")
    .filter({
      has: page.getByRole("button", { name: "Record payment", exact: true }),
    });
  const option = await paymentForm
    .getByLabel("Open invoice")
    .locator("option")
    .filter({ hasText: description })
    .getAttribute("value");
  await paymentForm.getByLabel("Open invoice").selectOption(option!);
  await paymentForm.getByLabel("Payment date").fill("2026-09-05");
  await paymentForm.getByLabel("Amount (USD)").fill("500");
  await paymentForm
    .getByRole("button", { name: "Record payment", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText("Payment recorded");
  await expect(posted).toContainText("$1,000.00");
  const downloadPromise = page.waitForEvent("download");
  await posted.getByRole("button", { name: /Download INV-/ }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toMatch(/^INV-\d+\.pdf$/);
  await download.saveAs("test-results/invoice-example.pdf");
  const { readFile } = await import("node:fs/promises");
  expect(
    (await readFile("test-results/invoice-example.pdf"))
      .subarray(0, 5)
      .toString(),
  ).toBe("%PDF-");
  await page.getByRole("button", { name: "Customers", exact: true }).click();
  const customer = page
    .getByRole("row")
    .filter({ hasText: "accounts@oak-elm.example" });
  await expect(customer).toContainText("$1,500.00");
  await expect(customer).toContainText("$500.00");
  await expect(customer).toContainText("$1,000.00");
  await customer
    .getByRole("button", { name: "Oak & Elm Studio", exact: true })
    .click();
  await expect(
    page.getByRole("heading", {
      name: "Oak & Elm Studio · Invoices",
      exact: true,
    }),
  ).toBeVisible();
  await page.screenshot({
    path: "test-results/customer-balances.png",
    fullPage: true,
  });
});

test("discard a draft and use the invoice workspace on a narrow screen", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await page.getByLabel("Username").fill("demo");
  await page.getByLabel("Password", { exact: true }).fill("demo-local-only");
  await page.getByRole("button", { name: "Open workspace" }).click();
  await page.getByRole("button", { name: "Invoices", exact: true }).click();
  const form = page
    .locator("form")
    .filter({
      has: page.getByRole("button", { name: "Save draft", exact: true }),
    });
  await form.getByLabel("Description").fill("Cancelled proposal");
  await form.getByLabel("Amount (USD)").fill("25");
  await form.getByRole("button", { name: "Save draft", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("Draft saved");
  const row = page.getByRole("row").filter({ hasText: "Cancelled proposal" });
  page.once("dialog", (dialog) => dialog.accept());
  await row.getByRole("button", { name: "Discard draft", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("Draft discarded");
  await expect(row).toHaveCount(0);
  await page
    .getByRole("button", { name: "Reload workspace", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText("Workspace reloaded");
  await page.getByRole("button", { name: "Customers", exact: true }).click();
  const balanceTable = page.locator("section").filter({ hasText: "All-time totals exclude drafts" });
  await expect(balanceTable.getByRole("table")).toBeVisible();
  const canScroll = await balanceTable.evaluate(element => element.scrollWidth > element.clientWidth);
  expect(canScroll).toBe(true);
  await page.getByRole("button", { name: "Overview", exact: true }).click();
  await page.screenshot({
    path: "test-results/mobile-overview.png",
    fullPage: true,
  });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
});

test("retry after a failed refresh does not post the invoice twice", async ({
  page,
  request,
}) => {
  await page.goto("/");
  await page.getByLabel("Username").fill("demo");
  await page.getByLabel("Password", { exact: true }).fill("demo-local-only");
  await page.getByRole("button", { name: "Open workspace" }).click();
  await page.getByRole("button", { name: "Invoices", exact: true }).click();
  const form = page
    .locator("form")
    .filter({
      has: page.getByRole("button", { name: "Post invoice", exact: true }),
    });
  const description = "Connection retry check";
  await form.getByLabel("Description").fill(description);
  await form.getByLabel("Amount (USD)").fill("45");
  let releasePosting!: () => void;
  const postingGate = new Promise<void>(resolve => { releasePosting = resolve; });
  await page.route("**/api/invoices", async route => {
    await postingGate;
    await route.continue();
  });
  let failNextRefresh = true;
  await page.route("**/api/state", (route) => {
    if (failNextRefresh) {
      failNextRefresh = false;
      return route.abort();
    }
    return route.continue();
  });
  await form.getByRole("button", { name: "Post invoice", exact: true }).click();
  await expect(page.getByRole("button", { name: "Lock workspace", exact: true })).toBeDisabled();
  releasePosting();
  await expect(page.getByRole("alert")).toBeVisible();
  await expect(form.getByLabel("Description")).toHaveValue(description);
  await form.getByRole("button", { name: "Post invoice", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("Invoice posted");
  const state = await request
    .get("/api/state", {
      headers: {
        Authorization: `Basic ${Buffer.from("demo:demo-local-only").toString("base64")}`,
      },
    })
    .then((r) => r.json());
  expect(
    state.invoices.filter(
      (i: { description: string }) => i.description === description,
    ),
  ).toHaveLength(1);
  await expect(page.getByRole("button", { name: "Lock workspace", exact: true })).toBeEnabled();
  await page.getByRole("button", { name: "Lock workspace", exact: true }).click();
  await expect(page.getByLabel("Username")).toBeVisible();
});
