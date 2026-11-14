const assert = require('node:assert/strict');
const shell = require('./support/shell.cjs');

async function main() {
  const browser = await shell.launch();
  try {
    const form = await shell.openEditor(browser);
    const dialog = {type: 'dialog', title: 'Choose label', kind: 'notice', timeoutTicks: 400,
      body: [{text: 'Enter your label', width: 240}],
      inputs: [{key: 'label', type: 'text', label: 'Label', maxLength: 32}],
      buttons: [{label: 'Save', actions: [{type: 'call', action: 'save'}]}],
      unsupported: [{type: 'prompt', kind: 'chat', var: 'label', label: 'Enter a label'}]};
    const menu = {offset: [0, 1.7, 2.5], lockPosition: false, followPlayer: false,
      closeOnDeath: false, closeOnTeleport: false,
      actions: {save: [{type: 'setSession', var: 'label', value: 'input.label'}]},
      components: [{id: 'form_button', x: 0, y: 0, z: 0, data: {type: 'button',
        icon: {type: 'text', text: 'Open form'}, actions: [dialog]}}]};
    await shell.importJson(form.page, menu);
    await form.page.getByText('Components', {exact: true}).click();
    await form.page.locator('[data-rail-row]').filter({hasText: 'form_button'}).click();
    await form.page.getByLabel('Dialog title', {exact: true}).fill('Updated form');
    await form.page.getByLabel('Dialog timeout ticks', {exact: true}).fill('600');
    await form.page.getByRole('switch', {name: 'Close dialog with Escape', exact: true}).focus();
    await form.page.keyboard.press('Space');
    await form.page.getByRole('button', {name: 'Add inputs', exact: true}).click();
    const output = await shell.exportJson(form.page);
    const result = output.components[0].data.actions[0];
    assert.equal(result.title, 'Updated form');
    assert.equal(result.timeoutTicks, 600);
    assert.equal(result.escape, false);
    assert.equal(result.inputs.length, 2);
    assert.deepEqual(result.inputs[0], dialog.inputs[0]);
    assert.deepEqual(result.buttons, dialog.buttons);
    assert.deepEqual(result.unsupported, dialog.unsupported);
    assert.deepEqual(output.actions, menu.actions);
    await shell.assertFitsWidths(form.page);
    assert.deepEqual(form.errors, []);
    await form.page.close();

    const inventory = await shell.openEditor(browser);
    const source = {schemaVersion: 1, revision: 19, title: 'Shop', resolution: '9x1', mask: ['A........'],
      actions: {welcome: [{type: 'message', message: 'Hello'}]},
      keys: {A: {type: 'button', icon: {type: 'text', text: 'Welcome'}, actions: [{type: 'call', action: 'welcome'}]}},
      refresh: {mode: 'dynamic', titleTicks: 20, slotsTicks: 5, conditionsTicks: 10, listTicks: 40}};
    // Refresh ticks are Advanced only.
    if (!(await shell.setMode(inventory.page, 'advanced'))) {
      await inventory.page.evaluate(() => window.localStorage.setItem('gloss.mode', 'advanced'));
      await inventory.page.reload();
      await shell.ready(inventory.page);
    }
    await shell.importJson(inventory.page, source);
    await inventory.page.locator('#hui-inventory-titleTicks').fill('0');
    await inventory.page.locator('#hui-inventory-slotsTicks').fill('3');
    const updated = await shell.exportJson(inventory.page);
    assert.equal(updated.revision, 19);
    assert.deepEqual(updated.refresh, {...source.refresh, titleTicks: 0, slotsTicks: 3});
    assert.deepEqual(updated.actions, source.actions);
    assert.deepEqual(updated.keys.A.actions, source.keys.A.actions);
    await shell.assertFitsWidths(inventory.page);
    assert.deepEqual(inventory.errors, []);
    await inventory.page.close();

    const preview = await shell.openEditor(browser);
    const previewSource = {match: {blocks: ['CHEST']}, elements: [{type: 'label', text: "'Contents'"}],
      contentRefreshTicks: 6, accessCheckTicks: 7, custom: {retained: true}};
    await shell.importJson(preview.page, previewSource);
    await preview.page.getByLabel('Content refresh ticks', {exact: true}).fill('12');
    await preview.page.getByLabel('Access check ticks', {exact: true}).fill('3');
    const previewOutput = await shell.exportJson(preview.page);
    assert.equal(previewOutput.contentRefreshTicks, 12);
    assert.equal(previewOutput.accessCheckTicks, 3);
    assert.deepEqual(previewOutput.elements, previewSource.elements);
    assert.deepEqual(previewOutput.custom, previewSource.custom);
    await shell.assertFitsWidths(preview.page);
    await shell.inspector(preview.page);
    await preview.page.getByLabel('Content refresh ticks', {exact: true}).fill('8');
    await preview.page.getByLabel('Content refresh ticks', {exact: true}).press('Tab');
    await preview.page.setViewportSize({width: 1440, height: 1000});
    assert.equal((await shell.exportJson(preview.page)).contentRefreshTicks, 8);
    assert.deepEqual(preview.errors, []);
    console.log('Dialog and inventory browser passed: form controls, keyboard Escape toggle, nested callbacks, named references, refresh rates, lossless export, mobile widths.');
    console.log('Preview browser passed: content/access refresh controls, preserved elements and custom keys, mobile widths.');
  } finally {
    await browser.close();
  }
}
main().catch(error => {console.error(error); process.exitCode = 1;});
