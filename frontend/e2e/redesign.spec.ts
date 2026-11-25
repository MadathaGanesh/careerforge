import { expect, test } from '@playwright/test';
import { randomUUID } from 'node:crypto';

test('sign-in restores a protected link and query state', async ({ page, request }) => {
  const email = `return-${randomUUID()}@example.test`;
  const password = 'CareerForge test password 2026!';
  await page.goto('/register');
  await page.getByLabel('Full Name', { exact: false }).fill('CareerForge tester');
  await page.getByLabel('Email address').fill(email);
  await page.getByLabel(/^Password/).fill(password);
  await page.getByRole('button', { name: 'Create Account', exact: true }).click();
  await expect(page).toHaveURL(/verify-email/);

  const inbox = process.env.CF_TEST_MAILPIT ?? 'http://localhost:8025';
  let code = '';
  await expect.poll(async () => {
    const { messages } = await (await request.get(inbox + '/api/v1/messages')).json();
    const mail = messages.find((item: { Subject: string; To: { Address: string }[] }) =>
      item.Subject.includes('verification code') && item.To.some(to => to.Address === email));
    if (mail) {
      const item = await (await request.get(inbox + '/api/v1/message/' + mail.ID)).json();
      code = item.Text.match(/\b\d{6}\b/)?.[0] ?? '';
    }
    return code;
  }).not.toBe('');
  await page.getByLabel('Verification code').fill(code);
  await page.getByRole('button', { name: 'Verify email', exact: true }).click();
  await expect(page.getByText('Email verified. You can now sign in.')).toBeVisible();

  await page.goto('/settings?from=bookmark#security');
  await expect(page).toHaveURL(/\/login$/);
  await page.getByLabel('Email address').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Sign In', exact: true }).click();
  await expect(page).toHaveURL(/\/settings\?from=bookmark#security$/);
  await expect(page.getByRole('heading', { name: 'Settings', exact: true })).toBeVisible();
});

test('unsupported upload reports one error and leaves archive feedback clear', async ({ page }) => {
  expect((await page.request.post('/api/v1/auth/dev', { data: { email: `upload-${randomUUID()}@example.test` } })).ok()).toBeTruthy();
  await page.goto('/resumes');
  await page.locator('input[type="file"]').setInputFiles({
    name: 'unsupported.txt', mimeType: 'text/plain', buffer: Buffer.from('An unsupported fixture file'),
  });
  await expect(page.getByRole('alert')).toHaveCount(1);
  await expect(page.getByRole('alert')).toContainText(/pdf|docx/i);
  await expect(page.locator('section').filter({ has: page.getByRole('heading', { name: 'Archived resumes', exact: true }) }).getByRole('alert')).toHaveCount(0);
});

test('mark all read uses total unread count rather than the visible page', async ({ page }) => {
  test.setTimeout(110_000);
  expect((await page.request.post('/api/v1/auth/dev', { data: { email: `notices-${randomUUID()}@example.test` } })).ok()).toBeTruthy();
  await page.route('**/api/v1/notifications/page*', async route => {
    const url = new URL(route.request().url());
    url.searchParams.set('size', '1');
    await route.continue({ url: url.toString() });
  });
  await page.goto('/applications');
  for (const name of ['First reminder', 'Second reminder', 'Third reminder']) {
    await page.getByLabel('Reminder', { exact: true }).fill(name);
    const due = new Date(Date.now() + 20 * 60 * 1000);
    const local = new Date(due.getTime() - due.getTimezoneOffset() * 60_000);
    await page.getByLabel('When', { exact: true }).fill(local.toISOString().slice(0, 16));
    await page.getByRole('button', { name: 'Save reminder' }).click();
    await expect(page.getByText('Reminder saved.')).toBeVisible();
  }
  await expect.poll(async () => (await (await page.request.get('/api/v1/notifications/unread-count')).json()).count, { timeout: 75_000 }).toBe(3);
  await page.goto('/dashboard');
  await page.getByRole('button', { name: '3 unread notifications' }).click();
  await page.locator('.cf-notice').first().click();
  await expect(page).toHaveURL(/applications/);
  await page.getByRole('button', { name: '2 unread notifications' }).click();
  await page.getByRole('button', { name: 'Mark all as read' }).click();
  await expect(page.getByRole('button', { name: '0 unread notifications' })).toBeVisible();
  await page.getByRole('link', { name: 'Open inbox' }).click();
  await expect(page.getByRole('heading', { name: 'Notifications', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Unread', exact: true }).click();
  await expect(page.getByText('No unread notifications.')).toBeVisible();
});

test('job details open interview practice and Tracker before analysis, and search opens the matching application', async ({ page }) => {
  expect((await page.request.post('/api/v1/auth/dev', { data: { email: `jobs-${randomUUID()}@example.test` } })).ok()).toBeTruthy();
  await page.goto('/jobs/new');
  await page.getByLabel('Job title', { exact: true }).fill('Backend Engineer');
  await page.getByLabel('Company', { exact: true }).fill('Northstar Labs');
  await page.getByLabel('Job description', { exact: true }).fill('Build Java and Spring Boot APIs for a customer platform. Use PostgreSQL, integration tests and Docker. Work with a backend engineering team to review and ship changes.');
  await page.getByRole('button', { name: 'Save and extract job description' }).click();
  await page.getByRole('link', { name: 'Saved job', exact: true }).click();

  await page.getByRole('link', { name: 'Practice for this job' }).click();
  const job = page.getByRole('button', {name: /^Target job/});
  await expect(job).toContainText('Northstar Labs');
  await page.getByRole('button', { name: 'Generate interview questions' }).click();
  await expect(page.getByRole('heading', { name: 'Interview practice', exact: true })).toBeVisible();

  await page.goBack();
  await page.getByRole('link', { name: 'Track this job' }).click();
  await expect(page.getByRole('button', {name: /^Add a saved job/})).toContainText('Northstar Labs');
  await page.getByRole('button', { name: 'Add application' }).click();
  await expect(page.getByText('Application added.')).toBeVisible();

  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole('link', { name: 'Search workspace' }).click();
  await page.getByRole('searchbox', { name: 'Search jobs and applications' }).fill('Northstar');
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await page.getByRole('link', { name: 'Open application' }).click();
  await expect(page.getByLabel('Application stage')).toHaveValue('SAVED');
  await expect(page.getByRole('heading', { name: 'Application details' })).toBeVisible();
  await page.getByRole('link', { name: 'Compare compensation' }).click();
  await expect(page.getByRole('heading', { name: 'Compensation and offers' })).toBeVisible();
});
