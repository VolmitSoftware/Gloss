// Frame helpers for the browser scripts and the layout checks. Selectors
// prefer the hooks the redesigned editor exposes (`#gloss-main[data-view]`,
// `data-preview-frame`, `data-action-row`, `data-sync-action`, ...) and fall
// back to today's `.hui-*` markup. Every fallback lives in LEGACY below, so
// it can be deleted in one edit once the redesign ships its hooks.
//
// Env: GLOSS_WEB_URL (required, the served build), GLOSS_FAKE_SERVER (the
// fake Gloss server, `dart run tool/fake_server/main.dart --port 9807
// --editor-url <GLOSS_WEB_URL>`; optional), GLOSS_PAIR_LINK (a live
// server's `/gloss editor pair` link, used when there is no fake; good
// once), PLAYWRIGHT_MODULE, PLAYWRIGHT_CHROMIUM_EXECUTABLE.
const fs = require('node:fs');
const path = require('node:path');

/** Hooks of the redesigned editor. */
const HOOKS = {
  main: '#gloss-main',
  view: view => `#gloss-main[data-view="${view}"]`,
  previewFrame: '[data-preview-frame]',
  windowFit: '[data-window-fit]',
  footer: '[data-action-row="footer"]',
  primary: '[data-action-slot="primary"]',
  syncBar: '[data-sync-bar]',
  syncStatus: status => `[data-sync-bar][data-sync-status="${status}"]`,
  syncAction: action => `[data-sync-action="${action}"]`,
  infoMark: '[data-ui-info]',
  gameDrawn: '[data-game-drawn]',
  pane: name => `[data-pane="${name}"]`,
  docRow: '[data-doc-row]',
};

/**
 * Today's markup for the same things. Delete an entry (and its use in
 * `either`) once the redesign exposes the hook above it.
 */
const LEGACY = {
  previewFrame: [
    '.hui-canvas-stage', '.hui-preview-stage', '.hui-gloss-game', '.hui-preview-card-stage', '.hui-motd-stage',
  ].join(', '),
  // Game windows drawn inside a frame (must stay wholly visible).
  windows: '.hui-mc-stage, .hui-preview-card, .hui-inventory-screen, .hui-motd-screen, .hui-tablist-screen, .hui-gloss-game-hud',
  // Boxes that clip their own content on purpose: the WebGL host and its
  // DOM layer, the menu 3D preview's camera view (its world quads end at
  // the camera's edge; a wide quad's transparent canvas runs past it), the
  // pan/zoom artboard, the preview log's rows. These are custom surfaces
  // that keep their classes in the redesign.
  ownClip: '.hui-mc-stage, .hui-mc-gl-host, .hui-mc-dom, .hui-preview-stage, .hui-canvas-surface, .hui-preview-log-rows',
  footer: '.arcane-dialog-footer, .arcane-sheet-footer',
  // Content drawn as the game draws it, at the game's sizes.
  gameDrawn: '.hui-gloss-game-world, .hui-gloss-game-anchor, .hui-gloss-game-hud, .hui-mc-dom, .hui-inventory-slot, .hui-canvas-surface',
  // Game-screen chrome that is a picture of the game, not editor surfaces.
  gameWindows: '.hui-gloss-game, .hui-mc-stage, .hui-canvas-surface, .hui-preview-card, .hui-inventory-screen, .hui-motd-screen, .hui-tablist-screen',
  // Drawers: the rail and inspector panes while open over the page.
  drawers: '#hui-library-drawer [role="dialog"], #hui-inspector-drawer [role="dialog"]',
  splitter: '.hui-splitter',
};

/** A hook and its fallback as one selector list. */
function either(hook, legacy) {
  return [hook, legacy].filter(Boolean).join(', ');
}

const SELECTORS = {
  main: HOOKS.main,
  view: HOOKS.view,
  previewFrame: either(HOOKS.previewFrame, LEGACY.previewFrame),
  windowFit: HOOKS.windowFit,
  windows: either(`${HOOKS.windowFit} > *, ${HOOKS.gameDrawn}`, LEGACY.windows),
  ownClip: LEGACY.ownClip,
  footer: either(HOOKS.footer, LEGACY.footer),
  // The kit's primary slot, or a library primary button.
  primary: `${HOOKS.primary}, [data-variant="primary"]`,
  syncBar: HOOKS.syncBar,
  syncStatus: HOOKS.syncStatus,
  infoMark: HOOKS.infoMark,
  gameDrawn: either(HOOKS.gameDrawn, LEGACY.gameDrawn),
  gameWindows: either(HOOKS.gameDrawn, LEGACY.gameWindows),
  drawers: LEGACY.drawers,
  // An open dialog, sheet, menu or drawer: a screen that shows one is judged inside it.
  layer: `[role="dialog"], [role="alertdialog"], [role="menu"], ${LEGACY.drawers}`,
  // Library rows are list rows, not buttons of a row.
  notButtons: HOOKS.docRow,
  pane: HOOKS.pane,
  docRow: HOOKS.docRow,
};

/**
 * Every document kind: wire name, plural label (kind picker), the New
 * document button, its fixture in test/demo/fixtures, its views, and
 * whether its Visual view draws a stage.
 */
const ALL_VIEWS = ['visual', 'preview', 'code', 'split'];
const EDITOR_ONLY = ['visual', 'code', 'split'];
const KINDS = [
  {kind: 'menu', plural: 'Menus', create: 'Menu', fixture: 'menu-shop', views: ALL_VIEWS, stage: true},
  {kind: 'containerPreview', plural: 'Container previews', create: 'Container preview', fixture: 'container-preview', views: ALL_VIEWS, stage: true},
  {kind: 'panel', plural: 'Flow maps', create: 'Flow map', fixture: null, views: ['visual'], stage: true},
  {kind: 'hologram', plural: 'Holograms', create: 'Hologram', fixture: 'hologram', views: ALL_VIEWS, stage: true},
  {kind: 'animation', plural: 'Animations', create: 'Animation', fixture: 'animation', views: ALL_VIEWS, stage: true},
  {kind: 'scoreboard', plural: 'Scoreboards', create: 'Scoreboard', fixture: 'scoreboard', views: ALL_VIEWS, stage: true},
  {kind: 'surface', plural: 'Surfaces', create: 'Surface', fixture: 'surface', views: ALL_VIEWS, stage: true},
  {kind: 'motd', plural: 'MOTDs', create: 'MOTD', fixture: 'motd', views: ALL_VIEWS, stage: true},
  {kind: 'connections', plural: 'Connection messages', create: 'Connection messages', fixture: 'connections', views: ALL_VIEWS, stage: true},
  {kind: 'channel', plural: 'Chat channels', create: 'Chat channel', fixture: 'channel', views: ALL_VIEWS, stage: true},
  {kind: 'emoji', plural: 'Emoji', create: 'Emoji', fixture: 'emoji', views: ALL_VIEWS, stage: true},
  {kind: 'bubbleStyle', plural: 'Bubble styles', create: 'Bubble style', fixture: 'bubble-style', views: ALL_VIEWS, stage: true},
  {kind: 'damageIndicators', plural: 'Damage indicators', create: 'Damage indicators', fixture: 'damage-indicators', views: ALL_VIEWS, stage: true},
  {kind: 'entityOverlays', plural: 'Entity overlays', create: 'Entity overlays', fixture: 'entity-overlays', views: ALL_VIEWS, stage: true},
  {kind: 'tablist', plural: 'Tab lists', create: 'Tab list', fixture: 'tablist', views: ALL_VIEWS, stage: true},
  {kind: 'realDrops', plural: 'Real drops', create: 'Real drops', fixture: 'real-drops', views: ALL_VIEWS, stage: true},
  {kind: 'inventory', plural: 'Inventories', create: 'Inventory', fixture: 'inventory', views: ALL_VIEWS, stage: true},
  {kind: 'nameplate', plural: 'Nameplates', create: 'Nameplate', fixture: 'nameplate', views: ALL_VIEWS, stage: true},
  {kind: 'nametag', plural: 'Nametags', create: 'Nametag', fixture: 'nametag', views: ALL_VIEWS, stage: true},
  {kind: 'marker', plural: 'Markers', create: 'Marker', fixture: 'marker', views: ALL_VIEWS, stage: true},
  {kind: 'names', plural: 'Names', create: 'Names', fixture: 'names', views: EDITOR_ONLY, stage: false},
  {kind: 'strings', plural: 'Strings', create: 'Strings', fixture: 'strings', views: EDITOR_ONLY, stage: false},
  {kind: 'waypoint', plural: 'Waypoints', create: 'Waypoint', fixture: 'waypoint', views: EDITOR_ONLY, stage: false},
  {kind: 'glyph', plural: 'Glyphs', create: 'Glyphs', fixture: 'glyph', views: EDITOR_ONLY, stage: false},
  {kind: 'behavior', plural: 'Behaviors', create: 'Behaviors', fixture: 'behavior', views: EDITOR_ONLY, stage: false},
  {kind: 'presets', plural: 'Presets', create: 'Presets', fixture: 'presets', views: EDITOR_ONLY, stage: false},
  {kind: 'leaderboard', plural: 'Leaderboards', create: 'Leaderboard', fixture: 'leaderboard', views: EDITOR_ONLY, stage: false},
];

const VIEW_LABELS = {visual: 'Visual', preview: 'Preview', code: 'Code', split: 'Split'};
const FIXTURES = path.resolve(__dirname, '..', 'demo', 'fixtures');
const escape = text => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const exact = text => (text instanceof RegExp ? text : new RegExp(`^${escape(text)}$`));

/** The served editor (GLOSS_WEB_URL), without a trailing slash. */
function webUrl() {
  const value = process.env.GLOSS_WEB_URL;
  if (!value) throw new Error('Set GLOSS_WEB_URL to the served editor (python3 -m http.server <port> --directory build/jaspr)');
  return value.replace(/\/$/, '');
}

function playwright() {
  return require(process.env.PLAYWRIGHT_MODULE || 'playwright');
}

/** Headless Chromium (PLAYWRIGHT_CHROMIUM_EXECUTABLE picks the binary). */
async function launch(options = {}) {
  return playwright().chromium.launch({headless: true, ...options,
    ...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE ? {executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE} : {})});
}

/** The viewport the checks use at [width]. */
function viewportFor(width) {
  return {width, height: width <= 390 ? 844 : 900};
}

/**
 * A new page on the editor, ready to edit: the tour dismissed for good
 * unless [tour] is 'keep'. Returns {page, errors}; page errors collect in
 * [errors].
 */
async function openEditor(browser, {width = 1440, height = 1000, tour = 'dismiss', url = webUrl()} = {}) {
  const page = await browser.newPage({viewport: {width, height}});
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await ready(page, {url, tour});
  return {page, errors};
}

/** The tour's dismiss-for-good control, today's or the redesign's. */
function tourDismiss(page) {
  return page.getByRole('button', {name: /^(Never show the guided tour again|Don't show again)$/});
}

/**
 * Goes to the editor and waits until the library and the frame are up. A
 * [url] without a `#` route opens the browser workspace (`#/workspace`),
 * since `#/` is the start page.
 */
async function ready(page, {url = webUrl(), tour = 'dismiss'} = {}) {
  await page.goto(url.includes('#') ? url : `${url.replace(/\/$/, '')}/#/workspace`);
  await page.locator('#hui-library-new-document').waitFor({state: 'attached', timeout: 60000});
  await page.locator(SELECTORS.main).first().waitFor({state: 'attached'});
  if (tour === 'dismiss') await dismissTour(page);
}

/**
 * Closes the first-run tour for good if it shows within [wait] ms (it can
 * open a moment after the frame).
 */
async function dismissTour(page, {wait = 1500} = {}) {
  const button = tourDismiss(page);
  if (!(await button.waitFor({timeout: wait}).then(() => true, () => false))) return false;
  // A dialog opened over the tour keeps it from closing until that dialog
  // is gone.
  if (!(await button.click({timeout: 2000}).then(() => true, () => false))) return false;
  await button.waitFor({state: 'hidden'});
  return true;
}

/** True when the panes are drawers (the top bar has Open library). */
async function isCompact(page) {
  return page.getByRole('button', {name: /^(Open|Close) library$/}).first().isVisible().catch(() => false);
}

async function openPane(page, which) {
  const pane = page.locator(SELECTORS.pane(which)).first();
  // A resize just before lands the drawer layout a frame or two later.
  await settle(page, 300);
  if (await isCompact(page)) {
    const label = which === 'library' ? 'library' : 'inspector';
    const open = page.getByRole('button', {name: `Open ${label}`, exact: true});
    if (await open.isVisible().catch(() => false)) {
      await open.click();
    } else if (label === 'inspector') {
      const more = page.locator('#hui-bar-more-button');
      if (await more.isVisible().catch(() => false)) {
        await more.click();
        await page.locator('[role="menu"]:visible').getByRole('menuitem', {name: 'Open inspector'}).first().click().catch(() => {});
      }
    }
    await pane.waitFor({state: 'visible', timeout: 3000}).catch(() => {});
    await page.waitForTimeout(350);
  }
  return pane;
}

/** The library pane, its drawer opened first on narrow screens. */
function rail(page) {
  return openPane(page, 'library');
}

/** The inspector pane, its drawer opened first on narrow screens. */
function inspector(page) {
  return openPane(page, 'inspector');
}

/** Closes an open library or inspector drawer (a kit sheet: Escape). */
async function closePanes(page) {
  for (let i = 0; i < 2; i++) {
    const open = page.locator('#hui-library-drawer [role="dialog"]:visible, #hui-inspector-drawer [role="dialog"]:visible');
    if (!(await open.count())) break;
    await page.keyboard.press('Escape');
    await page.waitForTimeout(350);
  }
  await page.waitForTimeout(100);
}

/** The visible menu opened by a trigger. */
function openMenu(page) {
  return page.locator('[role="menu"]:visible').last();
}

/** Opens the menu behind [trigger] and runs its item [label]. */
async function menuPick(page, trigger, label) {
  await trigger.click();
  const menu = openMenu(page);
  await menu.waitFor();
  const name = exact(label);
  await menu.getByRole('menuitem', {name}).or(menu.getByRole('menuitemradio', {name})).or(menu.getByRole('menuitemcheckbox', {name})).first().click();
}

/**
 * True when a click on [locator] would land: shown and not folded away
 * (the top bar keeps folded actions laid out but out of reach).
 */
async function clickable(locator) {
  if (!(await locator.isVisible().catch(() => false))) return false;
  if (await locator.click({trial: true, timeout: 1000}).then(() => true, () => false)) return true;
  // A toast over the top bar goes after a few seconds.
  const toasts = locator.page().locator('.arcane-toast:visible');
  if (!(await toasts.count())) return false;
  await toasts.first().waitFor({state: 'hidden', timeout: 10000}).catch(() => {});
  return locator.click({trial: true, timeout: 1000}).then(() => true, () => false);
}

/**
 * Runs a top bar action by its accessible name (Undo, Import JSON,
 * Images, Templates, Settings, Help, Command palette, ...): the visible bar
 * button, else the entry of the visible More actions menu.
 */
async function barAction(page, name) {
  if (name === 'Command palette') name = 'Search';
  const pattern = exact(name);
  const bar = page.locator('header, [role="banner"]').first();
  const direct = bar.getByRole('button', {name: pattern});
  for (let i = 0; i < await direct.count(); i++) {
    if ((await direct.nth(i).getAttribute('aria-haspopup')) === 'menu') continue;
    if (await clickable(direct.nth(i))) {
      await direct.nth(i).click();
      return;
    }
  }
  for (const trigger of ['#hui-file-menu-button', '#hui-help-menu-button', '#hui-bar-more-button']) {
    const button = page.locator(trigger);
    if (!(await button.isVisible().catch(() => false))) continue;
    await button.click();
    const menu = openMenu(page);
    await menu.waitFor();
    const item = menu.getByRole('menuitem', {name: pattern}).or(menu.getByRole('menuitemcheckbox', {name: pattern}));
    if (await item.count()) {
      await item.first().click();
      return;
    }
    await page.keyboard.press('Escape');
    await page.waitForTimeout(150);
    if (await menu.isVisible().catch(() => false)) await button.click();
    await menu.waitFor({state: 'hidden', timeout: 2000}).catch(() => {});
  }
  throw new Error(`barAction: no visible control named ${pattern}`);
}

/** Shows only [plural] documents (or 'All') through the kind picker. */
async function pickKind(page, plural) {
  const library = await rail(page);
  const trigger = library.getByRole('button', {name: /^Filter by kind/}).first();
  await menuPick(page, trigger, plural === 'All' ? 'All kinds' : plural);
  await closePanes(page);
}

/** Creates a document through the library's New document dialog. */
async function newDocument(page, create) {
  await rail(page);
  await page.locator('#hui-library-new-document').click();
  const dialog = page.getByRole('dialog', {name: 'New document'});
  await dialog.waitFor();
  await dialog.getByRole('button', {name: exact(create)}).first().click();
  await dialog.waitFor({state: 'hidden'});
  await closePanes(page);
}

/** Replaces the open document with [source] through Import JSON. */
async function importJson(page, source) {
  await barAction(page, 'Import JSON');
  await page.locator('textarea:visible').last().fill(typeof source === 'string' ? source : JSON.stringify(source));
  await page.getByRole('button', {name: /Replace document$/}).click();
}

/** The JSON of fixture [name] in test/demo/fixtures. */
function fixture(name) {
  return JSON.parse(fs.readFileSync(path.join(FIXTURES, `${name}.json`), 'utf8'));
}

/** Replaces the open document with fixture [name]. */
async function importFixture(page, name) {
  await importJson(page, fixture(name));
}

/**
 * Drops [files] ({name, text}) on the editor as one file drop from the
 * operating system; JSON files become new documents.
 */
async function dropFiles(page, files) {
  await page.evaluate(list => {
    const transfer = new DataTransfer();
    for (const file of list) transfer.items.add(new File([file.text], file.name, {type: 'application/json'}));
    for (const type of ['dragenter', 'dragover', 'drop']) {
      document.body.dispatchEvent(new DragEvent(type, {bubbles: true, cancelable: true, dataTransfer: transfer}));
    }
  }, files);
}

/** The open document's JSON, read from the Export dialog. */
async function exportJson(page) {
  await barAction(page, /^Export .* JSON$/);
  const dialog = page.locator('#hui-export-dialog [role="dialog"]');
  const output = JSON.parse(await dialog.locator('pre').last().innerText());
  await dialog.getByRole('button', {name: 'Close', exact: true}).click();
  return output;
}

/** The centre's view: visual, preview, code or split. */
async function currentView(page) {
  return page.evaluate((hook) => {
    const main = document.querySelector(hook);
    return main && main.dataset.view ? main.dataset.view : null;
  }, HOOKS.main);
}

/**
 * Shows [view] through the top bar's View switch; returns false when the
 * open document does not offer it (the switch lists only what it offers).
 */
async function showView(page, view) {
  if (await currentView(page) === view) return true;
  const label = {visual: 'Edit', preview: 'Preview', code: 'Code', split: 'Split view'}[view];
  const choice = page.locator(`#hui-view-controls [data-choice="${label}"]`);
  if (await choice.count()) {
    await choice.first().click();
  } else {
    // On phones the views are inside the bar's ... menu.
    const more = page.locator('#hui-bar-more-button').filter({visible: true});
    if (!(await more.count())) return false;
    await more.first().click();
    const item = openMenu(page).getByRole('menuitemradio', {name: label, exact: true});
    if (!(await item.count())) {
      await page.keyboard.press('Escape');
      return false;
    }
    await item.first().click();
  }
  return page.locator(SELECTORS.view(view)).first().waitFor({timeout: 2500}).then(() => true, () => false);
}

/** Sets Simple or Advanced when the editor has the mode; false when not. */
async function setMode(page, mode) {
  const label = mode === 'advanced' ? 'Advanced' : 'Simple';
  const current = await page.evaluate(() => window.localStorage.getItem('gloss.mode'));
  if (current === mode) return true;
  const control = page.getByRole('button', {name: label, exact: true}).or(page.getByRole('radio', {name: label, exact: true})).filter({visible: true});
  if (await control.count()) {
    await control.first().click();
  } else {
    // On phones the switch is inside the bar's ... menu.
    const more = page.locator('#hui-bar-more-button').filter({visible: true});
    if (!(await more.count())) return false;
    await more.first().click();
    const item = openMenu(page).getByRole('menuitemradio', {name: label, exact: true});
    if (!(await item.count())) {
      await page.keyboard.press('Escape');
      return false;
    }
    await item.first().click();
  }
  await page.waitForFunction(value => window.localStorage.getItem('gloss.mode') === value, mode).catch(() => {});
  return true;
}

/**
 * Opens a document from the library by its title or id: its row, else the
 * row the library's search finds within [wait] ms (folders fold rows away;
 * a server's event can trail its write), else the command palette.
 */
async function openDocument(page, text, {wait = 3000} = {}) {
  const library = await rail(page);
  const row = library.locator(SELECTORS.docRow).filter({hasText: text});
  if (await row.count()) {
    await row.first().click();
    await closePanes(page);
    return;
  }
  const search = library.locator('#hui-library-search');
  if (await search.isVisible().catch(() => false)) {
    await search.fill(text);
    const found = await row.first().waitFor({timeout: wait}).then(() => true, () => false);
    if (found) await row.first().click();
    // Opening a row closes the drawer on narrow screens; clear the search in it.
    await (await rail(page)).locator('#hui-library-search').fill('');
    await closePanes(page);
    if (found) return;
  }
  await closePanes(page);
  await page.locator('#hui-bar-search').click().catch(() => page.keyboard.press('Control+K'));
  await page.keyboard.type(`Open ${text}`);
  await page.keyboard.press('Enter');
}

/** Clicks open every closed section of [scope] (the inspector by default). */
async function expandSections(page, scope) {
  const root = scope || page.locator(SELECTORS.pane('inspector')).first();
  for (let round = 0; round < 3; round++) {
    const closed = root.locator('details:not([open]) > summary');
    const count = await closed.count();
    if (!count) return;
    for (let i = count - 1; i >= 0; i--) {
      const toggle = closed.nth(i);
      if (await toggle.isVisible().catch(() => false)) await toggle.click({timeout: 2000}).catch(() => {});
    }
    await page.waitForTimeout(150);
  }
}

/** The save bar of a server's workspace (or of one another tab holds). */
function syncBar(page) {
  return page.locator(SELECTORS.syncBar).first();
}

/**
 * A visible sync button by its action: the bar's publish, review,
 * newLink, reconnect, takeOver, retrySave, reload; the Server sheet's
 * disconnect, reconnect, sync, publish, forget; the top bar's open.
 */
function syncAction(page, action) {
  return page.locator(`${HOOKS.syncAction(action)}:visible`).first();
}

/**
 * Waits until the bar shows [status]: connecting, loading, connected,
 * publishing, applied, conflict, rejected, reconnecting, offline, ended,
 * refused or elsewhere.
 */
async function waitSync(page, status, timeout = 20000) {
  await page.locator(SELECTORS.syncStatus(status)).first().waitFor({timeout});
}

// --- Pairing ------------------------------------------------------------------

/** The fake Gloss server (GLOSS_FAKE_SERVER, e.g. http://127.0.0.1:9807), or null. */
function fakeServer() {
  const value = process.env.GLOSS_FAKE_SERVER;
  return value ? value.replace(/\/$/, '') : null;
}

/**
 * Calls the fake server's test control [name] (`pair`, `revoke`, `expire`,
 * `restart`, `direct`, `relay`, `server-on-relay`, `edit`, `latency`,
 * `origins`; `state` with no body): POST [body] as JSON, or GET without one.
 */
async function fakeControl(name, body) {
  const fake = fakeServer();
  if (!fake) throw new Error('Set GLOSS_FAKE_SERVER to the fake Gloss server (dart run tool/fake_server/main.dart)');
  const response = await fetch(`${fake}/__fake/${name}`, body === undefined ? {} : {
    method: 'POST', headers: {'content-type': 'application/json'}, body: JSON.stringify(body),
  });
  const text = await response.text();
  const json = text ? JSON.parse(text) : null;
  if (!response.ok) throw new Error(`fake ${name} returned ${response.status}: ${text}`);
  return json;
}

/** [link] with its origin replaced by GLOSS_WEB_URL's, so it opens the served build. */
function onEditor(link) {
  const hash = link.indexOf('#');
  if (hash < 0) throw new Error(`not an editor link: ${link}`);
  return `${webUrl()}/${link.slice(hash)}`;
}

/**
 * A pairing link: {link, code, tokenId, expiresAt}. From the fake (a fresh
 * one per call; [options] go to its `pair` control: paths, scopes,
 * folders, label, expires), else GLOSS_PAIR_LINK, the link
 * `/gloss editor pair` printed on a live server (good once; tokenId null).
 */
async function pairLink(options = {}) {
  if (fakeServer()) {
    const minted = await fakeControl('pair', options);
    return {...minted, link: onEditor(minted.link)};
  }
  const live = process.env.GLOSS_PAIR_LINK;
  if (!live) throw new Error('Set GLOSS_FAKE_SERVER or GLOSS_PAIR_LINK');
  return {link: onEditor(live), code: live.slice(live.indexOf('#/pair/') + 7), tokenId: null, expiresAt: null};
}

/** The bar's status now, or null when no bar shows. */
async function syncStatusNow(page) {
  return page.evaluate(() => {
    const bar = document.querySelector('[data-sync-bar]');
    return bar ? bar.getAttribute('data-sync-status') : null;
  });
}

/** Opens [link] (a string or a pairLink result) and waits for its pairing card. */
async function openPairCard(page, link) {
  await page.goto(typeof link === 'string' ? link : link.link);
  const card = page.locator('[data-pair-card]');
  await card.waitFor({timeout: 60000});
  return card;
}

/**
 * Pairs through the pairing card: opens [link] (default: a fresh
 * pairLink()), onStep('pairing card'), presses Pair, onStep('server
 * loading') when the bar shows the fill running, waits for Connected and
 * onStep('server connected'). Returns the server id from the address.
 */
async function pairServer(page, {link, onStep = async () => {}, timeout = 120000} = {}) {
  await openPairCard(page, link || await pairLink());
  await onStep('pairing card');
  await page.locator('[data-testid="pair-submit"]').click();
  const problem = page.locator('[data-testid="pair-problem"]');
  await page.locator(SELECTORS.syncBar).or(problem).first().waitFor({timeout: 30000});
  if (await problem.isVisible().catch(() => false)) throw new Error(`pairing refused: ${(await problem.innerText()).replace(/\s+/g, ' ')}`);
  // Connecting comes first; the fill (Loading) can be over before it is seen.
  await page.locator(`${SELECTORS.syncStatus('loading')}, ${SELECTORS.syncStatus('connected')}`).first().waitFor({timeout});
  if (await syncStatusNow(page) === 'loading') await onStep('server loading');
  await waitSync(page, 'connected', timeout);
  await onStep('server connected');
  const match = /#\/server\/([0-9a-f]{16})/.exec(page.url());
  return match ? match[1] : null;
}

/** Goes to the start page (`#/`) and waits for it. */
async function openStart(page, {url = webUrl()} = {}) {
  const start = page.locator('main#gloss-start');
  if (!(await start.isVisible().catch(() => false))) {
    const home = page.locator('#gloss-home');
    if (await clickable(home)) await home.click();
    else await page.goto(`${url}/#/`);
  }
  await start.waitFor({timeout: 60000});
  return start;
}

/** Opens the Server sheet from the top bar (or its ... menu). */
async function openServer(page) {
  const sheet = page.locator('#gloss-server-sheet [role="dialog"]');
  if (await sheet.isVisible().catch(() => false)) return sheet;
  const button = page.locator('#gloss-server-button');
  if (await clickable(button)) await button.click();
  else await barAction(page, /^(Server|Checking|Loading|Connected|Publishing|Published|Needs a choice|Rejected|Not reachable|Not connected|Pairing ended|Refused|Open in another tab)$/);
  await sheet.waitFor({timeout: 4000});
  return sheet;
}

/** Opens Pair a server from the Server button of a workspace with no server paired. */
async function openPairDialog(page) {
  const dialog = page.locator('#gloss-pair-dialog [role="dialog"]');
  if (await dialog.isVisible().catch(() => false)) return dialog;
  const button = page.locator('#gloss-server-button');
  if (await clickable(button)) await button.click();
  else await barAction(page, /^Server$/);
  await dialog.waitFor({timeout: 4000});
  return dialog;
}

/** True when the page has no horizontal scroll. */
async function noHorizontalScroll(page) {
  return page.evaluate(() => document.documentElement.scrollWidth <= innerWidth);
}

/** Fails unless the page fits each of [widths] without horizontal scroll. */
async function assertFitsWidths(page, widths = [1440, 390], height = 1000) {
  for (const width of widths) {
    await page.setViewportSize({width, height});
    if (!(await noHorizontalScroll(page))) throw new Error(`horizontal scroll at ${width} px`);
  }
}

/** Waits for layout to settle (two animation frames after [ms]). */
async function settle(page, ms = 150) {
  await page.waitForTimeout(ms);
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}

/** Closes the open dialog, sheet or menu (Escape, then Close/Cancel). */
async function closeOverlay(page) {
  const open = page.locator('[role="dialog"]:visible, [role="alertdialog"]:visible, [role="menu"]:visible');
  await page.keyboard.press('Escape');
  await page.waitForTimeout(150);
  if (!(await open.count())) return;
  for (const name of ['Close', 'Cancel', 'Keep editing', 'Done']) {
    const button = open.first().getByRole('button', {name, exact: true});
    if (await button.isVisible().catch(() => false)) {
      await button.click();
      await page.waitForTimeout(150);
      if (!(await open.count())) return;
    }
  }
}

module.exports = {
  HOOKS, LEGACY, SELECTORS, KINDS, VIEW_LABELS, FIXTURES,
  webUrl, playwright, launch, viewportFor, openEditor, ready, dismissTour, tourDismiss, isCompact,
  rail, inspector, closePanes, openMenu, menuPick, barAction, pickKind, newDocument,
  importJson, fixture, importFixture, dropFiles, exportJson, currentView, showView, setMode, openDocument, expandSections,
  syncBar, syncAction, waitSync, syncStatusNow, fakeServer, fakeControl, pairLink, openPairCard, pairServer, openStart, openServer, openPairDialog,
  noHorizontalScroll, assertFitsWidths, settle, closeOverlay,
};
