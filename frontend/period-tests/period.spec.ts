import { test, expect } from "@playwright/test";

test("review, close, retry and reopen an accounting period with retained reports", async ({
  page,
  request,
}) => {
  const auth = {
    Authorization: `Basic ${Buffer.from("demo:demo-local-only").toString("base64")}`,
  };
  async function post(path: string, data: object, key: string) {
    const csrf = await request.get("/api/csrf").then((r) => r.json());
    return request.post(path, {
      headers: {
        ...auth,
        [csrf.headerName]: csrf.token,
        "Idempotency-Key": key,
      },
      data,
    });
  }
  async function state() {
    const response = await request.get("/api/state", { headers: auth });
    expect(response.ok()).toBe(true);
    return response.json();
  }
  expect(
    (
      await post(
        "/api/opening-bank-balance",
        {
          asOf: "2026-09-30",
          balance: "1000.25",
          memo: "Cleared opening for period review",
        },
        "period-opening",
      )
    ).ok(),
  ).toBe(true);
  await page.goto("/");
  await page.getByLabel("Username").fill("demo");
  await page.getByLabel("Password", { exact: true }).fill("demo-local-only");
  await page.getByRole("button", { name: "Open workspace" }).click();
  await page.getByRole("button", { name: "Period close", exact: true }).click();
  await page.getByLabel("Accounting period end").fill("2026-10-30");
  await page.getByRole("button", { name: "Preview accounting period" }).click();
  await expect(page.getByRole("alert")).toContainText("month-end");
  await page.getByLabel("Accounting period end").fill("2026-10-31");
  await page.getByRole("button", { name: "Preview accounting period" }).click();
  await expect(
    page.getByText("Close the bank statement ending at this month-end."),
  ).toBeVisible();
  await page
    .getByLabel("Period review note")
    .fill("Reviewed bank statement and supporting documents");
  await expect(
    page.getByRole("button", { name: "Close accounting period", exact: true }),
  ).toBeDisabled();
  const statement = {
    startsOn: "2026-10-01",
    endsOn: "2026-10-31",
    openingBalance: "1000.25",
    closingBalance: "1000.25",
  };
  const bank = await post(
    "/api/bank/reconciliations",
    statement,
    "period-bank",
  );
  expect(bank.ok()).toBe(true);
  const bankId = (await bank.json()).id;
  await page.getByRole("button", { name: "Reload workspace" }).click();
  await page.getByRole("button", { name: "Preview accounting period" }).click();
  await expect(
    page.getByText("The prerequisites and report calculations pass."),
  ).toBeVisible();
  await expect(
    page.getByRole("row").filter({ hasText: "Net profit" }),
  ).toContainText("$0.00");
  await expect(
    page.getByRole("row").filter({ hasText: "Assets" }),
  ).toContainText("$1,000.25");
  await page.screenshot({
    path: "period-results/period-review.png",
    fullPage: true,
  });
  page.once("dialog", (d) => d.dismiss());
  await page
    .getByRole("button", { name: "Close accounting period", exact: true })
    .click();
  expect((await state()).accountingPeriodCloses).toHaveLength(0);
  let failRefresh = true;
  await page.route("**/api/state", async (route) => {
    if (failRefresh) {
      failRefresh = false;
      await route.abort();
    } else await route.continue();
  });
  page.on("dialog", (d) => d.accept());
  await page
    .getByRole("button", { name: "Close accounting period", exact: true })
    .click();
  await expect(page.getByRole("alert")).toBeVisible();
  expect((await state()).accountingPeriodCloses).toHaveLength(1);
  await expect(page.getByLabel("Period review note")).toHaveValue(
    "Reviewed bank statement and supporting documents",
  );
  await page
    .getByRole("button", { name: "Close accounting period", exact: true })
    .click();
  await expect(page.getByRole("status")).toHaveText(
    "Accounting period closed. The reviewed reports are retained.",
  );
  await page.unroute("**/api/state");
  const saved = await state();
  expect(saved.accountingPeriodCloses).toHaveLength(1);
  expect(saved.ledger).toHaveLength(2);
  expect(
    saved.audit.filter(
      (a: { action: string }) => a.action === "ACCOUNTING_PERIOD_CLOSED",
    ),
  ).toHaveLength(1);
  const retained = saved.accountingPeriodCloses[0].snapshot;
  const history = page.getByRole("region", {
    name: "Saved accounting periods",
  });
  await history.getByText("View retained period reports").click();
  await expect(
    history.getByRole("row").filter({ hasText: "Closing cash" }),
  ).toContainText("$1,000.25");
  await page.screenshot({
    path: "period-results/period-closed.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "period-results/period-mobile.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 1280, height: 900 });
  expect(
    (
      await post(
        `/api/bank/reconciliations/${bankId}/reopen`,
        { version: 1, reason: "Protected by accounting close" },
        "period-bank-blocked",
      )
    ).status(),
  ).toBe(400);
  await history
    .getByRole("button", { name: "Reopen latest accounting period" })
    .click();
  await page
    .getByLabel("Accounting reopening reason")
    .fill("Recheck supporting evidence");
  failRefresh = true;
  await page.route("**/api/state", async (route) => {
    if (failRefresh) {
      failRefresh = false;
      await route.abort();
    } else await route.continue();
  });
  await page
    .getByRole("button", { name: "Confirm accounting reopening" })
    .click();
  await expect(page.getByRole("alert")).toBeVisible();
  await expect(page.getByLabel("Accounting reopening reason")).toHaveValue(
    "Recheck supporting evidence",
  );
  await page
    .getByRole("button", { name: "Confirm accounting reopening" })
    .click();
  await expect(page.getByRole("status")).toHaveText(
    "Accounting period reopened. Its original reports remain in history.",
  );
  await page.unroute("**/api/state");
  await expect(history).toContainText("Recheck supporting evidence");
  expect((await state()).accountingPeriodCloses[0].snapshot).toBe(retained);
  await page.getByRole("button", { name: "Preview accounting period" }).click();
  await page
    .getByLabel("Period review note")
    .fill("Review completed after checking evidence");
  await page
    .getByRole("button", { name: "Close accounting period", exact: true })
    .click();
  await expect(page.getByRole("status")).toHaveText(
    "Accounting period closed. The reviewed reports are retained.",
  );
  expect((await state()).accountingPeriodCloses).toHaveLength(2);
  expect((await state()).ledger).toEqual(saved.ledger);
  await page.getByRole("button", { name: "Lock workspace" }).click();
  await page.getByLabel("Username").fill("period-reviewer");
  await page
    .getByLabel("Password", { exact: true })
    .fill("period-reviewer-password");
  await page.getByRole("button", { name: "Open workspace" }).click();
  await page.getByRole("button", { name: "Period close", exact: true }).click();
  await expect(history.locator("article")).toHaveCount(2);
  await expect(
    page.getByRole("button", { name: "Reopen latest accounting period" }),
  ).toHaveCount(0);
  await page.getByLabel("Accounting period end").fill("2026-11-30");
  await page.getByRole("button", { name: "Preview accounting period" }).click();
  await expect(
    page.getByText("Accounting review · 2026-11-01 to 2026-11-30"),
  ).toBeVisible();
  await expect(page.getByLabel("Period review note")).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "Close accounting period", exact: true }),
  ).toHaveCount(0);
  await page.screenshot({
    path: "period-results/period-reviewer.png",
    fullPage: true,
  });
});
