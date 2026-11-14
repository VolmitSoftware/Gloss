const assert = require('node:assert/strict');
const shell = require('./support/shell.cjs');

async function main() {
  const browser = await shell.launch();
  try {
    const {page, errors} = await shell.openEditor(browser);
    // The subscription condition is Advanced only.
    if (!(await shell.setMode(page, 'advanced'))) {
      await page.evaluate(() => window.localStorage.setItem('gloss.mode', 'advanced'));
      await shell.ready(page);
    }
    const subscription = index => page.locator(`[data-surface-subscription="${index}"]`);
    const source = {
      schemaVersion: 1, revision: 3, surface: 'actionbar', automatic: false,
      select: { priority: 0, when: 'false' }, presentation: { text: 'Event message' }, variants: [],
      on: [{ trigger: 'join', future: { retained: [1, 2] } }], extension: 'retained',
    };
    await shell.importJson(page, source);
    assert.deepEqual(await shell.exportJson(page), source);
    await subscription(0).getByLabel('Event (Subscription 1)', { exact: true }).selectOption('interval');
    await subscription(0).getByLabel('Interval (Subscription 1)', { exact: true }).fill('120');
    await subscription(0).getByLabel('Delay (Subscription 1)', { exact: true }).fill('10');
    await subscription(0).getByLabel('Condition (Subscription 1)', { exact: true }).fill('viewer.op');
    await subscription(0).getByLabel('Condition (Subscription 1)', { exact: true }).press('Tab');
    let changed = await shell.exportJson(page);
    assert.deepEqual(changed.on, [{ trigger: 'interval', everyTicks: 120, delayTicks: 10, when: 'viewer.op', future: { retained: [1, 2] } }]);
    await page.getByRole('button', { name: 'Add subscription', exact: true }).focus();
    await page.keyboard.press('Enter');
    await subscription(1).getByLabel('Event (Subscription 2)', { exact: true }).selectOption('world_change');
    await subscription(1).getByRole('button', { name: 'Move up', exact: true }).click();
    changed = await shell.exportJson(page);
    assert.equal(changed.on[0].trigger, 'world_change');
    assert.deepEqual(changed.on[1].future, source.on[0].future);
    await subscription(1).getByLabel('Event (Subscription 2)', { exact: true }).selectOption('join');
    changed = await shell.exportJson(page);
    assert.equal('everyTicks' in changed.on[1], false);
    await page.setViewportSize({ width: 390, height: 1000 });
    await shell.inspector(page);
    await subscription(1).getByLabel('Delay (Subscription 2)', { exact: true }).fill('25');
    await subscription(1).getByLabel('Delay (Subscription 2)', { exact: true }).press('Tab');
    assert.equal(await shell.noHorizontalScroll(page), true);
    await shell.closePanes(page);
    await page.setViewportSize({ width: 1440, height: 1000 });
    assert.equal((await shell.exportJson(page)).on[1].delayTicks, 25);
    assert.equal(await shell.noHorizontalScroll(page), true);
    assert.deepEqual(errors, []);
    console.log('HUD subscription browser passed: typed event/interval/delay/condition edits, keyboard add, reorder, preserved extensions and mobile editing.');
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
