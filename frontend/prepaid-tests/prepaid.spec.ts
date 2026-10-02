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
  async function reports(start: string, end: string) {
    const r = await request.get(
      `/api/reports?startsOn=${start}&endsOn=${end}`,
      { headers: auth },
    );
    expect(r.ok()).toBe(true);
    return r.json();
  }
  async function interruptRefresh() {
    await page.unroute("**/api/state");
    let failOnce = true;
    await page.route("**/api/state", async (route) => {
      if (failOnce) { failOnce = false; await route.abort("failed"); }
      else await route.continue();
    });
  }
  const keys: string[] = [];
  page.on("request", (r) => {
    if (r.method() === "POST" && new URL(r.url()).pathname === "/api/prepaid")
      keys.push(r.headers()["idempotency-key"]);
  });
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
  await interruptRefresh();
  await page
    .getByRole("button", { name: "Create prepaid plan", exact: true })
    .click();
  await expect(page.getByRole("alert")).toBeVisible();
  await expect(page.getByLabel("Prepaid memo", { exact: true })).toHaveValue(
    "Software October to December",
  );
  expect((await state()).prepaidPlans).toHaveLength(1);
  await page
    .getByRole("button", { name: "Create prepaid plan", exact: true })
    .click();

  await expect(page.getByRole("status")).toContainText("Prepaid plan created");
  expect(keys).toHaveLength(2);
  expect(keys[0]).toBe(keys[1]);
  expect((await state()).prepaidPlans).toHaveLength(1);
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
  const october = await reports("2026-10-01", "2026-10-31");
  expect(Number(october.profitLoss.expenses)).toBe(33.33);
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
  await interruptRefresh();
  await page
    .getByRole("button", { name: "Confirm benefit cancellation", exact: true })
    .click();
  await expect(page.getByRole("alert")).toBeVisible();
  await expect(
    page.getByLabel("Benefit cancellation reason", { exact: true }),
  ).toHaveValue("Subscription ended early");
  expect(
    (await state()).prepaidPlans.filter(
      (p: { cancellation_id: string | null }) => p.cancellation_id,
    ),
  ).toHaveLength(1);
  await page
    .getByRole("button", { name: "Confirm benefit cancellation", exact: true })
    .click();

  await expect(page.getByRole("status")).toContainText("Prepaid benefit ended");
  await expect(page.getByText("Cancelled", { exact: true })).toBeVisible();
  await page.screenshot({
    path: "prepaid-results/prepaid-cancelled.png",
    fullPage: true,
  });
  expect(await reports("2026-10-01", "2026-10-31")).toEqual(october);
  const november = await reports("2026-11-01", "2026-11-30");
  expect(Number(november.profitLoss.expenses)).toBe(66.67);
  expect(Number(november.balanceSheet.difference)).toBe(0);
  const second = await post(
    "/api/expenses",
    {
      vendorId: vendor,
      description: "One-time software purchase",
      spentOn: "2026-11-01",
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
  await page.getByLabel("Benefit start", { exact: true }).fill("2026-11-01");
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
