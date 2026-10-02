import { test, expect } from "@playwright/test";

test("create, recognize and end a prepaid benefit, then correct an untouched plan", async ({
  page,
  request,
}) => {
  test.setTimeout(60000);
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
    return result.id;
  }
  async function state() {
    const r = await request.get("/api/state", { headers: auth });
    expect(r.ok()).toBe(true);
    return r.json();
  }
  const vendor = await post(
    "/api/vendors",
    { name: "Software supplier", email: "accounts@example.test" },
    "vendor",
  );
  const expense = await post(
    "/api/expenses",
    {
      vendorId: vendor,
      description: "Quarterly software subscription",
      spentOn: "2026-10-01",
      accountCode: "5100",
      amount: "100",
    },
    "expense",
  );
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto("/");
  await page.getByLabel("Username").fill("demo");
  await page.getByLabel("Password", { exact: true }).fill("demo-local-only");
  await page.getByRole("button", { name: "Open workspace" }).click();
  await page
    .getByRole("button", { name: "Prepaid expenses", exact: true })
    .click();
  await page.getByLabel("Paid purchase", { exact: true }).selectOption(expense);
  await page.getByLabel("Benefit start", { exact: true }).fill("2026-10-02");
  await page
    .getByLabel("Prepaid memo", { exact: true })
    .fill("Software October to December");
  await page
    .getByRole("button", { name: "Create prepaid plan", exact: true })
    .click();
  await expect(page.getByRole("alert")).toContainText("first-of-month");
  await page.getByLabel("Benefit start", { exact: true }).fill("2026-10-01");
  await page.getByLabel("Benefit start", { exact: true }).fill("9999-12-01");
  await page
    .getByRole("button", { name: "Create prepaid plan", exact: true })
    .click();
  await expect(page.getByRole("alert")).toContainText("one to sixty months");
  await page.getByLabel("Benefit start", { exact: true }).fill("2026-10-01");
  await expect(page.getByRole("alert")).toHaveCount(0);
  const preview = page.getByRole("table", { name: "Prepaid schedule preview" });
  await expect(preview.getByText("$33.33", { exact: true })).toHaveCount(2);
  await expect(preview.getByText("$33.34", { exact: true })).toHaveCount(1);
  await page.screenshot({
    path: "prepaid-results/prepaid-editor.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect
    .poll(() =>
      page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    )
    .toBe(true);
  await page.screenshot({
    path: "prepaid-results/mobile-prepaid.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 1280, height: 900 });
  await page
    .getByRole("button", { name: "Create prepaid plan", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText("Prepaid plan created");
  await page
    .getByRole("button", {
      name: "Recognize Software October to December on 2026-10-31",
      exact: true,
    })
    .click();
  await expect(page.getByRole("status")).toContainText(
    "Prepaid month recognized",
  );
  await expect(
    page.getByText("Remaining prepaid asset: $66.67", { exact: false }),
  ).toBeVisible();
  await page.screenshot({
    path: "prepaid-results/prepaid-recognition.png",
    fullPage: true,
  });
  await page
    .getByRole("button", {
      name: "Cancel remaining benefit for Software October to December",
      exact: true,
    })
    .click();
  await page
    .getByLabel("Benefit cancellation date", { exact: true })
    .fill("2026-11-01");
  await page
    .getByLabel("Benefit cancellation reason", { exact: true })
    .fill("Subscription ended early");
  await page
    .getByRole("button", { name: "Confirm benefit cancellation", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText("Prepaid benefit ended");
  await expect(page.getByText("Cancelled", { exact: true })).toBeVisible();
  await page.screenshot({
    path: "prepaid-results/prepaid-cancelled.png",
    fullPage: true,
  });
  const second = await post(
    "/api/expenses",
    {
      vendorId: vendor,
      description: "One-time software purchase",
      spentOn: "2026-10-01",
      accountCode: "5100",
      amount: "25",
    },
    "second",
  );
  await page
    .getByRole("button", { name: "Reload workspace", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText("Workspace reloaded");
  await page.getByLabel("Paid purchase", { exact: true }).selectOption(second);
  await page.getByLabel("Benefit start", { exact: true }).fill("2026-10-01");
  await page
    .getByLabel("Prepaid memo", { exact: true })
    .fill("Accidental deferral");
  await page
    .getByRole("button", { name: "Create prepaid plan", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText("Prepaid plan created");
  await page
    .getByRole("button", {
      name: "Correct accidental plan Accidental deferral",
      exact: true,
    })
    .click();
  await page
    .getByLabel("Prepaid correction reason", { exact: true })
    .fill("This was a one-time purchase");
  await page
    .getByRole("button", { name: "Confirm prepaid correction", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText(
    "Prepaid plan corrected",
  );
  await expect(
    page.getByText("Corrected to direct expense", { exact: true }),
  ).toBeVisible();
  await page.screenshot({
    path: "prepaid-results/prepaid-corrected.png",
    fullPage: true,
  });
  const result = await state();
  expect(result.prepaidPlans).toHaveLength(2);
  expect(
    result.prepaidPeriods.filter(
      (p: { entry_id: string | null }) => p.entry_id,
    ),
  ).toHaveLength(1);
  expect(
    result.prepaidPlans.filter(
      (p: { cancellation_id: string | null }) => p.cancellation_id,
    ),
  ).toHaveLength(1);
  expect(
    result.prepaidPlans.filter(
      (p: { correction_id: string | null }) => p.correction_id,
    ),
  ).toHaveLength(1);
});
