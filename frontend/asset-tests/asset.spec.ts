import { test, expect } from "@playwright/test";

test("register, depreciate and retire equipment, then correct accidental capitalization", async ({
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
      if (failOnce) {
        failOnce = false;
        await route.abort("failed");
      } else await route.continue();
    });
  }
  const keys: string[] = [];
  page.on("request", (r) => {
    if (r.method() === "POST" && new URL(r.url()).pathname === "/api/assets")
      keys.push(r.headers()["idempotency-key"]);
  });
  const vendor = await post(
    "/api/vendors",
    { name: "Equipment supplier", email: "accounts@example.test" },
    "vendor",
  );
  const expense = await post(
    "/api/expenses",
    {
      vendorId: vendor,
      description: "Office equipment",
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
  await page.getByRole("button", { name: "Fixed assets", exact: true }).click();
  await page.getByLabel("Paid purchase", { exact: true }).selectOption(expense);
  await page.getByLabel("Useful life (months)", { exact: true }).fill("3");
  await page.getByLabel("Residual value (USD)", { exact: true }).fill("10.00");
  await page.getByLabel("In-service start", { exact: true }).fill("2026-10-02");
  await page.getByLabel("Asset name", { exact: true }).fill("Office computer");
  await page
    .getByRole("button", { name: "Register fixed asset", exact: true })
    .click();
  await expect(page.getByRole("alert")).toContainText("first-of-month");
  await page.getByLabel("In-service start", { exact: true }).fill("2026-10-01");
  await page.getByLabel("In-service start", { exact: true }).fill("9999-12-01");
  await page
    .getByRole("button", { name: "Register fixed asset", exact: true })
    .click();
  await expect(page.getByRole("alert")).toContainText(
    "one to six hundred months",
  );
  await page.getByLabel("In-service start", { exact: true }).fill("2026-10-01");
  await expect(page.getByRole("alert")).toHaveCount(0);
  const preview = page.getByRole("table", {
    name: "Depreciation schedule preview",
  });
  await expect(preview.getByText("$30.00", { exact: true })).toHaveCount(3);
  await page.screenshot({
    path: "asset-results/asset-editor.png",
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
    path: "asset-results/mobile-asset.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 1280, height: 900 });
  await interruptRefresh();
  await page
    .getByRole("button", { name: "Register fixed asset", exact: true })
    .click();
  await expect(page.getByRole("alert")).toBeVisible();
  await expect(page.getByLabel("Asset name", { exact: true })).toHaveValue(
    "Office computer",
  );
  expect((await state()).fixedAssets).toHaveLength(1);
  await page
    .getByRole("button", { name: "Register fixed asset", exact: true })
    .click();

  await expect(page.getByRole("status")).toContainText("Fixed asset created");
  expect(keys).toHaveLength(2);
  expect(keys[0]).toBe(keys[1]);
  expect((await state()).fixedAssets).toHaveLength(1);
  await page
    .getByRole("button", {
      name: "Depreciate Office computer on 2026-10-31",
      exact: true,
    })
    .click();
  await expect(page.getByRole("status")).toContainText("Depreciation posted");
  await expect(
    page.getByText("Net book value: $70.00", { exact: false }),
  ).toBeVisible();
  await page.screenshot({
    path: "asset-results/asset-depreciation.png",
    fullPage: true,
  });
  const october = await reports("2026-10-01", "2026-10-31");
  expect(Number(october.profitLoss.expenses)).toBe(30);
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
      name: "Retire asset for Office computer",
      exact: true,
    })
    .click();
  await page
    .getByLabel("Asset retirement date", { exact: true })
    .fill("2026-11-01");
  await page
    .getByLabel("Asset retirement reason", { exact: true })
    .fill("Equipment damaged beyond repair");
  await interruptRefresh();
  await page
    .getByRole("button", { name: "Confirm asset retirement", exact: true })
    .click();
  await expect(page.getByRole("alert")).toBeVisible();
  await expect(
    page.getByLabel("Asset retirement reason", { exact: true }),
  ).toHaveValue("Equipment damaged beyond repair");
  expect(
    (await state()).fixedAssets.filter(
      (p: { retirement_id: string | null }) => p.retirement_id,
    ),
  ).toHaveLength(1);
  await page
    .getByRole("button", { name: "Confirm asset retirement", exact: true })
    .click();

  await expect(page.getByRole("status")).toContainText("Asset retired");
  await expect(page.getByText("Retired", { exact: true })).toBeVisible();
  await page.screenshot({
    path: "asset-results/asset-retired.png",
    fullPage: true,
  });
  expect(await reports("2026-10-01", "2026-10-31")).toEqual(october);
  const november = await reports("2026-11-01", "2026-11-30");
  expect(Number(november.profitLoss.expenses)).toBe(70);
  expect(Number(november.balanceSheet.difference)).toBe(0);
  const second = await post(
    "/api/expenses",
    {
      vendorId: vendor,
      description: "One-time supplies purchase",
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
  await page.getByLabel("In-service start", { exact: true }).fill("2026-11-01");
  await page
    .getByLabel("Asset name", { exact: true })
    .fill("Accidental capitalization");
  await page
    .getByRole("button", { name: "Register fixed asset", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText("Fixed asset created");
  await page
    .getByRole("button", {
      name: "Correct accidental asset Accidental capitalization",
      exact: true,
    })
    .click();
  await page
    .getByLabel("Asset correction reason", { exact: true })
    .fill("This was a one-time purchase");
  await page
    .getByRole("button", { name: "Confirm asset correction", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText("Asset corrected");
  await expect(
    page.getByText("Corrected to direct expense", { exact: true }),
  ).toBeVisible();
  await page.screenshot({
    path: "asset-results/asset-corrected.png",
    fullPage: true,
  });
  const result = await state();
  expect(result.fixedAssets).toHaveLength(2);
  expect(
    result.assetPeriods.filter((p: { entry_id: string | null }) => p.entry_id),
  ).toHaveLength(1);
  expect(
    result.fixedAssets.filter(
      (p: { retirement_id: string | null }) => p.retirement_id,
    ),
  ).toHaveLength(1);
  expect(
    result.fixedAssets.filter(
      (p: { correction_id: string | null }) => p.correction_id,
    ),
  ).toHaveLength(1);
});
