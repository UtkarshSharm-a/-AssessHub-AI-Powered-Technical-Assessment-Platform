// @ts-check
import { test, expect } from '@playwright/test';

const BASE_URL = 'http://localhost:5173/';
const BACKEND_URL = 'http://localhost:8080';

const USER_EMAIL = 'utkarshsharma8369@gmail.com';
const USER_PASSWORD = '123456'; // use your actual correct password

test.describe('AssessHub Complete Auth Flow', () => {

  test('Login page should open successfully', async ({ page }) => {
    await page.goto(BASE_URL);
    await page.waitForLoadState('networkidle');

    await expect(page.getByRole('heading', { name: /Welcome Back/i })).toBeVisible();
    await expect(page.getByPlaceholder(/name@company.com/i)).toBeVisible();
    await expect(page.locator('input[type="password"]')).toBeVisible();
    await expect(page.getByRole('button', { name: /Sign In/i })).toBeVisible();
  });

  test('User should navigate to signup page from login page', async ({ page }) => {
    await page.goto(BASE_URL);
    await page.waitForLoadState('networkidle');

    await page.getByRole('link', { name: /Create Account/i }).click();

    await expect(page.getByRole('heading', { name: /Create Account/i })).toBeVisible();
    await expect(page.getByRole('button', { name: /Register/i })).toBeVisible();
  });

  test('Signup form fields should be visible', async ({ page }) => {
    await page.goto(BASE_URL);
    await page.waitForLoadState('networkidle');

    await page.getByRole('link', { name: /Create Account/i }).click();

    await expect(page.getByRole('heading', { name: /Create Account/i })).toBeVisible();
    await expect(page.getByPlaceholder(/John Doe/i)).toBeVisible();
    await expect(page.getByPlaceholder(/you@example.com/i)).toBeVisible();
    await expect(page.getByPlaceholder(/Min 6 characters/i)).toBeVisible();
    await expect(page.getByPlaceholder(/Lead's name/i)).toBeVisible();
    await expect(page.locator('select[name="teamId"]')).toBeVisible();
    await expect(page.locator('select[name="role"]')).toBeVisible();
    await expect(page.getByRole('button', { name: /Register/i })).toBeVisible();
  });

  test('User should login and reach OTP verification page', async ({ page }) => {
    await page.goto(BASE_URL);
    await page.waitForLoadState('networkidle');

    await page.getByPlaceholder(/name@company.com/i).fill(USER_EMAIL);
    await page.locator('input[type="password"]').fill(USER_PASSWORD);
    await page.getByRole('button', { name: /Sign In/i }).click();

    await expect(page.getByRole('heading', { name: /OTP Verification/i }))
      .toBeVisible({ timeout: 15000 });

    await expect(page.getByPlaceholder(/Enter 6-digit code/i)).toBeVisible();
    await expect(page.getByRole('button', { name: /Verify/i })).toBeVisible();
  });

  test('OTP verification page should display correct UI', async ({ page }) => {
    await page.goto(BASE_URL);
    await page.waitForLoadState('networkidle');

    await page.getByPlaceholder(/name@company.com/i).fill(USER_EMAIL);
    await page.locator('input[type="password"]').fill(USER_PASSWORD);
    await page.getByRole('button', { name: /Sign In/i }).click();

    await expect(page.getByRole('heading', { name: /OTP Verification/i }))
      .toBeVisible({ timeout: 15000 });

    await expect(page.getByText(/Enter the code sent to/i)).toBeVisible();
    await expect(page.getByText(/OTP Code/i)).toBeVisible();
    await expect(page.getByPlaceholder(/Enter 6-digit code/i)).toBeVisible();
    await expect(page.getByRole('button', { name: /Verify/i })).toBeVisible();
  });

  test('User should login, auto-fetch OTP and reach dashboard', async ({ page, request }) => {
    await page.goto(BASE_URL);
    await page.waitForLoadState('networkidle');

    // Step 1: Login
    await page.getByPlaceholder(/name@company.com/i).fill(USER_EMAIL);
    await page.locator('input[type="password"]').fill(USER_PASSWORD);
    await page.getByRole('button', { name: /Sign In/i }).click();

    // Step 2: Wait for OTP page
    await expect(page.getByRole('heading', { name: /OTP Verification/i }))
      .toBeVisible({ timeout: 15000 });

    // Step 3: Fetch latest OTP from backend test API
    const otpResponse = await request.get(
      `${BACKEND_URL}/api/test/otp?email=${encodeURIComponent(USER_EMAIL)}`
    );

    expect(otpResponse.ok()).toBeTruthy();

    const otpData = await otpResponse.json();
    const otp = otpData.otp;

    console.log('Fetched OTP:', otp);

    // Step 4: Fill OTP automatically
    await page.getByPlaceholder(/Enter 6-digit code/i).fill(String(otp));
    await page.getByRole('button', { name: /Verify/i }).click();

    // Step 5: Verify dashboard
    await expect(page.getByText(/Welcome back/i)).toBeVisible({ timeout: 15000 });
    await expect(page.getByRole('link', { name: /Dashboard/i })).toBeVisible();
  });

});