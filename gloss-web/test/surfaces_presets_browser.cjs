const assert = require('node:assert/strict');
const shell = require('./support/shell.cjs');

async function main() {
  const browser = await shell.launch();
  try {
    const hud = await shell.openEditor(browser);
    const fixture = {schemaVersion: 1, revision: 19, surface: 'bossbar', group: 'event', automatic: false,
      select: {when: 'true'}, show: true,
      delivery: {mode: 'queue', preempt: 'higher', maxPending: 8, overflow: 'reject', cooldownTicks: 40,
        deduplicate: 'purpose', expireTicks: 300},
      on: [{trigger: 'join', delayTicks: 20, when: 'true'}],
      presentation: {title: 'Event', progress: '0.5', color: 'purple', style: 'solid', flags: ['create_fog'], ttlTicks: 80}};
    await shell.importJson(hud.page, fixture);
    await hud.page.getByLabel('Bossbar group', {exact: true}).fill('quest');
    // Max pending sits in the delivery section's closed More group.
    await hud.page.locator('summary').filter({has: hud.page.getByText('More', {exact: true})}).first().click();
    await hud.page.getByLabel('Max pending', {exact: true}).fill('12');
    await hud.page.getByRole('switch', {name: 'Darken sky', exact: true}).focus();
    await hud.page.keyboard.press('Space');
    const output = await shell.exportJson(hud.page);
    assert.equal(output.revision, 19);
    assert.equal(output.group, 'quest');
    assert.equal(output.automatic, false);
    assert.deepEqual(output.on, fixture.on);
    assert.deepEqual(output.delivery, {...fixture.delivery, maxPending: 12});
    assert.deepEqual(new Set(output.presentation.flags), new Set(['create_fog', 'darken_sky']));
    await shell.assertFitsWidths(hud.page);
    assert.deepEqual(hud.errors, []);
    await hud.page.close();

    const presets = await shell.openEditor(browser);
    // Simple hides Shared resources, the preset catalog among them.
    await shell.setMode(presets.page, 'advanced');
    const catalog = {schemaVersion: 1, revision: 27, defaults: {boards: {title: 'Base'}},
      presets: {boards: {base: {values: {lines: ['First']}}, event: {extends: 'base', values: {title: 'Event'}}},
        behaviors: {welcome: {values: {trigger: 'join'}}}}};
    await shell.importJson(presets.page, catalog);
    const defaults = presets.page.locator('.is-presets .hui-extras').filter({has: presets.page.locator('summary', {hasText: 'Default values'})});
    await defaults.locator('summary').first().click();
    await defaults.getByLabel('title', {exact: true}).fill('Shared');
    await presets.page.getByLabel('New preset name', {exact: true}).fill('season');
    await presets.page.getByRole('button', {name: 'Add preset', exact: true}).click();
    await presets.page.getByLabel('Extends', {exact: true}).fill('event');
    await presets.page.getByLabel('Extends', {exact: true}).press('Tab');
    const edited = await shell.exportJson(presets.page);
    assert.equal(edited.revision, 27);
    assert.deepEqual(edited.defaults, {boards: {title: 'Shared'}});
    assert.deepEqual(edited.presets.behaviors, catalog.presets.behaviors);
    assert.deepEqual(edited.presets.boards.base, catalog.presets.boards.base);
    assert.deepEqual(edited.presets.boards.event, catalog.presets.boards.event);
    assert.deepEqual(edited.presets.boards.season, {extends: 'event', values: {}});
    await shell.assertFitsWidths(presets.page);
    assert.deepEqual(presets.errors, []);
    console.log('Surface and preset browser: group, queue, keyboard flags, ancestry edits, lossless export and mobile widths passed.');
  } finally {
    await browser.close();
  }
}
main().catch(error => {console.error(error); process.exitCode = 1;});
