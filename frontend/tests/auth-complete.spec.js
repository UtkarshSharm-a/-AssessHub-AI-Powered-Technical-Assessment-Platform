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

  test('User should login directly and reach dashboard without OTP', async ({ page }) => {
    await page.goto(BASE_URL);
    await page.waitForLoadState('networkidle');

    await page.getByPlaceholder(/name@company.com/i).fill(USER_EMAIL);
    await page.locator('input[type="password"]').fill(USER_PASSWORD);
    await page.getByRole('button', { name: /Sign In/i }).click();

    // Verify user reaches dashboard directly
    await expect(page.getByText(/Welcome back/i)).toBeVisible({ timeout: 15000 });
    await expect(page.getByRole('link', { name: /Dashboard/i })).toBeVisible();
  });

});