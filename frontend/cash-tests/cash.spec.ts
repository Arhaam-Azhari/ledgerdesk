import { test, expect } from "@playwright/test";
import { readFile } from "node:fs/promises";
test("cash bridge, protected CSV text, date reset and mobile layout", async ({
  page,
  request,
}) => {
  const auth = {
    Authorization: `Basic ${Buffer.from("demo:demo-local-only").toString("base64")}`,
  };
  async function post(path: string, data: object, key: string) {
    const csrf = await request.get("/api/csrf").then((r) => r.json());
    const r = await request.post(path, {
      headers: {
        ...auth,
        [csrf.headerName]: csrf.token,
        "Idempotency-Key": key,
      },
      data,
    });
    const result = await r.json();
    expect(r.ok(), JSON.stringify(result)).toBe(true);
    return result.id;
  }
  const vendor = await post(
    "/api/vendors",
    { name: "Office supplier", email: "accounts@example.test" },
    "vendor",
  );
  await post(
    "/api/equity",
    {
      kind: "CONTRIBUTION",
      postedOn: "2026-09-30",
      memo: "Opening funding",
      amount: "100",
    },
    "funding",
  );
  await post(
    "/api/expenses",
    {
      vendorId: vendor,
      description: '=HYPERLINK("https://example.test","supplies")',
      spentOn: "2026-10-01",
      accountCode: "5000",
      amount: "25",
    },
    "expense",
  );
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto("/");
  await page.getByLabel("Username").fill("demo");
  await page.getByLabel("Password", { exact: true }).fill("demo-local-only");
  await page.getByRole("button", { name: "Open workspace" }).click();
  await page
    .getByRole("button", { name: "Cash activity", exact: true })
    .click();
  await page.getByLabel("Cash activity start").fill("2026-10-01");
  await page.getByLabel("Cash activity end").fill("2026-10-31");
  await page
    .getByRole("button", { name: "Run cash activity", exact: true })
    .click();
  await expect(
    page.getByRole("heading", {
      name: "Cash bridge · 2026-10-01 to 2026-10-31",
    }),
  ).toBeVisible();
  await expect(page.locator("dl")).toContainText("$100.00");
  await expect(page.locator("dl")).toContainText("$75.00");
  await expect(
    page
      .getByRole("table", { name: "Cash movements", exact: true })
      .locator("tbody tr"),
  ).toHaveCount(1);
  const download = page.waitForEvent("download");
  await page
    .getByRole("button", { name: "Download cash activity CSV" })
    .click();
  const file = await download;
  const path = await file.path();
  expect(path).not.toBeNull();
  const csv = await readFile(path!, "utf8");
  expect(file.suggestedFilename()).toBe(
    "ledgerdesk-cash-activity-2026-10-01-2026-10-31.csv",
  );
  expect(csv).toContain('"Opening cash","100.00"');
  expect(csv).toContain('"Closing cash","75.00"');
  expect(csv).toContain("\"'=HYPERLINK");
  expect(csv).toContain("Ledger entry ID");
  await page.screenshot({
    path: "cash-results/cash-activity.png",
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
    path: "cash-results/mobile-cash.png",
    fullPage: true,
  });
  await page.getByLabel("Cash activity start").fill("2026-11-01");
  await expect(
    page.getByRole("button", { name: "Download cash activity CSV" }),
  ).toHaveCount(0);
  await page.getByLabel("Cash activity end").fill("2026-11-30");
  await page
    .getByRole("button", { name: "Run cash activity", exact: true })
    .click();
  await expect(
    page.getByText("No recorded cash movements in this period."),
  ).toBeVisible();
});
