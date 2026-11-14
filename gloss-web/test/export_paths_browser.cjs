const assert = require('node:assert/strict');
const shell = require('./support/shell.cjs');

// The Export dialog's File path for kinds the plugin keeps outside previews/.
// document_data_path_test.dart pins every kind against EditorSyncDocumentKind.
const KINDS = [
  ['Chat channel', /^plugins\/Gloss\/channels\/[^/]+\.json$/],
  ['Waypoint', /^plugins\/Gloss\/waypoints\/[^/]+\.json$/],
  ['Leaderboard', /^plugins\/Gloss\/leaderboards\/[^/]+\.json$/],
  ['Names', /^plugins\/Gloss\/names\.json$/],
  ['Strings', /^plugins\/Gloss\/strings\/[^/]+\.json$/],
  ['Real drops', /^plugins\/Gloss\/real-drops\/default\.json$/],
  ['Container preview', /^plugins\/Gloss\/previews\/[^/]+\.json$/],
];

async function exportedPath(page) {
  await shell.barAction(page, /^Export .* JSON$/);
  const dialog = page.locator('#hui-export-dialog [role="dialog"]');
  const value = await dialog.locator('#hui-export-file').inputValue();
  await dialog.getByRole('button', {name: 'Close', exact: true}).click();
  await dialog.waitFor({state: 'hidden'});
  return value;
}

async function main() {
  const browser = await shell.launch();
  try {
    const {page, errors} = await shell.openEditor(browser);
    await shell.setMode(page, 'advanced');
    for (const [create, expected] of KINDS) {
      await shell.newDocument(page, create);
      const value = await exportedPath(page);
      assert.match(value, expected, create);
    }
    // The menu preview's action log has its own Close; exportJson must still
    // close the Export dialog.
    await shell.newDocument(page, 'Menu');
    assert.ok(await shell.showView(page, 'preview'));
    await page.getByRole('heading', {name: 'Action log'}).waitFor();
    assert.equal(typeof (await shell.exportJson(page)), 'object');
    await page.locator('#hui-export-dialog [role="dialog"]').waitFor({state: 'hidden'});
    assert.deepEqual(errors, []);
    console.log(`Export paths browser passed: ${KINDS.length} kinds, export with the action log open.`);
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
