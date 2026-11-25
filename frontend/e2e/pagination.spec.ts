import { expect, test, type Page } from '@playwright/test';
import { randomUUID } from 'node:crypto';

const resumeText = 'Sample Candidate\nJava backend developer experienced with Spring Boot, PostgreSQL, and automated testing.';
const rawJd = 'Build Java and Spring Boot APIs, maintain PostgreSQL databases and work with the product team to test and ship new features.';

async function signIn(page: Page) {
  const response = await page.request.post('/api/v1/auth/dev', {
    data: { email: 'pagination-' + randomUUID() + '@example.test' },
  });
  expect(response.ok()).toBeTruthy();
  const csrf = (await page.context().cookies()).find(cookie => cookie.name === 'CF_CSRF');
  expect(csrf).toBeDefined();
  return { 'X-CSRF-Token': csrf!.value };
}

test('resume pagination starts after 30 and recovers when the last page is archived', async ({page}) => {
  const headers = await signIn(page);
  async function addResume(title: string) {
    const response = await page.request.post('/api/v1/resumes/text', {headers, data: {title, pastedText: resumeText}});
    expect(response.ok()).toBeTruthy();
    return (await response.json()).resumeId as string;
  }
  const olderResume = 'Older resume 100% Java';
  await addResume(olderResume);
  for (let i = 2; i <= 30; i++) await addResume('Current resume ' + i);

  await page.goto('/resumes');
  const cards = page.locator('.resume-card');
  const older = page.getByRole('button', {name: 'Older resumes'});
  const newer = page.getByRole('button', {name: 'Newer resumes'});
  await expect(cards).toHaveCount(30);
  await expect(older).toHaveCount(0);
  await expect(newer).toHaveCount(0);

  await addResume('Current resume 31');
  await page.reload();
  await older.click();
  await expect(cards).toHaveCount(1);
  await expect(cards).toContainText(olderResume);
  await expect(older).toHaveCount(0);
  await expect(newer).toBeVisible();
  const matching = await page.request.get('/api/v1/resumes/page', {params: {q: '100% java'}});
  expect((await matching.json()).items.map((item: {title: string}) => item.title)).toEqual([olderResume]);
  const literal = await page.request.get('/api/v1/resumes/page', {params: {q: '%_'}});
  expect((await literal.json()).items).toEqual([]);

  page.once('dialog', dialog => dialog.accept());
  await cards.getByRole('button', {name: 'Archive resume'}).click();
  await expect(cards).toHaveCount(30);
  await expect(page.getByText("You haven't uploaded a resume")).toHaveCount(0);
  await expect(newer).toHaveCount(0);
  const archivedMatch = await page.request.get('/api/v1/resumes/page', {params: {q: olderResume}});
  expect((await archivedMatch.json()).items).toEqual([]);
  await page.getByRole('button', {name: 'Restore', exact: true}).click();
  await older.click();
  await expect(cards).toContainText(olderResume);
});

test('search and paging locate older jobs and Tracker applications', async ({page, browser}) => {
  test.setTimeout(90_000);
  const headers = await signIn(page);
  const companyName = 'Cursor Test Company';
  const applications: {jobId: string; applicationId: string}[] = [];
  for (let i = 1; i <= 51; i++) {
    const title = i === 1 ? 'Archived History Engineer' : 'Current Engineer ' + i;
    const job = await page.request.post('/api/v1/jobs', {headers, data: {title, companyName, rawJd}});
    expect(job.status()).toBe(201);
    const {jobId} = await job.json();
    const application = await page.request.post('/api/v1/applications', {headers, data: {jobId}});
    expect(application.ok()).toBeTruthy();
    applications.push({jobId, applicationId: (await application.json()).id});
    if (i === 30) {
      const firstPage = await page.request.get('/api/v1/jobs/page');
      const data = await firstPage.json();
      expect(data.items).toHaveLength(30);
      expect(data.nextCursor).toBeNull();
    }
    if (i === 50) {
      const firstPage = await page.request.get('/api/v1/applications/page');
      const data = await firstPage.json();
      expect(data.items).toHaveLength(50);
      expect(data.nextCursor).toBeNull();
    }
  }

  await page.goto('/applications');
  const older = page.getByRole('button', {name: 'Older applications'});
  await expect(older).toBeVisible();
  await expect(page.getByRole('button', {name: 'Newer applications'})).toHaveCount(0);
  const applicationSearch = page.getByRole('searchbox', {name: 'Search applications'});
  await applicationSearch.fill('Archived History');
  const oldStage = page.getByLabel('Change status for Archived History Engineer');
  await expect(oldStage).toBeVisible();
  await expect(older).toHaveCount(0);
  await oldStage.selectOption('REJECTED');
  await page.getByRole('button', {name: 'View application'}).click();
  await expect(page.getByLabel('Application stage')).toHaveValue('REJECTED');
  await expect(page.getByRole('heading', {name: 'Application details'})).toBeFocused();

  const picker = page.getByRole('button', {name: /^Add a saved job/});
  await picker.click();
  const search = page.getByRole('combobox', {name: 'Search saved jobs'});
  await expect(search).toBeFocused();
  await page.getByRole('button', {name: 'Load more jobs'}).click();
  await expect(page.getByRole('option', {name: /Archived History Engineer/})).toBeVisible();
  await search.fill('Archived History');
  await expect(page.getByRole('option', {name: /Archived History Engineer/})).toBeVisible();
  await search.press('ArrowDown');
  await search.press('Enter');
  await expect(picker).toContainText('Archived History Engineer');
  await expect(picker).toBeFocused();

  await page.goto('/interviews?job=' + applications[0].jobId);
  const target = page.getByRole('button', {name: /^Target job/});
  await expect(target).toContainText('Archived History Engineer');
  await page.getByRole('button', {name: 'Generate interview questions'}).click();
  await expect(page.getByRole('heading', {name: 'Question bank'})).toBeVisible();
  await target.click();
  const interviewSearch = page.getByRole('combobox', {name: 'Search saved jobs'});
  await interviewSearch.fill('Current Engineer');
  await expect(page.getByRole('option', {name: /Current Engineer 51/})).toBeVisible();
  await interviewSearch.press('Escape');
  await expect(target).toContainText('Archived History Engineer');
  await expect(page.getByRole('heading', {name: 'Question bank'})).toBeVisible();
  const generate = page.getByRole('button', {name: 'Generate interview questions'});
  for (const status of [200, 200, 429]) {
    const response = page.waitForResponse(result => result.url().endsWith('/interview-questions/generate'));
    await generate.click();
    expect((await response).status()).toBe(status);
  }
  await expect(page.getByRole('alert')).toContainText('monthly limit for generating interview questions');
  await page.getByRole('button', {name: 'Open saved questions'}).click();
  await expect(page.getByRole('heading', {name: 'Question bank'})).toBeVisible();

  const secondUser = await browser.newPage({baseURL: test.info().project.use.baseURL});
  try {
    await signIn(secondUser);
    for (const path of ['/api/v1/jobs/page', '/api/v1/applications/page']) {
      const response = await secondUser.request.get(path, {params: {q: companyName}});
      expect((await response.json()).items).toEqual([]);
    }
  } finally {
    await secondUser.close();
  }
});
