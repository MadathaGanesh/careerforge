import { expect, test } from '@playwright/test';
import { randomUUID } from 'node:crypto';

test('a missing Google configuration leaves email sign-in available', async ({page}) => {
  await page.route('**/api/v1/auth/options', route => route.fulfill({json: {googleClientId: ''}}));
  await page.goto('/login');
  await expect(page.getByText('Google sign-in is unavailable on this installation.')).toBeVisible();
  await expect(page.getByLabel('Email address')).toBeVisible();
});

test('Google uses the server client ID, signs in and restores the requested page', async ({page}) => {
  const credential = process.env.CF_QA_BROWSER_GOOGLE_TOKEN;
  test.skip(!credential, 'Requires a configured local Google token fixture');
  const options = await page.request.get('/api/v1/auth/options');
  const {googleClientId} = await options.json();
  expect(googleClientId).toBeTruthy();
  await page.route('https://accounts.google.com/gsi/client', route => route.fulfill({
    contentType: 'application/javascript',
    body: [
      'window.google = { accounts: { id: {',
      'initialize: options => { window.fixtureIdentity = options; },',
      'renderButton: (target, config) => {',
      'const button = document.createElement("button");',
      'button.textContent = config.text === "signin_with" ? "Sign in with Google" : "Other Google action";',
      'button.dataset.clientId = window.fixtureIdentity.client_id;',
      'button.onclick = () => window.fixtureIdentity.callback({credential: ' + JSON.stringify(credential) + '});',
      'target.replaceChildren(button);',
      '}, cancel() {} }}};',
    ].join('\n'),
  }));
  await page.goto('/settings?from=google#security');
  const google = page.getByRole('button', {name: 'Sign in with Google', exact: true});
  await expect(google).toHaveAttribute('data-client-id', googleClientId);
  const signIn = page.waitForResponse(response => response.url().endsWith('/api/v1/auth/google'));
  await google.click();
  expect((await signIn).status()).toBe(200);
  await expect(page).toHaveURL(/\/settings\?from=google#security$/);
});

test('salary research shows the source-backed estimate, contextual input and poor evidence state', async ({page}) => {
  test.skip(process.env.CF_QA_SALARY_FIXTURE !== 'true', 'Requires a configured search fixture');
  const login = await page.request.post('/api/v1/auth/dev', {
    data: {email: 'salary-' + randomUUID() + '@example.test'},
  });
  expect(login.ok()).toBeTruthy();
  const csrf = (await page.context().cookies()).find(cookie => cookie.name === 'CF_CSRF')!.value;
  const job = await page.request.post('/api/v1/jobs', {
    headers: {'X-CSRF-Token': csrf},
    data: {
      title: 'Java Backend Developer', companyName: 'Example Co', location: 'Hyderabad', salaryDisclosed: '₹9 LPA',
      rawJd: 'We are hiring a Java backend developer who will design REST APIs and maintain production services.',
    },
  });
  expect(job.status()).toBe(201);
  const {jobId} = await job.json();

  await page.setViewportSize({width: 390, height: 850});
  await page.route('**/api/v1/salary-research/capabilities', route => route.abort());
  await page.goto('/salaries?job=' + jobId);
  const form = page.getByRole('form', {name: 'Salary research'});
  await expect(form.getByLabel('Job title', {exact: true})).toHaveValue('Java Backend Developer');
  await expect(form.getByLabel('Company', {exact: true})).toHaveValue('Example Co');
  await expect(form.getByLabel('Location', {exact: true})).toHaveValue('Hyderabad');
  await expect(page.getByText('Advertised salary: ₹9 LPA')).toBeVisible();
  await form.getByLabel('Experience from (years)').fill('5');
  await form.getByLabel('Experience to (years)').fill('4');
  const search = page.getByRole('button', {name: 'Search salary sources'});
  await expect(search).toBeDisabled();
  await form.getByLabel('Experience from (years)').fill('3');
  await search.click();
  await expect(page.getByRole('heading', {name: '₹8–10 LPA'})).toBeVisible();
  await expect(page.getByRole('link', {name: 'AmbitionBox'})).toHaveAttribute('href', /ambitionbox\.com/);
  await expect(page.getByRole('link', {name: 'Glassdoor'})).toHaveAttribute('href', /glassdoor\.co\.in/);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  await form.getByLabel('Location', {exact: true}).fill('Pune');
  await search.click();
  await expect(page.getByText(/Two independent sources with comparable annual figures are needed/)).toBeVisible();
  await expect(page.getByRole('link', {name: 'Search on Google'})).toBeVisible();
  await form.getByLabel('Company', {exact: true}).fill('Quota Fixture');
  await search.click();
  await expect(page.getByText(/SerpApi reached a search limit/)).toBeVisible();
  await form.getByLabel('Company', {exact: true}).fill('Rejected Key Fixture');
  await search.click();
  await expect(page.getByText(/SerpApi rejected the configured key/)).toBeVisible();
  await expect(form.getByLabel('Location', {exact: true})).toHaveValue('Pune');
  await form.getByLabel('Company', {exact: true}).fill('Genpact');
  await form.getByLabel('Job title', {exact: true}).fill('Consultant');
  await form.getByLabel('Location', {exact: true}).fill('Hyderabad');
  await form.getByLabel('Experience to (years)').fill('5');
  await search.click();
  await expect(page.getByRole('heading', {name: 'Related benchmarks and salary pages'})).toBeVisible();
  await expect(page.getByRole('link', {name: 'indeed.com'})).toHaveAttribute('href', /indeed\.com/);
  await expect(page.getByText('₹8.58 LPA', {exact: true})).toBeVisible();
  await expect(page.getByText('Experience band unconfirmed', {exact: true})).toBeVisible();
  await expect(page.getByText('Requested city unconfirmed · Broader experience band', {exact: true})).toBeVisible();
  await expect(page.getByText('Used in estimate', {exact: true})).toHaveCount(0);
  await page.getByText('Search coverage · 2 of 4 completed', {exact: true}).click();
  await expect(page.getByText('AmbitionBox · Unavailable', {exact: true})).toBeVisible();
  await expect(page.getByText('Naukri · Search limit reached', {exact: true})).toBeVisible();
  await expect(search).toBeEnabled();
});

test('a valid search is clickable without a provider key and gives setup guidance', async ({page}) => {
  const login = await page.request.post('/api/v1/auth/dev', {
    data: {email: 'no-salary-provider-' + randomUUID() + '@example.test'},
  });
  expect(login.ok()).toBeTruthy();
  const config = await page.request.get('/api/v1/salary-research/capabilities');
  expect(config.ok()).toBeTruthy();
  test.skip((await config.json()).onlineSearch, 'Requires a backend without a SerpApi key');
  await page.goto('/salaries');
  const form = page.getByRole('form', {name: 'Salary research'});
  await form.getByLabel('Job title').fill('Java Backend Developer');
  await form.getByLabel('Company').fill('Google');
  await form.getByLabel('Location').fill('Hyderabad');
  await form.getByLabel('Experience from (years)').fill('2');
  await form.getByLabel('Experience to (years)').fill('4');
  const search = form.getByRole('button', {name: 'Search salary sources'});
  await expect(search).toBeEnabled();
  const lookup = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/salary-research');
  await search.click();
  expect((await (await lookup).json()).status).toBe('UNCONFIGURED');
  await expect(page.getByText('Salary search needs a SerpApi key on the server.')).toBeVisible();
  await page.getByText('Local setup', {exact: true}).click();
  await expect(page.getByText('SERPAPI_API_KEY', {exact: true})).toBeVisible();
  await expect(form.getByLabel('Location')).toHaveValue('Hyderabad');
  await expect(search).toBeEnabled();
});
