import { test, expect } from "@playwright/test";

test("review and match recorded cash movements, undo, and retry after a failed refresh", async ({
  page,
  request,
}) => {
  const auth = {
    Authorization: `Basic ${Buffer.from("demo:demo-local-only").toString("base64")}`,
  };
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
  const vendor = await post(
    "/api/vendors",
    { name: "Cedar Office Supply", email: "accounts@cedar.example" },
    "matching-vendor",
  );
  const invoice = await post(
    "/api/invoices",
    {
      customerId: "demo-customer",
      description: "October consulting",
      issuedOn: "2026-10-01",
      dueOn: "2026-10-31",
      amount: "125",
    },
    "matching-invoice",
  );
  await post(
    `/api/invoices/${invoice}/payments`,
    { paidOn: "2026-10-02", amount: "125" },
    "matching-customer-pay",
  );
  const bill = await post(
    "/api/bills",
    {
      vendorId: vendor,
      reference: "CEDAR-80",
      description: "Office materials",
      issuedOn: "2026-10-01",
      dueOn: "2026-10-31",
      accountCode: "5000",
      amount: "80",
    },
    "matching-bill",
  );
  await post(
    `/api/bills/${bill}/payments`,
    { paidOn: "2026-10-02", amount: "40" },
    "matching-bill-pay",
  );
  await post(
    "/api/expenses",
    {
      vendorId: vendor,
      description: "October stationery",
      spentOn: "2026-10-03",
      accountCode: "5000",
      amount: "75",
    },
    "matching-expense",
  );
  await post(
    "/api/bank/imports",
    {
      label: "Matching example",
      csv: "transaction_id,date,description,amount\nMATCH-IN,2026-10-03,Consulting payment,125\nMATCH-BILL,2026-10-03,Cedar bill payment,-40\nMATCH-EXPENSE,2026-10-03,Cedar stationery,-75\nMATCH-UNKNOWN,2026-10-03,Unrecorded bank charge,-19\n",
    },
    "matching-import",
  );
  const before = await request
    .get("/api/state", { headers: auth })
    .then((r) => r.json());
  await page.goto("/");
  await page.getByLabel("Username").fill("demo");
  await page.getByLabel("Password", { exact: true }).fill("demo-local-only");
  await page.getByRole("button", { name: "Open workspace" }).click();
  await page
    .getByRole("button", { name: "Bank matching", exact: true })
    .click();
  const table = page
    .locator("section")
    .filter({
      has: page.getByRole("heading", {
        name: "Bank transactions to review",
        exact: true,
      }),
    });
  const review = page
    .locator("section")
    .filter({
      has: page.getByRole("heading", {
        name: "Review recorded entries",
        exact: true,
      }),
    });
  for (const [id, kind] of [
    ["MATCH-IN", "Customer payment"],
    ["MATCH-BILL", "Bill payment"],
    ["MATCH-EXPENSE", "Direct expense"],
  ]) {
    await page
      .getByRole("button", { name: `Review ${id}`, exact: true })
      .click();
    await expect(review).toContainText(kind);
    await expect(
      page.getByRole("button", { name: "Confirm match", exact: true }),
    ).toBeDisabled();
    await page.getByLabel("Select entry 1", { exact: true }).check();
    if (id === "MATCH-IN")
      await page.screenshot({
        path: "test-results/bank-match-review.png",
        fullPage: true,
      });
    page.once("dialog", (dialog) => dialog.accept());
    await page
      .getByRole("button", { name: "Confirm match", exact: true })
      .click();
    await expect(page.getByRole("status")).toContainText(
      "Bank transaction matched",
    );
    await expect(table.getByRole("row").filter({ hasText: id })).toContainText(
      "Matched",
    );
  }
  await page
    .getByRole("button", { name: "Review MATCH-UNKNOWN", exact: true })
    .click();
  await expect(review).toContainText("No eligible recorded entries");
  await expect(
    page.getByRole("button", { name: "Confirm match", exact: true }),
  ).toHaveCount(0);
  page.once("dialog", (dialog) => dialog.dismiss());
  await page
    .getByRole("button", { name: "Undo match MATCH-EXPENSE", exact: true })
    .click();
  await expect(
    table.getByRole("row").filter({ hasText: "MATCH-EXPENSE" }),
  ).toContainText("Matched");
  page.once("dialog", (dialog) => dialog.accept());
  await page
    .getByRole("button", { name: "Undo match MATCH-EXPENSE", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText("Match removed");
  await expect(
    table.getByRole("row").filter({ hasText: "MATCH-EXPENSE" }),
  ).toContainText("Unmatched");
  await page
    .getByRole("button", { name: "Review MATCH-EXPENSE", exact: true })
    .click();
  await page.getByLabel("Select entry 1", { exact: true }).check();
  let interrupted = false;
  await page.route("**/api/state", async (route) => {
    if (!interrupted) {
      interrupted = true;
      await route.fulfill({
        status: 503,
        contentType: "application/json",
        body: "{}",
      });
    } else await route.continue();
  });
  page.once("dialog", (dialog) => dialog.accept());
  await page
    .getByRole("button", { name: "Confirm match", exact: true })
    .click();
  await expect(page.getByRole("alert")).toContainText("could not be completed");
  page.once("dialog", (dialog) => dialog.accept());
  await page
    .getByRole("button", { name: "Confirm match", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText(
    "Bank transaction matched",
  );
  await page.unroute("**/api/state");
  const after = await request
    .get("/api/state", { headers: auth })
    .then((r) => r.json());
  expect(after.ledger).toEqual(before.ledger);
  expect(after.trialBalance).toEqual(before.trialBalance);
  expect(after.bankMatches).toHaveLength(3);
  expect(after.bankMatchEvents).toHaveLength(5);
  await page.screenshot({
    path: "test-results/bank-matching.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await page
    .getByRole("button", { name: "Review MATCH-UNKNOWN", exact: true })
    .click();
  await expect(review).toContainText("No eligible recorded entries");
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "test-results/mobile-bank-matching.png",
    fullPage: true,
  });
});
