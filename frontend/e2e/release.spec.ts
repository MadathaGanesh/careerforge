import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { randomUUID } from 'node:crypto';

test('register with OTP, recover the password and export and delete the account', async ({page, request}) => {
  const user = `browser-release-${randomUUID()}@example.test`;
  const mailpit = process.env.CF_TEST_MAILPIT ?? 'http://localhost:8025';
  const initial = 'CareerForge Browser QA 123!';
  const next = 'CareerForge New Browser QA 123!';

  async function message(subject: string) {
    let body = '';
    await expect.poll(async () => {
      const inbox = await (await request.get(mailpit + '/api/v1/messages')).json();
      const hit = inbox.messages.find((mail: {Subject: string; To: {Address: string}[]}) =>
        mail.Subject.includes(subject) && mail.To.some(to => to.Address === user));
      if (!hit) return '';
      const full = await (await request.get(mailpit + '/api/v1/message/' + hit.ID)).json();
      body = full.Text;
      return body;
    }).not.toBe('');
    return body;
  }

  await page.goto('/register');
  await page.getByLabel('Full Name', {exact: false}).fill('Browser Release Candidate');
  await page.getByLabel('Email address').fill(user);
  await page.getByLabel(/^Password/).fill(initial);
  await page.getByRole('button', {name: 'Create Account', exact: true}).click();
  await expect(page.getByRole('heading', {name: 'Verify your email'})).toBeVisible();
  const otp = (await message('verification code')).match(/\b\d{6}\b/)?.[0];
  expect(otp).toBeTruthy();
  await page.getByLabel('Verification code').fill(otp!);
  await page.getByRole('button', {name: 'Verify email'}).click();
  await page.getByRole('link', {name: 'Continue to Sign In'}).click();
  await page.goto('/forgot-password');
  await page.getByLabel('Email address').fill(user);
  await page.getByRole('button', {name: /send.*reset/i}).click();
  const reset = (await message('password reset')).match(/[a-f0-9-]{36}\.[a-f0-9-]{36}/)?.[0];
  expect(reset).toBeTruthy();
  await page.getByLabel(/reset token/i).fill(reset!);
  await page.getByLabel(/^New Password/i).fill(next);
  await page.getByRole('button', {name: /reset password/i}).click();
  await expect(page.getByText(/password reset successfully|password was reset|password has been reset/i)).toBeVisible();
  await page.goto('/login');
  await page.getByLabel('Email address').fill(user);
  await page.getByLabel('Password', {exact: true}).fill(next);
  await page.getByRole('button', {name: 'Sign In', exact: true}).click();
  await expect(page).toHaveURL(/dashboard|onboarding/);
  await page.goto('/settings');
  const archive = page.waitForEvent('download');
  await page.getByRole('button', {name: 'Export my data'}).click();
  await expect((await archive).suggestedFilename()).toBe('careerforge-data.zip');
  await page.getByLabel('Type DELETE MY ACCOUNT').fill('DELETE MY ACCOUNT');
  await page.getByRole('button', {name: 'Delete my account'}).click();
  await expect(page).toHaveURL('/');
  const session = await (await page.request.get('/api/v1/auth/session')).json();
  expect(session.authenticated).toBe(false);
});

for (const width of [360, 390]) {
  test(`workspace has accessible controls and no horizontal page overflow at ${width}px`, async ({page}) => {
    await page.setViewportSize({width, height: 844});
    const response = await page.request.post('/api/v1/auth/dev', {
      data: { email: `mobile-${randomUUID()}@example.test` },
    });
    expect(response.ok()).toBeTruthy();
    for (const [path, title] of [
      ['/dashboard', /welcome back/i], ['/resumes', 'My Resumes'], ['/jobs', 'Saved Jobs'],
      ['/interviews', 'Interview Lab'], ['/applications', 'Job Tracker'],
      ['/salaries', 'Compensation and offers'], ['/settings', 'Settings'],
    ] as const) {
      await page.goto(path);
      const main = page.locator('main');
      await expect(main.getByRole('heading', {name: title, exact: true})).toBeVisible();
      const layout = await page.evaluate(() => ({
        width: document.documentElement.scrollWidth,
        overflow: [...document.querySelectorAll('header, main, main *')]
          .filter(el => el.getBoundingClientRect().right > window.innerWidth + 1)
          .slice(0, 12).map(el => String(el.className)),
      }));
      expect(layout.width, `${path}: ${layout.overflow.join(', ')}`).toBeLessThanOrEqual(width);
      const results = await new AxeBuilder({page}).include('body')
        .withRules(['button-name', 'label', 'link-name', 'aria-allowed-attr', 'aria-required-attr', 'aria-valid-attr-value'])
        .analyze();
      expect(results.violations, path).toEqual([]);
    }
  });
}

test('page through applications, record an offer, save a reminder and keep private salary notes', async ({page}) => {
  const login = await page.request.post('/api/v1/auth/dev', {
    data: { email: `tracker-${randomUUID()}@example.test` },
  });
  expect(login.ok()).toBeTruthy();
  const csrf = (await page.context().cookies()).find(cookie => cookie.name === 'CF_CSRF');
  expect(csrf).toBeDefined();
  const headers = { 'X-CSRF-Token': csrf!.value };
  const rawJd = 'The role requires Java backend engineering, API design, PostgreSQL query optimization and automated testing.';
  for (const title of ['Platform Engineer', 'Application Engineer']) {
    const job = await page.request.post('/api/v1/jobs', { headers, data: { title, rawJd, companyName: 'Example Co' } });
    expect(job.status()).toBe(201);
    const { jobId } = await job.json();
    if (title === 'Platform Engineer') {
      const application = await page.request.post('/api/v1/applications', { headers, data: { jobId } });
      expect(application.ok()).toBeTruthy();
    }
  }

  await page.route(/\/api\/v1\/applications\/page(?:\?|$)/, route => {
    const url = new URL(route.request().url());
    url.searchParams.set('size', '1');
    return route.continue({ url: url.toString() });
  });
  await page.goto('/applications');
  await expect(page.getByRole('heading', {name: 'Job Tracker'})).toBeVisible();
  const add = page.getByRole('button', {name: 'Add application'});
  await expect(add).toBeDisabled();
  await page.getByRole('button', {name: /^Add a saved job/}).click();
  await page.getByRole('option', {name: /Example Co — Application Engineer/}).click();
  await add.click();
  await expect(page.getByText('Application added.')).toBeVisible();
  await page.getByRole('button', {name: 'Older applications'}).click();
  await expect(page.getByLabel('Change status for Platform Engineer')).toBeVisible();
  await page.getByRole('button', {name: 'Newer applications'}).click();
  const stage = page.getByLabel('Change status for Application Engineer');
  await stage.selectOption('OFFER');
  await expect(page.getByText('Application moved to offer.')).toBeVisible();
  await expect(stage).toHaveValue('OFFER');
  await page.getByRole('button', {name: /notes and salary/i}).click();
  await page.getByLabel('Notes', {exact: true}).fill('Follow up about remote work.');
  await page.getByLabel('Offer details / salary').fill('INR 20L');
  await page.getByRole('button', {name: 'Save details'}).click();
  await expect(page.getByText('Application details saved.')).toBeVisible();

  await page.getByRole('button', {name: /notes and salary/i}).click();
  await page.getByLabel('Reminder', {exact: true}).fill('Follow up with Example Co');
  await page.getByLabel('When', {exact: true}).fill('2090-05-16T11:00');
  await page.getByRole('button', {name: 'Save reminder'}).click();
  await expect(page.getByText('Reminder saved.')).toBeVisible();
  await page.goto('/dashboard');
  await expect(page.getByText('Follow up with Example Co')).toBeVisible();
  await page.goto('/salaries');
  await expect(page.getByText(/Application Engineer: INR 20L/)).toBeVisible();

  await page.getByText('Personal salary notes', {exact: true}).click();
  await expect(page.getByRole('heading', {name: 'Saved salary notes'})).toBeVisible();
  await expect(page.getByRole('button', {name: 'Save salary note'})).toBeHidden();
  await page.getByText('Add salary note', {exact: true}).click();
  const salary = page.locator('form').filter({has: page.getByRole('button', {name: 'Save salary note'})});
  await salary.getByLabel('Role', {exact: true}).fill('Platform Engineer');
  await salary.getByLabel('Location', {exact: true}).fill('Hyderabad');
  await salary.getByLabel('Low annual compensation').fill('800000');
  await salary.getByLabel('High annual compensation').fill('1000000');
  await salary.getByLabel('Source label').fill('Written offer');
  await page.getByRole('button', {name: 'Save salary note'}).click();
  await expect(page.getByRole('cell', {name: 'Written offer'})).toBeVisible();
});
