const assert = require('node:assert/strict');
const shell = require('./support/shell.cjs');

// Number fields clamp on commit, never per keystroke: container preview
// Scale has min 0.25 and max 4.
async function retype(field, text) {
  await field.click();
  await field.press('ControlOrMeta+A');
  await field.pressSequentially(text, {delay: 30});
}

async function main() {
  const browser = await shell.launch();
  try {
    const {page, errors} = await shell.openEditor(browser);
    await shell.newDocument(page, 'Container preview');
    const scale = page.locator('#hui-preview-scale');
    await scale.waitFor();

    await retype(scale, '0');
    assert.equal(await scale.inputValue(), '0');
    assert.equal(await scale.getAttribute('aria-invalid'), 'true');
    await scale.pressSequentially('.85', {delay: 30});
    assert.equal(await scale.inputValue(), '0.85');
    assert.equal(await scale.getAttribute('aria-invalid'), null);
    await scale.press('Tab');
    assert.equal((await shell.exportJson(page)).scale, 0.85);

    await retype(scale, '9');
    assert.equal(await scale.getAttribute('aria-invalid'), 'true');
    await scale.press('Tab');
    assert.equal(await scale.inputValue(), '4');
    assert.equal(await scale.getAttribute('aria-invalid'), null);

    await retype(scale, '0.1');
    await scale.press('Enter');
    assert.equal(await scale.inputValue(), '0.25');

    await scale.click();
    await scale.press('ControlOrMeta+A');
    await scale.press('Backspace');
    assert.equal(await scale.getAttribute('aria-invalid'), 'true');
    await scale.press('Tab');
    assert.equal(await scale.inputValue(), '0.25');
    assert.equal((await shell.exportJson(page)).scale, 0.25);

    assert.deepEqual(errors, []);
    console.log('Number field browser passed: no clamp while typing, error state, clamp on blur and Enter, empty reverts.');
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
