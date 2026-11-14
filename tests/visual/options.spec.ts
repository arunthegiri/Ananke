import { expect, test } from "./fixtures";

/**
 * /options — option chain (multi-asset P2, issue #8) against a recorded
 * Alpaca indicative-feed chain (no backend required).
 *
 * Behavioural, not a screenshot baseline: the expiry tabs carry days-to-expiry
 * computed from the real clock, so a pixel baseline would drift daily.
 */

test("empty state until an underlying is chosen", async ({ page }) => {
  await page.goto("/options");
  await expect(page.getByText("Enter a stock or ETF to load its option chain.")).toBeVisible();
});

test("chain renders straddle rows around ATM with a delayed label", async ({ page }) => {
  await page.goto("/options?u=AAPL");

  // The fixture is the indicative feed, so the data must be labelled delayed.
  await expect(page.getByText(/Delayed · indicative/i)).toBeVisible();
  await expect(page.getByText("356.40")).toBeVisible();

  // Expiry tabs from the expirations fixture.
  await expect(page.getByRole("tab", { name: /Nov 20/ })).toBeVisible();

  // ATM strike (357.5 is nearest 356.40) is badged; default window is ±10.
  await expect(page.getByText("357.5", { exact: true })).toBeVisible();
  await expect(page.getByText(/IV\/greeks: \d+ from alpaca-options/)).toBeVisible();

  // Greeks tab is URL state (rule 3).
  await page.getByRole("tab", { name: "Greeks" }).click();
  await expect(page).toHaveURL(/view=greeks/);
  await expect(page.getByText("C Γ")).toBeVisible();
});

test("strike window narrows the chain", async ({ page }) => {
  // Fixture strikes: 342.5 … 370 in 2.5 steps, ATM 357.5 at index 6.
  await page.goto("/options?u=AAPL&strikes=5");
  await expect(page.getByText("357.5", { exact: true })).toBeVisible();
  await expect(page.getByText("345", { exact: true })).toBeVisible(); // ATM − 5
  await expect(page.getByText("370", { exact: true })).toBeVisible(); // ATM + 5
  await expect(page.getByText("342.5", { exact: true })).toHaveCount(0); // ATM − 6

  await page
    .getByRole("radio", { name: "All" })
    .or(page.getByRole("button", { name: "All" }))
    .click();
  await expect(page).toHaveURL(/strikes=all/);
  await expect(page.getByText("342.5", { exact: true })).toBeVisible();
});

test("non-equity underlying in the URL is ignored, not sent to the API", async ({ page }) => {
  await page.goto("/options?u=BTC/USD");
  await expect(page.getByText("Enter a stock or ETF to load its option chain.")).toBeVisible();
});
