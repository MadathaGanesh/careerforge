import { test, expect, type Page } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';

const resume = 'Sample Candidate\nJava Backend Developer with 3 years of experience.\nDeveloped REST APIs using Java, Spring Boot and PostgreSQL.';
const jd = 'We are hiring a Java Backend Developer to develop scalable REST APIs. Required: Java, Spring Boot, PostgreSQL, CI/CD, Docker and Kafka. You will implement APIs, design databases, review code and maintain testing.';

async function signIn(page: Page) {
  const response = await page.request.post('/api/v1/auth/dev', {
    data: { email: `browser-${randomUUID()}@example.test` },
  });
  expect(response.ok()).toBeTruthy();
}

test('review, analyze, tailor, download and revisit a saved job', async ({ page }) => {
  await signIn(page);
  await page.goto('/resumes');
  await page.getByText('Or paste your resume text').click();
  await page.getByLabel('Resume title').fill('Browser Test Resume');
  await page.getByLabel('Resume text').fill(resume);
  await page.getByRole('button', { name: 'Save pasted resume' }).click();
  await expect(page.getByRole('heading', { name: 'Review extracted resume' })).toBeVisible();
  await page.getByRole('checkbox', { name: /I confirm the text/ }).check();
  await page.getByRole('button', { name: 'Confirm reviewed version' }).click();
  await expect(page.getByText('Reviewed version confirmed.')).toBeVisible();
  await page.getByRole('link', { name: /Next: analyze a job/ }).click();
  await page.getByLabel('Job description', { exact: true }).fill(jd);
  await page.getByLabel('Company', { exact: true }).fill('Example Company');
  await page.getByLabel('Job title', { exact: true }).fill('Backend Engineer');
  await page.getByRole('button', { name: 'Save and extract job description' }).click();
  await expect(page.getByText('Job saved', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Analyze job fit' }).click();
  await expect(page.getByText('Requirement-by-requirement evidence')).toBeVisible();
  await page.reload();
  await expect(page.getByText('Demonstrated', { exact: true }).first()).toBeVisible();
  const tailoring = page.getByRole('button', { name: 'Generate tailoring proposals', exact: true });
  for (const width of [320, 390]) {
    await page.setViewportSize({ width, height: 844 });
    await expect(tailoring).toBeVisible();
    const action = await tailoring.boundingBox();
    expect(action!.x + action!.width).toBeLessThanOrEqual(width);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    await expect(page.getByRole('link', { name: 'Search workspace' })).toBeVisible();
  }
  await page.setViewportSize({ width: 1440, height: 900 });
  await tailoring.click();
  await expect(page.getByRole('heading', { name: /Resume Studio/ })).toBeVisible();
  await page.getByRole('textbox', {name: /Edit proposed text/}).first().fill('Sample Candidate, backend engineer');
  await page.getByRole('button', { name: 'Save and accept' }).click();

  await expect(page.getByRole('button', { name: 'Export DOCX', exact: true })).toHaveCount(0);
  const jake = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download Overleaf .tex' }).click();
  const jakeFile = await jake;
  expect(jakeFile.suggestedFilename()).toMatch(/\.tex$/);
  expect(await jakeFile.failure()).toBeNull();
  const source = await readFile((await jakeFile.path())!, 'utf8');
  expect(source).toContain("Based on Jake's Resume");
  expect(source).toContain('Sample Candidate, backend engineer');
  const capability = await (await page.request.get('/api/v1/export-capabilities')).json();
  if (process.env.CF_REQUIRE_JAKE_PDF === 'true') expect(capability.jakePdf).toBe(true);
  if (capability.jakePdf) {
    const jakePdf = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Download Jake PDF', exact: true }).click();
    const file = await jakePdf;
    expect(file.suggestedFilename()).toBe('CareerForge_Jakes_Tailored_Resume.pdf');
    expect((await readFile((await file.path())!)).subarray(0, 5).toString()).toBe('%PDF-');
  } else {
    await expect(page.getByRole('button', { name: 'Download Jake PDF', exact: true })).toBeDisabled();
    await expect(page.getByText('Jake’s Overleaf source is available above.', { exact: false })).toBeVisible();
  }
  await page.getByText('Resume evaluation and recruiter draft', { exact: true }).click();
  await expect(page.getByText('Original resume vs. tailored resume', {exact: true})).toBeVisible();
  await page.goto('/jobs');
  await page.getByRole('link', {name: 'Backend Engineer', exact: true}).click();
  await expect(page.getByRole('heading', {name: 'Analysis history'})).toBeVisible();
  await expect(page.getByRole('link', {name: 'Continue tailoring'})).toBeVisible();
  await page.getByRole('link', {name: 'View exports for this job'}).click();
  await expect(page.getByRole('heading', {name: 'Export history'})).toBeVisible();
  await expect(page.getByRole('button', {name: 'Download', exact: true})).toHaveCount(capability.jakePdf ? 2 : 1);
  await page.getByRole('link', {name: 'Back to job'}).click();
  await page.getByRole('link', {name: 'Analyze this saved job'}).click();
  await expect(page.getByText('Job saved', {exact: true})).toBeVisible();

});

test('switching interview jobs creates a new session and saves its answer', async ({page}) => {
  await signIn(page);
  const csrf = (await page.context().cookies()).find(cookie => cookie.name === 'CF_CSRF')?.value;
  expect(csrf).toBeTruthy();
  for (const title of ['First QA role', 'Second QA role']) {
    const response = await page.request.post('/api/v1/jobs', {
      headers: { 'X-CSRF-Token': csrf! },
      data: { title, rawJd: jd },
    });
    expect(response.status()).toBe(201);
  }
  await page.goto('/interviews');
  const job = page.getByRole('button', {name: /^Target job/});
  for (const title of ['First QA role', 'Second QA role']) {
    await job.click();
    await page.getByRole('option', {name: new RegExp(title)}).click();
    await page.getByRole('button', {name: 'Generate interview questions'}).click();
    await page.getByLabel('Your response').fill('I used Java to create and maintain backend APIs.');
    await page.getByRole('button', {name: 'Submit answer'}).click();
    await expect(page.getByRole('heading', {name: 'Feedback'})).toBeVisible();
  }
  await expect(page.getByRole('button', {name: 'View answers'})).toHaveCount(2);
  await page.getByRole('button', {name: 'View answers'}).first().click();
  await expect(page.getByRole('heading', {name: 'Previous answers'})).toBeVisible();
});

test('sign out dialog traps focus and closes with Escape on mobile', async ({page}) => {
  await page.setViewportSize({width: 390, height: 844});
  await signIn(page);
  await page.goto('/dashboard');
  const account = page.getByRole('button', {name: 'Account options'});
  await account.click();
  await page.getByRole('button', {name: 'Log Out', exact: true}).click();
  const dialog = page.getByRole('alertdialog');
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole('button', {name: 'No', exact: true})).toBeFocused();
  await page.keyboard.press('Shift+Tab');
  await expect(dialog.getByRole('button', {name: 'Yes, Log Out'})).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(dialog).not.toBeVisible();
  await expect(account).toBeFocused();
});
