// Documents of kinds only Advanced lists (shared resources, flow maps)
// arriving while the editor is in Simple: Import JSON (pasted), dropped
// files, Import JSON with nothing open and, with the fake server, a
// document made on the server. Each says how many arrived and that they are
// in Advanced, with one Switch to Advanced that opens them; nothing is
// dropped, and Advanced keeps its old notes.
//
// Env: GLOSS_WEB_URL; GLOSS_FAKE_SERVER for the server part (skipped
// without it); GLOSS_FAKE_ADVANCED_ONLY, a second fake started with
// `--data <dir>` holding only shared resources, for pairing with one.
const assert = require('node:assert/strict');
const shell = require('./support/shell.cjs');

const fixtureText = name => JSON.stringify(shell.fixture(name));

/** The newest toast's text and whether it offers Switch to Advanced. */
async function toast(page, text) {
  const shown = page.locator('.arcane-toast').filter({hasText: text}).first();
  await shown.waitFor({timeout: 8000});
  return shown;
}

/** The library's listed count. */
async function listed(page) {
  return Number(await page.locator('[data-listed]').first().getAttribute('data-listed'));
}

/** The library's rows, by title. */
async function rows(page) {
  const library = await shell.rail(page);
  // A row starts with its kind's icon, a private-use glyph.
  const titles = (await library.locator(shell.SELECTORS.docRow).allInnerTexts())
    .map(text => text.replace(/\p{Co}/gu, '').trim().split('\n')[0].trim());
  await shell.closePanes(page);
  return titles;
}

/** Start page > Import, [json] pasted, then the dialog's primary button. */
async function importFromStart(page, json) {
  await shell.openStart(page);
  await page.locator('main#gloss-start').getByRole('button', {name: /^Import$/}).first().click();
  const dialog = page.locator('#hui-import-dialog [role="dialog"]');
  await dialog.waitFor();
  await dialog.locator('textarea').fill(json);
  const primary = dialog.locator('[data-action-slot="primary"]').first();
  await primary.waitFor();
  // The button's icon is a private-use glyph before the label.
  const label = (await primary.innerText()).replace(/\p{Co}/gu, '').trim();
  const description = (await dialog.locator('.ui-section-description').first().innerText()).trim();
  await primary.click();
  await dialog.waitFor({state: 'hidden'});
  await shell.dismissTour(page, {wait: 2500});
  return {label, description};
}

async function simpleImports(browser) {
  const context = await browser.newContext({viewport: {width: 1440, height: 900}});
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(`${shell.webUrl()}/#/`);
  await page.locator('main#gloss-start').waitFor({timeout: 60000});
  assert.equal(await page.evaluate(() => localStorage.getItem('gloss.mode')), 'simple', 'a first visit starts in Simple');

  // Pasted names over the starting menu: the toast, then the empty Simple views.
  const first = await importFromStart(page, fixtureText('names'));
  assert.equal(first.label, 'Replace document');
  const notice = await toast(page, '1 imported document is in Advanced.');
  assert.equal(await notice.getByRole('button', {name: 'Switch to Advanced'}).count(), 1);
  const centre = page.locator('#gloss-main[data-advanced-documents="1"]');
  await centre.waitFor({timeout: 5000});
  assert.match(await centre.innerText(), /1 document is in Advanced\./);
  assert.equal(await listed(page), 0);
  const library = await shell.rail(page);
  assert.match(await library.innerText(), /1 document is in Advanced\./);
  await shell.closePanes(page);

  // Nothing open: Import JSON adds a document instead of dropping the text.
  await page.waitForTimeout(1100);
  const second = await importFromStart(page, fixtureText('strings'));
  assert.equal(second.label, 'Import');
  assert.equal(second.description, 'Adds a new document.');
  await toast(page, '1 imported document is in Advanced.');
  await page.locator('#gloss-main[data-advanced-documents="2"]').waitFor({timeout: 5000});

  // Two files dropped together, one of them hidden: one count for both.
  // Arrivals more than a second apart are separate notices.
  await page.waitForTimeout(1100);
  await shell.dropFiles(page, [{name: 'lobby.json', text: fixtureText('menu')}, {name: 'glyphs.json', text: fixtureText('glyph')}]);
  const dropped = await toast(page, '1 of 2 imported documents is in Advanced.');
  assert.equal(await listed(page), 1, 'the dropped menu is listed in Simple');

  // The toast's Switch to Advanced lands on the hidden document.
  await dropped.getByRole('button', {name: 'Switch to Advanced'}).click();
  await page.waitForFunction(() => localStorage.getItem('gloss.mode') === 'advanced');
  await page.locator('#gloss-main[data-view]:not([data-view="empty"])').waitFor();
  assert.match(await page.locator('#hui-bar').innerText(), /glyphs/i, 'the glyphs document is open');
  assert.equal(await listed(page), 4, 'names, strings, the menu and the glyphs');

  // Back to Simple: the centre's Switch to Advanced opens the newest hidden one.
  await shell.setMode(page, 'simple');
  await page.locator('#gloss-main').first().waitFor();
  assert.equal(await listed(page), 1);
  await shell.openDocument(page, 'lobby');
  await shell.setMode(page, 'advanced');
  assert.equal(await listed(page), 4);
  await shell.assertFitsWidths(page, [1440, 390]);
  assert.deepEqual(errors, []);
  await context.close();
}

async function centreSwitch(browser) {
  const context = await browser.newContext({viewport: {width: 390, height: 844}});
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(`${shell.webUrl()}/#/`);
  await page.locator('main#gloss-start').waitFor({timeout: 60000});
  await importFromStart(page, fixtureText('presets'));
  const centre = page.locator('#gloss-main[data-advanced-documents="1"]');
  await centre.waitFor({timeout: 5000});
  assert.equal(await shell.noHorizontalScroll(page), true);
  await centre.getByRole('button', {name: 'Switch to Advanced'}).click();
  await page.waitForFunction(() => localStorage.getItem('gloss.mode') === 'advanced');
  await page.locator('#gloss-main[data-view]:not([data-view="empty"])').waitFor();
  assert.equal(await listed(page), 1);
  assert.deepEqual(errors, []);
  await context.close();
}

async function advancedUnchanged(browser) {
  const context = await browser.newContext({viewport: {width: 1440, height: 900}});
  await context.addInitScript(() => {
    if (!localStorage.getItem('gloss.mode')) localStorage.setItem('gloss.mode', 'advanced');
  });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await shell.ready(page);
  await shell.dropFiles(page, [{name: 'names.json', text: fixtureText('names')}]);
  const note = await toast(page, 'Imported names as a new Names.');
  assert.equal(await note.getByRole('button', {name: 'Switch to Advanced'}).count(), 0);
  assert.ok((await rows(page)).includes('names'));
  assert.deepEqual(errors, []);
  await context.close();
}

async function serverArrivals(browser) {
  if (!shell.fakeServer()) {
    console.log('server arrivals: skipped (set GLOSS_FAKE_SERVER)');
    return;
  }
  const context = await browser.newContext({viewport: {width: 1440, height: 900}});
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(`${shell.webUrl()}/#/`);
  await page.locator('main#gloss-start').waitFor({timeout: 60000});
  await shell.pairServer(page);
  await shell.dismissTour(page, {wait: 2500});
  // The built-in server set holds shared resources: the listing says so.
  await toast(page, /new documents? (is|are) in Advanced\./);
  await shell.fakeControl('edit', {kind: 'animation', id: 'arrival-check', json: shell.fixture('animation')});
  const made = await toast(page, '1 new document is in Advanced.');
  await made.getByRole('button', {name: 'Switch to Advanced'}).click();
  await page.waitForFunction(() => localStorage.getItem('gloss.mode') === 'advanced');
  await page.locator('#gloss-main[data-view]:not([data-view="empty"]):not([data-view="loading"])').waitFor({timeout: 10000});
  assert.match(await page.locator('#hui-bar').innerText(), /arrival-check/);
  await shell.fakeControl('edit', {kind: 'animation', id: 'arrival-check', delete: true});
  assert.deepEqual(errors, []);
  await context.close();

  const only = process.env.GLOSS_FAKE_ADVANCED_ONLY;
  if (!only) {
    console.log('pairing a server of shared resources only: skipped (set GLOSS_FAKE_ADVANCED_ONLY)');
    return;
  }
  const saved = process.env.GLOSS_FAKE_SERVER;
  process.env.GLOSS_FAKE_SERVER = only;
  try {
    const next = await browser.newContext({viewport: {width: 1440, height: 900}});
    const tab = await next.newPage();
    const tabErrors = [];
    tab.on('pageerror', error => tabErrors.push(error.message));
    await tab.goto(`${shell.webUrl()}/#/`);
    await tab.locator('main#gloss-start').waitFor({timeout: 60000});
    await shell.pairServer(tab);
    await shell.dismissTour(tab, {wait: 2500});
    const listing = await toast(tab, /^\d+ new documents? (is|are) in Advanced\.|new documents? (is|are) in Advanced\./);
    const count = Number((await listing.innerText()).match(/(\d+) new document/)[1]);
    const centre = tab.locator(`#gloss-main[data-advanced-documents="${count}"]`);
    await centre.waitFor({timeout: 10000});
    assert.equal(await listed(tab), 0);
    await centre.getByRole('button', {name: 'Switch to Advanced'}).click();
    await tab.waitForFunction(() => localStorage.getItem('gloss.mode') === 'advanced');
    assert.equal(await listed(tab), count);
    assert.deepEqual(tabErrors, []);
    await next.close();
  } finally {
    process.env.GLOSS_FAKE_SERVER = saved;
  }
}

async function main() {
  const browser = await shell.launch();
  try {
    await simpleImports(browser);
    await centreSwitch(browser);
    await advancedUnchanged(browser);
    await serverArrivals(browser);
    console.log('Simple import browser passed: pasted, nothing-open and dropped imports name the documents Simple hides, Switch to Advanced opens them, Advanced keeps its notes, server arrivals say the same.');
  } finally {
    await browser.close();
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
