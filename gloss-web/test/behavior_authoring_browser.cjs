const assert = require('node:assert/strict');
const shell = require('./support/shell.cjs');
async function main() {
  const browser = await shell.launch();
  try {
    const {page, errors} = await shell.openEditor(browser);
    // Cue ticks are Advanced only.
    if (!(await shell.setMode(page, 'advanced'))) {
      await page.evaluate(() => window.localStorage.setItem('gloss.mode', 'advanced'));
      await shell.ready(page);
    }
    const fixture = {schemaVersion: 2, on: [{trigger: 'join', do: [
      {type: 'sequence', future: {keep: true}, steps: [
        {type: 'setState', key: 'visits', value: '1', atTicks: 10, extension: 'keep'},
        {type: 'message', message: 'Welcome'},
      ]},
      {type: 'if', when: 'true', then: [{type: 'addState', key: 'visits', value: '1'}], else: []},
    ]}], state: {visits: {scope: 'player', type: 'number', default: '2', extension: {keep: true}}}, future: 'keep'};
    await shell.importJson(page, fixture);
    const baseline = await shell.exportJson(page);
    assert.deepEqual(baseline.on, fixture.on); assert.deepEqual(baseline.state, fixture.state);
    assert.equal(await page.getByText('Click trigger', {exact: true}).count(), 0);
    assert.equal(await page.getByRole('button', {name: 'Call named action', exact: true}).count(), 0);
    const state = page.locator('[data-behavior-state="visits"]');
    await state.getByRole('combobox', {name: 'Scope', exact: true}).selectOption('global');
    await page.locator('#behavior-state-visits-default').fill('7');
    await page.locator('#behavior-state-visits-default').press('Tab');
    await page.locator('input[id$="/on/0/do/0/steps/0/value"]').fill('5');
    await page.locator('input[id$="/on/0/do/0/steps/0/atTicks"]').fill('40');
    await page.locator('input[id$="/on/0/do/0/steps/0/atTicks"]').press('Tab');
    await page.getByRole('button', {name: 'Add state', exact: true}).focus();
    await page.keyboard.press('Enter');
    const addedState = page.locator('[data-behavior-state="state1"]');
    await addedState.getByRole('combobox', {name: 'Type', exact: true}).selectOption('boolean');
    await addedState.getByRole('switch', {name: 'Override', exact: true}).focus();
    await page.keyboard.press('Space');
    await addedState.getByRole('switch', {name: 'Value', exact: true}).focus();
    await page.keyboard.press('Space');
    await page.setViewportSize({width: 390, height: 1000});
    // One action row is open at a time: open the `if` to reach its branch.
    await page.locator('[data-action-path$="/on/0/do/1"]').getByRole('button', {name: 'Edit action 2', exact: true}).first().click();
    await page.locator('input[id$="/on/0/do/1/then/0/value"]').fill('3');
    await page.locator('input[id$="/on/0/do/1/then/0/value"]').press('Tab');
    assert.equal(await shell.noHorizontalScroll(page), true);
    await page.setViewportSize({width: 1440, height: 1000});
    const output = await shell.exportJson(page);
    const expected = structuredClone(baseline);
    expected.state.visits.scope = 'global'; expected.state.visits.default = 7;
    expected.state.state1 = {scope: 'player', type: 'boolean', default: true};
    expected.on[0].do[0].steps[0].value = '5'; expected.on[0].do[0].steps[0].atTicks = 40;
    expected.on[0].do[1].then[0].value = '3';
    assert.deepEqual(output, expected);
    assert.deepEqual(errors, []);
    console.log('Behavior authoring passed: state scope/type/default, nested actions and sequence cues, keyboard/mobile edits, source preservation.');
  } finally {await browser.close();}
}
main().catch(error => {console.error(error); process.exitCode = 1;});
