const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {glossRepository} = require('./support/gloss_repository.cjs');
const shell = require('./support/shell.cjs');

async function main() {
  const browser = await shell.launch();
  try {
    const {page, errors} = await shell.openEditor(browser);
    // Refresh cadence and the indicator limits are Advanced only.
    if (!(await shell.setMode(page, 'advanced'))) {
      await page.evaluate(() => window.localStorage.setItem('gloss.mode', 'advanced'));
      await shell.ready(page);
    }
    const bubble = JSON.parse(fs.readFileSync(path.join(glossRepository, 'src/main/resources/defaults/bubbles/default.json'), 'utf8'));
    await shell.importJson(page, bubble);
    await page.getByRole('radio', {name: 'Reject new', exact: true}).or(page.getByRole('button', {name: 'Reject new', exact: true})).first().click();
    await page.getByRole('switch', {name: 'Override Content interval', exact: true}).focus();
    await page.keyboard.press('Space');
    await page.getByLabel('Content ticks', {exact: true}).fill('20');
    await page.getByLabel('Content ticks', {exact: true}).press('Tab');
    await page.getByRole('switch', {name: 'Override Motion interval', exact: true}).focus();
    await page.keyboard.press('Space');
    await page.getByLabel('Motion ticks', {exact: true}).fill('4');
    await page.getByLabel('Motion ticks', {exact: true}).press('Tab');
    const changedBubble = await shell.exportJson(page);
    assert.equal(changedBubble.overflow, 'reject-new');
    assert.deepEqual(changedBubble.motion, bubble.motion);
    assert.deepEqual(changedBubble.refresh, {contentTicks: 20, motionTicks: 4});
    const indicator = JSON.parse(fs.readFileSync(path.join(glossRepository, 'src/main/resources/defaults/damage-indicators/default.json'), 'utf8'));
    await shell.importJson(page, indicator);
    const initialIndicator = await shell.exportJson(page);
    await page.getByLabel('Aggregation ticks', {exact: true}).fill('8');
    await page.getByLabel('Maximum pending samples', {exact: true}).fill('48');
    await page.getByLabel('Maximum pending samples', {exact: true}).press('Tab');
    const changed = await shell.exportJson(page);
    assert.equal(changed.limits.aggregationTicks, 8);
    assert.equal(changed.limits.maxPendingSamples, 48);
    assert.deepEqual(changed.damage, initialIndicator.damage);
    assert.deepEqual(changed.healing, initialIndicator.healing);
    await shell.assertFitsWidths(page, [1440, 390]);
    assert.deepEqual(errors, []);
    console.log('Transient policy browser passed: bubble overflow, indicator aggregation and capacity, independent content/motion cadence, preserved styles, mobile width.');
  } finally { await browser.close(); }
}
main().catch(error => {console.error(error); process.exitCode = 1;});
