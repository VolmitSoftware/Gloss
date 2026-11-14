const assert = require('node:assert/strict');
const shell = require('./support/shell.cjs');

/** A ToggleChoice option: the button [option] in the group named [group]. */
function choice(scope, group, option) {
  return scope.getByRole('group', {name: group, exact: true}).getByRole('button', {name: option, exact: true});
}

async function main() {
  const browser = await shell.launch();
  try {
    const {page, errors} = await shell.openEditor(browser);
    // Row settings, the layout and the native objectives are Advanced only.
    if (!(await shell.setMode(page, 'advanced'))) {
      await page.evaluate(() => window.localStorage.setItem('gloss.mode', 'advanced'));
      await shell.ready(page);
    }
    const fixture = {schemaVersion: 2, revision: 9, select: {when: 'true'}, presentation: {
      title: '', hideNumbers: true,
      lines: [{id: 'balance', text: 'Balance', value: '100', format: 'fixed', show: 'true', custom: 'retained'},
        {section: 'account', show: false}],
      layout: {sections: {account: ['Account']}, pages: [{id: 'first', show: false, lines: ['Page']}],
        refresh: {valueTicks: 2, custom: 'refresh'}, overflow: 'truncate', custom: {nested: 'layout'}}}, variants: [],
      objectives: {custom: 'objectives', belowName: {title: 'Health', value: 'subject.health', custom: 'slot'}}};
    await shell.importJson(page, fixture);
    await page.waitForSelector('.hui-inspector-body.is-scoreboard');
    const body = page.locator('.hui-inspector-body.is-scoreboard');
    const firstRow = body.getByRole('list', {name: 'Lines', exact: true}).first().getByRole('listitem').first();
    await firstRow.getByRole('textbox').first().fill('Credits');
    await firstRow.locator('summary').filter({hasText: 'Row settings'}).focus();
    await page.keyboard.press('Enter');
    await firstRow.getByLabel('Value', {exact: true}).fill('200');
    await firstRow.getByLabel('Value', {exact: true}).press('Tab');
    const layout = page.locator('.hui-scoreboard-layout').first();
    await layout.getByLabel('Section name', {exact: true}).fill('stats');
    await layout.getByRole('button', {name: 'Rename section', exact: true}).click();
    await layout.getByRole('button', {name: 'Add section', exact: true}).click();
    const extraSection = layout.locator('.hui-scoreboard-section').last();
    await extraSection.getByRole('button', {name: 'Delete section', exact: true}).click();
    const firstPage = layout.locator('.hui-scoreboard-page').first();
    // The page's own condition comes before its rows' hidden Row settings.
    await firstPage.getByLabel('Show condition', {exact: true}).first().fill('true');
    await firstPage.getByLabel('Duration', {exact: true}).fill('4');
    await firstPage.getByLabel('Duration', {exact: true}).press('Tab');
    await firstPage.getByRole('switch', {name: 'Inherit presentation title', exact: true}).click();
    await firstPage.getByLabel('Title', {exact: true}).fill('First');
    await layout.getByRole('button', {name: 'Add page', exact: true}).click();
    const secondPage = layout.locator('.hui-scoreboard-page').last();
    await secondPage.getByLabel('Id', {exact: true}).fill('second');
    await secondPage.getByLabel('Duration', {exact: true}).fill('7');
    await secondPage.getByLabel('Duration', {exact: true}).press('Tab');
    await secondPage.getByRole('button', {name: /Add line$/}).first().click();
    await secondPage.getByRole('list', {name: 'Lines', exact: true}).getByRole('textbox').first().fill('Second');
    await secondPage.getByRole('button', {name: 'Move page up', exact: true}).focus();
    await page.keyboard.press('Enter');
    await choice(layout, 'Overflow', 'Reject overflow').click();
    for (const [label, ticks] of [['Title', '3'], ['Text', '4']]) {
      await layout.getByRole('switch', {name: `Override ${label} interval`, exact: true}).focus();
      await page.keyboard.press('Space');
      await layout.getByLabel(`${label} ticks`, {exact: true}).fill(ticks);
      await layout.getByLabel(`${label} ticks`, {exact: true}).press('Tab');
    }
    const objective = name => page.locator('.hui-scoreboard-objective').filter({has: page.getByText(name, {exact: true})});
    const belowName = objective('Below name');
    await belowName.getByLabel('Numeric value (Below name)', {exact: true}).fill('subject.health * 2');
    await choice(belowName, 'Score format', 'Fixed').click();
    await belowName.getByLabel('Formatted value (Below name)', {exact: true}).fill('{{ subject.health }}');
    await belowName.getByLabel('Subject condition (Below name)', {exact: true}).fill('subject.world == viewer.world');
    await choice(belowName, 'Slot conflict', 'Override').click();
    const playerList = objective('Player list');
    await playerList.getByRole('switch', {name: 'Enabled (Player list)', exact: true}).click();
    await choice(playerList, 'Render type', 'Hearts').click();
    await playerList.getByLabel('Refresh ticks (Player list)', {exact: true}).fill('5');
    await playerList.getByLabel('Refresh ticks (Player list)', {exact: true}).press('Tab');
    await page.setViewportSize({width: 390, height: 1000});
    await shell.inspector(page);
    await layout.getByLabel('Value ticks', {exact: true}).fill('9');
    await layout.getByLabel('Value ticks', {exact: true}).press('Tab');
    await page.getByRole('button', {name: /Add variant$/}).click();
    const variantLayout = page.locator('.hui-scoreboard-variant .hui-scoreboard-layout');
    // A variant's layout sections start closed.
    await variantLayout.locator('summary').filter({hasText: 'Layout policy'}).click();
    await variantLayout.getByLabel('Title ticks', {exact: true}).fill('11');
    await variantLayout.getByLabel('Title ticks', {exact: true}).press('Tab');
    assert.equal(await shell.noHorizontalScroll(page), true);
    await shell.closePanes(page);
    await page.setViewportSize({width: 1440, height: 1000});
    const output = await shell.exportJson(page);
    assert.equal(output.revision, 9);
    assert.equal(output.presentation.title, '');
    assert.deepEqual(output.presentation.lines[0], {...fixture.presentation.lines[0], text: 'Credits', value: '200'});
    assert.deepEqual(output.presentation.lines[1], {...fixture.presentation.lines[1], section: 'stats'});
    assert.deepEqual(output.presentation.layout.sections, {stats: ['Account']});
    assert.deepEqual(output.presentation.layout.pages, [
      {id: 'second', lines: ['Second'], durationTicks: 7},
      {id: 'first', show: true, title: 'First', lines: ['Page'], durationTicks: 4},
    ]);
    assert.equal(output.presentation.layout.overflow, 'error');
    assert.deepEqual(output.presentation.layout.refresh, {titleTicks: 3, textTicks: 4, valueTicks: 9, custom: 'refresh'});
    assert.deepEqual(output.presentation.layout.custom, fixture.presentation.layout.custom);
    assert.deepEqual(output.objectives, {custom: 'objectives', playerList: {renderType: 'hearts', refreshTicks: 5},
      belowName: {...fixture.objectives.belowName, value: 'subject.health * 2', format: 'fixed',
        valueText: '{{ subject.health }}', subjects: 'subject.world == viewer.world', conflict: 'override'}});
    assert.equal(output.variants.length, 1);
    assert.equal(output.variants[0].presentation.layout.refresh.titleTicks, 11);
    assert.deepEqual(output.variants[0].presentation.layout.sections, output.presentation.layout.sections);
    await shell.assertFitsWidths(page, [1440, 390]);
    assert.deepEqual(errors, []);
    console.log('Scoreboard browser: rows, section rename, ordered pages, refresh overrides, native objectives, variants, keyboard editing, lossless export and viewport checks passed.');
  } finally {
    await browser.close();
  }
}
main().catch(error => {console.error(error); process.exitCode = 1;});
