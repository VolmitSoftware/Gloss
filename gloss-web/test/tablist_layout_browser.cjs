const assert = require('node:assert/strict');
const shell = require('./support/shell.cjs');

async function main() {
  const browser = await shell.launch();
  try {
    const {page, errors} = await shell.openEditor(browser);
    const fixture = {schemaVersion: 3, revision: 7, show: true,
      headerFooter: {enabled: true, presentation: {header: 'Roster', footer: 'Footer'}, variants: []},
      listNames: {enabled: true, presentation: {format: '$player'}, variants: []},
      layout: {enabled: true, show: true, entries: 20,
        slots: [{column: 0, row: 0, text: 'Welcome {{ viewer.name }}', skin: 'brand', ping: 77, hat: true, custom: 'cell-retained'}],
        sections: [{id: 'players', column: 0, row: 1, columns: 1, rows: 3, filter: 'true',
          format: '$player', sort: [{expression: 'subject.name', type: 'text', direction: 'descending'}],
          overflow: 'count', overflowFormat: '+{count} more', includeNpcs: false, skin: 'brand', hat: false}],
        skins: {brand: {value: 'synthetic-texture', signature: 'synthetic-signature', custom: 'skin-retained'}},
        variants: [{id: 'event', priority: 30, when: 'false', presentation: {entries: 20,
          slots: [{column: 0, row: 0, text: 'EVENT ACTIVE', hat: true}], sections: [], skins: {}}}]}};
    await shell.importJson(page, fixture);
    await page.waitForSelector('.hui-tablist-grid');
    await page.getByLabel('Base layout cell 1 text', {exact: true}).fill('Updated {{ viewer.name }}');
    await page.getByLabel('Base layout cell 1 text', {exact: true}).press('Tab');
    // The cell's switch is named Hat (Fixed cell 1); the innermost section holding the cell's text is that cell.
    const cell = page.locator('.ui-section').filter({has: page.getByLabel('Base layout cell 1 text', {exact: true})}).last();
    await cell.getByRole('switch', {name: 'Hat (Fixed cell 1)', exact: true}).focus();
    await page.keyboard.press('Space');
    await page.getByLabel('Base layout roster 1 overflow format', {exact: true}).fill('Remaining {count}');
    await page.getByLabel('Base layout roster 1 overflow format', {exact: true}).press('Tab');
    await page.getByLabel('Layout variant 1 condition', {exact: true}).fill('true');
    await page.getByLabel('Layout variant 1 condition', {exact: true}).press('Tab');
    await page.waitForFunction(() => document.querySelector('.hui-tablist-grid')?.textContent.includes('EVENT ACTIVE'));
    const output = await shell.exportJson(page);
    assert.equal(output.schemaVersion, 3);
    assert.equal(output.revision, 7);
    assert.equal(output.layout.entries, 20);
    assert.equal(output.layout.slots[0].text, 'Updated {{ viewer.name }}');
    assert.equal(output.layout.slots[0].hat, false);
    assert.equal(output.layout.slots[0].ping, 77);
    assert.equal(output.layout.slots[0].custom, 'cell-retained');
    assert.deepEqual(output.layout.skins.brand, fixture.layout.skins.brand);
    assert.deepEqual(output.layout.sections[0].sort, fixture.layout.sections[0].sort);
    assert.equal(output.layout.sections[0].overflowFormat, 'Remaining {count}');
    assert.equal(output.layout.sections[0].hat, false);
    assert.equal(output.layout.variants[0].when, 'true');
    await shell.assertFitsWidths(page, [1440, 390]);
    assert.deepEqual(errors, []);
    console.log('Tablist browser: schema3 import, typed cell/section edits, keyboard hat toggle, conditional preview, lossless export and viewport checks passed.');
  } finally {
    await browser.close();
  }
}
main().catch(error => {console.error(error); process.exitCode = 1;});
