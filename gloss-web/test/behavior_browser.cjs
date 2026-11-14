const assert = require('node:assert/strict');
const shell = require('./support/shell.cjs');

async function main() {
  const browser = await shell.launch();
  try {
    const {page, errors} = await shell.openEditor(browser);
    const source = {schemaVersion: 2, revision: 19, matching: {maxInputCharacters: 4096},
      on: [{trigger: 'chat', pattern: '^hello$', permission: 'example.greeting',
        do: [{type: 'sequence', steps: [{type: 'message', message: 'Welcome'}]}]}],
      state: {visits: {scope: 'player', type: 'number', default: 0}}, custom: {retained: true}};
    // The matching limits are expert tuning: Advanced only.
    if (!(await shell.setMode(page, 'advanced'))) {
      await page.evaluate(() => window.localStorage.setItem('gloss.mode', 'advanced'));
      await page.reload();
      await shell.ready(page);
    }
    await shell.importJson(page, source);
    await page.getByLabel('Maximum input characters', {exact: true}).fill('2048');
    await page.getByLabel('Chat pattern 1', {exact: true}).fill('(?=a)a');
    const problems = page.locator('.hui-status-problems button');
    await problems.filter({hasText: /\berrors?\b/}).waitFor();
    await page.getByLabel('Chat pattern 1', {exact: true}).fill('^welcome$');
    await page.getByLabel('Chat pattern 1', {exact: true}).press('Tab');
    await problems.filter({hasText: /No issues/}).waitFor();
    await page.setViewportSize({width: 390, height: 1000});
    await page.getByLabel('Maximum search work units', {exact: true}).fill('75000');
    assert.equal(await shell.noHorizontalScroll(page), true);
    await page.setViewportSize({width: 1440, height: 1000});
    const output = await shell.exportJson(page);
    assert.equal(output.revision, source.revision);
    assert.equal(output.matching.maxInputCharacters, 2048);
    assert.equal(output.matching.maxWorkUnits, 75000);
    assert.equal(output.on[0].pattern, '^welcome$');
    assert.deepEqual(output.on[0].do, source.on[0].do);
    assert.deepEqual(output.state, source.state);
    assert.deepEqual(output.custom, source.custom);
    assert.deepEqual(errors, []);
    console.log('Behavior browser passed: typed matching edits, RE2 rejection/recovery, keyboard and mobile editing, preserved actions/state/unknown keys.');
  } finally {
    await browser.close();
  }
}
main().catch(error => {console.error(error); process.exitCode = 1;});
