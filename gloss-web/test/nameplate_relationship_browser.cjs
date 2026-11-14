const assert = require('node:assert/strict');
const shell = require('./support/shell.cjs');

async function main() {
  const browser = await shell.launch();
  try {
    const {page, errors} = await shell.openEditor(browser);
    const fixture = {schemaVersion: 1, revision: 9, select: {when: 'true', priority: 0},
      presentation: {lines: [{text: 'RELATIONSHIP {bar}', show: true}], healthSegments: 13,
        hideSneaking: true, hideInvisible: true, hideSpectator: true, includeNpcs: false, showSelf: false,
        custom: 'preserved'}, variants: []};
    await shell.importJson(page, fixture);
    await page.waitForSelector('.hui-identity-billboard');
    await page.getByLabel('Health segments', {exact: true}).fill('23');
    await page.getByLabel('Health segments', {exact: true}).press('Tab');
    await page.getByRole('switch', {name: 'Show own plate', exact: true}).focus();
    await page.keyboard.press('Space');
    await page.getByRole('button', {name: 'Own plate', exact: true}).click();
    await page.waitForFunction(() => document.querySelector('.hui-identity-billboard')?.textContent.includes('RELATIONSHIP'));
    await page.getByRole('button', {name: 'Invisible', exact: true}).click();
    await page.locator('.ui-stage-status', {hasText: 'Hidden'}).waitFor();
    await page.getByRole('switch', {name: 'Hide invisible players', exact: true}).click();
    await page.waitForFunction(() => document.querySelector('.hui-identity-billboard')?.textContent.includes('RELATIONSHIP'));
    const output = await shell.exportJson(page);
    assert.equal(output.presentation.healthSegments, 23);
    assert.equal(output.presentation.showSelf, true);
    assert.equal(output.presentation.hideInvisible, false);
    assert.equal(output.presentation.includeNpcs, false);
    assert.equal(output.presentation.custom, 'preserved');
    assert.equal(output.revision, 9);
    await shell.assertFitsWidths(page, [1440, 390]);
    assert.deepEqual(errors, []);
    console.log('Nameplate relationship browser passed: typed health controls, keyboard visibility, preview states, lossless export, desktop/mobile widths.');
  } finally {
    await browser.close();
  }
}
main().catch(error => {console.error(error); process.exitCode = 1;});
