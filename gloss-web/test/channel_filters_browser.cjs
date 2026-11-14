const assert = require('node:assert/strict');
const shell = require('./support/shell.cjs');

async function main() {
  const browser = await shell.launch();
  try {
    const {page, errors} = await shell.openEditor(browser);
    // Filters and the filter policy are Advanced only.
    if (!(await shell.setMode(page, 'advanced'))) {
      await page.evaluate(() => window.localStorage.setItem('gloss.mode', 'advanced'));
      await shell.ready(page);
    }
    const fixture = {schemaVersion: 2, revision: 9, channel: {name: 'global'}, format: '{{ message }}',
      mentions: {enabled: false}, filters: [{match: '(?i)hello', replace: '$1\\literal'}],
      filtering: {maxMatches: 16, budgetMicros: 100000, onLimit: 'keep-completed', custom: 'retained'}};
    await shell.importJson(page, fixture);
    await page.waitForSelector('.hui-inspector-body.is-channel');
    await page.getByLabel('Sample message').fill('Hello');
    await page.waitForFunction(() => document.querySelector('.hui-connections-line')?.textContent === '$1\\literal');
    await page.getByLabel('Maximum matches', {exact: true}).fill('17');
    await page.getByLabel('Maximum matches', {exact: true}).press('Tab');
    const output = await shell.exportJson(page);
    assert.equal(output.schemaVersion, 2);
    assert.equal(output.revision, 9);
    assert.equal(output.filtering.maxMatches, 17);
    assert.equal(output.filtering.budgetMicros, 100000);
    assert.equal(output.filtering.onLimit, 'keep-completed');
    assert.equal(output.filtering.custom, 'retained');
    assert.equal(output.filters[0].replace, '$1\\literal');
    await page.getByLabel('RE2 pattern', {exact: true}).fill('(?<=a)b');
    await page.waitForFunction(() => document.body.textContent.includes('invalid named capture') || document.body.textContent.includes('invalid or unsupported Perl syntax'));
    await page.getByLabel('RE2 pattern', {exact: true}).fill('(?i)hello');
    await page.getByRole('button', {name: 'Add variant', exact: true}).click();
    await page.getByRole('switch', {name: 'Override Filter policy', exact: true}).focus();
    await page.keyboard.press('Space');
    await page.getByLabel('Maximum matches', {exact: true}).last().fill('3');
    await page.getByLabel('Maximum matches', {exact: true}).last().press('Tab');
    const variantOutput = await shell.exportJson(page);
    assert.equal(variantOutput.variants[0].filtering.maxMatches, 3);
    assert.equal(variantOutput.variants[0].filtering.budgetMicros, 100000);
    await page.evaluate(() => {globalThis.savedChannelEngine = globalThis.GlossChannelFilters; globalThis.GlossChannelFilters = null;});
    await page.getByLabel('Sample message').fill('Hello again');
    await page.getByText('RE2 preview engine is unavailable').first().waitFor();
    await page.evaluate(() => {globalThis.GlossChannelFilters = globalThis.savedChannelEngine; delete globalThis.savedChannelEngine;});
    await page.getByLabel('Sample message').fill('Hello');
    await page.waitForFunction(() => document.querySelector('.hui-connections-line')?.textContent === '$1\\literal');
    await shell.assertFitsWidths(page, [1440, 390]);
    assert.deepEqual(errors, []);
    console.log('Channel browser: preview, literal replacement, policy export, variant override, RE2 error, engine recovery and viewport checks passed.');
  } finally {
    await browser.close();
  }
}
main().catch(error => {console.error(error); process.exitCode = 1;});
