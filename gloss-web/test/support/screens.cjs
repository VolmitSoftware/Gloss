// The screens the layout checks walk at one width. Documents: the starting
// menu, then one document of every kind (New document, then its fixture from
// test/demo/fixtures), each in Visual, Preview, Code and Split where the
// kind offers them; on narrow screens the inspector drawer of each; with
// `expand`, the inspector with every section opened; with `zoom`, each
// frame that has Zoom in, zoomed in twice. With `frames`, the
// frame's other screens too: the first-run tour, the menus, the Import,
// Export, Images, Templates, Settings and Help dialogs, the command
// palette, the shortcuts sheet, the validation panel, a library row menu,
// both drawers, the delete confirmation, the empty workspace, a dropped
// document Simple hides (`advanced documents`) and New document. Then the
// start page and pairing (PAIRING_SCREENS): the start
// page on a fresh profile and the Pair a server dialog (the browser
// workspace's Server button) always; the rest against the fake Gloss server
// (GLOSS_FAKE_SERVER, its built-in documents or any data folder): the
// pairing card (Simple, then Advanced with Details open), the bar while the
// fill runs (the fake answers slowly), connected, the Server sheet, a local
// edit waiting to publish, a conflict (the fake changes the same hologram)
// with its Server sheet, a second tab of the same browser on the same
// server, a restart
// (Not reachable), the start page with that server and the browser
// workspace's work, a spent link, an expired link, and the pairing revoked
// with its Server sheet.
//
// Screen names: `tour`, `start menu visual`, `import dialog`, ...,
// `<kind> <view>`, `<kind> inspector`, `<kind> sections`, `<kind> zoomed`,
// then PAIRING_SCREENS below. [check] (name, {framed, layer, page})
// inspects each screen; `framed` says a preview frame should be on it;
// `layer` says the screen is an open dialog, sheet, menu or drawer, judged
// inside it; `page`, when given, is the tab to judge instead of the walk's
// own (the second tab of `elsewhere`). [wanted] (name) picks.
const shell = require('./shell.cjs');

/** The start page and pairing screens, in the order the walk meets them. */
const PAIRING_SCREENS = [
  'start first visit', 'pair a server dialog', 'pairing card', 'pairing card advanced',
  'server loading', 'server connected', 'server sheet', 'server changed', 'server conflict', 'server sheet conflict',
  'elsewhere', 'server reconnecting', 'start page', 'pairing card used link', 'pairing card expired',
  'pairing card refused origin', 'pairing card no answer', 'pairing card relay down',
  'server ended', 'server sheet ended',
];

/** Pairing screens that show the editor's centre with a document open. */
const SERVER_EDITOR_SCREENS = ['server connected', 'server changed', 'server conflict', 'elsewhere', 'server reconnecting', 'server ended'];

/**
 * The hologram the walk edits on both sides. The walk writes it on the fake
 * first, so a fake loaded from any data folder has it.
 */
const EDITED = {kind: 'hologram', id: 'diner', title: 'Double R Diner'};

/** The body of EDITED with [second] as its second line. */
function editedJson(second) {
  return {
    schemaVersion: 3, name: '&cDouble R Diner', anchor: {world: 'world', position: [0, 64, 0]},
    lines: ['&cDouble R Diner', second],
  };
}

async function walkScreens(page, {width, check, wanted = () => true, frames = false, expand = false, zoom = false, url = shell.webUrl(), log = () => {}}) {
  const visit = async (name, options = {}) => {
    if (!wanted(name)) return;
    await shell.settle(options.page || page);
    await check(name, options);
  };
  const compact = async () => shell.isCompact(page);
  const anyDialog = page.locator('[role="dialog"]:visible, [role="alertdialog"]:visible');
  // Opens something, checks it once it shows, then closes it.
  const overlay = async (name, open, shown = anyDialog) => {
    if (!wanted(name)) return;
    try {
      await open();
      await shown.first().waitFor({timeout: 4000});
      await page.waitForTimeout(250);
      await visit(name, {layer: true});
    } catch (error) {
      log(`${width} ${name}: not opened (${error.message.split('\n')[0]})`);
    }
    await shell.closeOverlay(page);
    await shell.closePanes(page);
  };
  const menu = (name, trigger) => overlay(name, () => trigger.first().click(), page.locator('[role="menu"]:visible'));
  // GLOSS_THEME=light|dark and GLOSS_MODE=simple|advanced pick the walk's
  // theme and the mode the kinds are walked in (default dark, Advanced).
  const theme = process.env.GLOSS_THEME;
  if (theme) await page.addInitScript(value => { try { window.localStorage.setItem('gloss.theme', value); } catch (e) {} }, theme);

  // The start page before anything is paired or written.
  if (wanted('start first visit')) {
    await shell.openStart(page, {url});
    await visit('start first visit');
  }
  await shell.ready(page, {url, tour: 'keep'});
  if (frames) {
    const tour = shell.tourDismiss(page);
    if (await tour.waitFor({timeout: 4000}).then(() => true, () => false)) await visit('tour', {layer: true});
  }
  await shell.dismissTour(page);
  await visit('start menu visual', {framed: true});

  if (frames) {
    await overlay('import dialog', () => shell.barAction(page, 'Import JSON'));
    await overlay('export dialog', () => shell.barAction(page, /^Export .* JSON$/));
    await overlay('images dialog', () => shell.barAction(page, 'Images'));
    await overlay('templates dialog', () => shell.barAction(page, 'Templates'));
    await overlay('settings dialog', () => shell.barAction(page, 'Settings'));
    await overlay('help dialog', () => shell.barAction(page, 'Help'));
    await overlay('command palette', () => shell.barAction(page, 'Command palette'));
    await overlay('shortcuts sheet', async () => {
      await page.evaluate(() => document.activeElement && document.activeElement.blur());
      await page.keyboard.press('?');
    });
    await overlay('validation panel', () => page.locator('.hui-status-problems button').first().click());
    const bar = page.locator('header, [role="banner"]').first();
    // The bar folds actions into its ... menu only when the row runs out of room.
    const more = bar.locator('#hui-bar-more-button').filter({visible: true});
    if (await more.count()) await menu('more menu', more);
    await menu('file menu', page.locator('#hui-file-menu-button'));
    await menu('help menu', page.locator('#hui-help-menu-button'));
    if (wanted('kind menu')) {
      await overlay('kind menu', async () => {
        const library = await shell.rail(page);
        await library.getByRole('button', {name: /^Filter by kind/}).first().click();
      }, page.locator('[role="menu"]:visible'));
    }
    if (wanted('row menu')) {
      await overlay('row menu', async () => {
        const library = await shell.rail(page);
        await library.getByRole('button', {name: /^Actions for /}).first().click();
      }, page.locator('[role="menu"]:visible'));
    }
    if (await compact()) {
      if (wanted('library drawer')) {
        await shell.rail(page);
        await visit('library drawer', {layer: true});
        await shell.closePanes(page);
      }
      if (wanted('inspector drawer')) {
        await shell.inspector(page);
        await visit('inspector drawer', {layer: true});
        await shell.closePanes(page);
      }
    }
    // Delete the starting document: the confirmation, then the empty workspace.
    if (wanted('delete confirm') || wanted('empty workspace')) {
      const library = await shell.rail(page);
      await shell.menuPick(page, library.getByRole('button', {name: /^Actions for /}).first(), /^Delete/);
      await page.locator('[role="alertdialog"]:visible').first().waitFor({timeout: 4000});
      await visit('delete confirm', {layer: true});
      const confirm = page.locator('[role="alertdialog"]:visible [data-confirm], [role="alertdialog"]:visible [data-action-slot="primary"]').first();
      await confirm.click();
      await page.waitForTimeout(300);
      // Deleting the last document leaves the library empty.
      const empty = page.locator('[data-empty-workspace]');
      if (await empty.first().isVisible().catch(() => false)) await visit('empty workspace');
      else log(`${width} empty workspace: not reached`);
    }
    // A file of a kind Simple hides, dropped into the empty workspace in
    // Simple: the toast and the centre with the count and Switch to Advanced.
    if (wanted('advanced documents')) {
      await shell.setMode(page, 'simple');
      await shell.dropFiles(page, [{name: 'pulse.json', text: JSON.stringify(shell.fixture('animation'))}]);
      const hidden = page.locator('#gloss-main[data-advanced-documents]');
      if (await hidden.first().waitFor({timeout: 4000}).then(() => true, () => false)) await visit('advanced documents');
      else log(`${width} advanced documents: not reached`);
    }
    if (wanted('new document')) {
      await shell.rail(page);
      await page.locator('#hui-library-new-document').click();
      await page.getByRole('dialog', {name: 'New document'}).waitFor();
      await visit('new document', {layer: true});
      await shell.closeOverlay(page);
      await shell.closePanes(page);
    }
  }

  // Every kind, view and field: Advanced shows Split, the shared-resource
  // kinds and the expert fields that Simple hides. Simple cannot create the
  // kinds it hides, so those are logged as not created.
  await shell.setMode(page, process.env.GLOSS_MODE === 'simple' ? 'simple' : 'advanced');
  for (const kind of shell.KINDS) {
    const names = [...kind.views.map(view => `${kind.kind} ${view}`), `${kind.kind} inspector`, `${kind.kind} sections`, `${kind.kind} zoomed`];
    if (!names.some(wanted)) continue;
    try {
      await shell.newDocument(page, kind.create);
      if (kind.fixture) await shell.importFixture(page, kind.fixture);
    } catch (error) {
      log(`${width} ${kind.kind}: not created (${error.message.split('\n')[0]})`);
      await shell.closeOverlay(page);
      continue;
    }
    for (const view of kind.views) {
      const name = `${kind.kind} ${view}`;
      if (!wanted(name)) continue;
      if (!(await shell.showView(page, view))) {
        log(`${width} ${name}: view not offered`);
        continue;
      }
      await page.waitForTimeout(view === 'split' ? 300 : 100);
      await visit(name, {framed: kind.stage && view !== 'code'});
    }
    await shell.showView(page, 'visual');
    // With `zoom`, the frame zoomed in twice: catches zoom that overflows it.
    // Zoom in sits in the frame's header (or its ... menu when the header
    // folds), beside the body that carries the preview frame hook.
    if (zoom && wanted(`${kind.kind} zoomed`)) {
      // The header folds its buttons a frame after a view switch; a click
      // aimed before that lands on whichever button slides under it.
      await shell.settle(page, 400);
      const stage = page.locator(`section:has(${shell.SELECTORS.previewFrame})`).filter({visible: true}).last();
      const zoomIn = stage.getByRole('button', {name: 'Zoom in', exact: true}).filter({visible: true});
      const more = stage.locator('[data-action-slot="more"] button').filter({visible: true});
      let zoomed = 0;
      for (let i = 0; i < 2; i++) {
        if (await zoomIn.count()) {
          if (!(await zoomIn.first().isEnabled())) break;
          await zoomIn.first().click();
          zoomed++;
        } else if (await more.count()) {
          await more.first().click();
          const item = shell.openMenu(page).getByRole('menuitem', {name: 'Zoom in', exact: true});
          if (!(await item.count())) {
            await page.keyboard.press('Escape');
            break;
          }
          await item.first().click();
          zoomed++;
        }
      }
      if (zoomed) {
        await visit(`${kind.kind} zoomed`, {framed: true});
        const reset = stage.getByRole('button', {name: 'Reset view', exact: true}).filter({visible: true});
        if (await reset.count()) await reset.first().click();
        if (await page.evaluate(() => !!document.fullscreenElement)) {
          log(`${width} ${kind.kind} zoomed: left fullscreen on`);
          await page.evaluate(() => document.exitFullscreen());
        }
      } else {
        log(`${width} ${kind.kind} zoomed: no Zoom in`);
      }
    }
    if (await compact() && wanted(`${kind.kind} inspector`)) {
      await shell.inspector(page);
      await visit(`${kind.kind} inspector`, {layer: true});
      await shell.closePanes(page);
    }
    if (expand && wanted(`${kind.kind} sections`)) {
      const pane = await shell.inspector(page);
      await shell.expandSections(page, pane);
      await visit(`${kind.kind} sections`, {layer: await compact()});
      await shell.closePanes(page);
    }
  }

  await walkPairing(page, {width, wanted, visit, overlay, url, log});
}

/** Waits until the server's workspace is online again (Connected, or Needs a choice). */
function backOnline(page, timeout = 30000) {
  return page.locator(`${shell.SELECTORS.syncStatus('connected')}, ${shell.SELECTORS.syncStatus('conflict')}`).first().waitFor({timeout});
}

/** Loads [link] afresh (the mode is read once per load) in [mode]. */
async function freshCard(page, link, mode) {
  await page.evaluate(value => window.localStorage.setItem('gloss.mode', value), mode);
  await page.goto('about:blank');
  return shell.openPairCard(page, link);
}

/**
 * Presses Pair on the open card and waits for the problem that asks for a
 * New link. The fake allows 10 pairing attempts a minute per address; over
 * that, it presses Pair again until the answer is the link's own.
 */
async function pairRefused(page) {
  const newLink = page.locator('#pair-new-link');
  for (let attempt = 0; attempt < 12; attempt++) {
    await page.locator('[data-testid="pair-submit"]').click();
    await page.locator('[data-testid="pair-problem"]').waitFor({timeout: 20000});
    if (await newLink.waitFor({timeout: 2000}).then(() => true, () => false)) return;
    await page.waitForTimeout(5000);
  }
  throw new Error(`no New link: ${(await page.locator('[data-testid="pair-problem"]').innerText()).replace(/\s+/g, ' ')}`);
}

/** Presses Pair on the open card and waits for its problem line. */
async function pairProblem(page) {
  await page.locator('[data-testid="pair-submit"]').click();
  await page.locator('[data-testid="pair-problem"]').waitFor({timeout: 30000});
}

/** The pairing screens of PAIRING_SCREENS after `start first visit`. */
async function walkPairing(page, {width, wanted, visit, overlay, url, log}) {
  if (!PAIRING_SCREENS.some(wanted)) return;
  const step = async (name, body) => {
    try {
      await body();
    } catch (error) {
      log(`${width} ${name}: ${error.message.split('\n')[0]}`);
      await shell.closeOverlay(page).catch(() => {});
    }
  };
  // The browser workspace holds work of its own for the start page.
  await step('browser workspace', async () => {
    await shell.ready(page, {url});
    await shell.newDocument(page, 'Hologram');
  });
  // Not paired, the Server button opens Pair a server.
  await step('pair a server dialog', () => overlay('pair a server dialog', async () => {
    await shell.openPairDialog(page);
    await page.locator('#pair-dialog-link').waitFor();
  }, page.locator('#gloss-pair-dialog [role="dialog"]')));
  const serverNames = PAIRING_SCREENS.filter(name => !['start first visit', 'pair a server dialog'].includes(name));
  if (!serverNames.some(wanted)) return;
  if (!shell.fakeServer()) {
    log(`${width} pairing screens skipped: GLOSS_FAKE_SERVER is not set`);
    return;
  }
  await shell.fakeControl('latency', {millis: 0});
  const first = await shell.pairLink();
  let serverId = null;
  await step('pairing card', async () => {
    if (wanted('pairing card')) {
      await freshCard(page, first, 'simple');
      await visit('pairing card');
    }
    if (wanted('pairing card advanced')) {
      const card = await freshCard(page, first, 'advanced');
      await card.locator('summary').filter({hasText: 'Details'}).first().click();
      await card.locator('[data-testid="pair-fingerprint"]').waitFor();
      await visit('pairing card advanced');
    }
    // The fill runs slowly enough to be seen: every request waits 400 ms.
    if (wanted('server loading')) await shell.fakeControl('latency', {millis: 400});
    serverId = await shell.pairServer(page, {link: first, onStep: async name => {
      if (name === 'server loading') {
        await visit('server loading');
        await shell.fakeControl('latency', {millis: 0});
      }
      if (name === 'server connected') await visit('server connected', {framed: false});
    }});
  });
  await shell.fakeControl('latency', {millis: 0});
  if (!serverId) return;
  const serverHash = `${url}/#/server/${serverId}`;

  await step('server sheet', () => overlay('server sheet', () => shell.openServer(page)));
  await step('server changed', async () => {
    if (!['server changed', 'server conflict', 'server sheet conflict'].some(wanted)) return;
    await shell.fakeControl('edit', {kind: EDITED.kind, id: EDITED.id, json: editedJson('&7Cherry pie')});
    await shell.openDocument(page, EDITED.title, {wait: 15000});
    const local = await shell.exportJson(page);
    if (local.name !== editedJson('').name) throw new Error(`${EDITED.title} did not open`);
    local.lines = [...(local.lines || []), '&7Changed here'];
    await shell.importJson(page, local);
    await shell.syncAction(page, 'publish').waitFor({timeout: 10000});
    await visit('server changed', {framed: true});
    // The server changes the same hologram: the bar asks for a choice.
    await shell.fakeControl('edit', {kind: EDITED.kind, id: EDITED.id, json: editedJson('&7Changed on the server')});
    await shell.waitSync(page, 'conflict');
    await visit('server conflict', {framed: true});
    const sheet = await shell.openServer(page);
    await sheet.locator('[data-sync-choice="server"]').first().waitFor();
    await page.waitForTimeout(250);
    await visit('server sheet conflict', {layer: true});
    // Settle it with the server's copy, so the screens below start clean.
    await sheet.locator('[data-sync-choice="server"]').first().click();
    await shell.closeOverlay(page);
    await shell.waitSync(page, 'connected');
  });
  await step('elsewhere', async () => {
    if (!wanted('elsewhere')) return;
    // A second tab of this browser on the same server: the first keeps it.
    const second = await page.context().newPage();
    try {
      second.setDefaultTimeout(15000);
      await second.goto(serverHash);
      await shell.waitSync(second, 'elsewhere');
      await second.locator(`${shell.HOOKS.syncAction('takeOver')}:visible`).waitFor();
      if (!(await second.locator('.hui-shell-body[inert]').count())) log(`${width} elsewhere: the panes are not inert`);
      if (await shell.syncStatusNow(page) === 'elsewhere') log(`${width} elsewhere: the first tab lost the server`);
      await visit('elsewhere', {page: second});
    } finally {
      await second.close();
    }
  });
  await step('server reconnecting', async () => {
    if (!wanted('server reconnecting')) return;
    await shell.fakeControl('restart', {downMillis: 6000});
    await shell.waitSync(page, 'reconnecting', 10000);
    await visit('server reconnecting');
    await backOnline(page);
  });
  await step('start page', async () => {
    if (!wanted('start page')) return;
    await shell.openStart(page, {url});
    await page.locator('[data-continue="server"]').waitFor();
    await page.locator('[data-continue="local"]').waitFor();
    await visit('start page');
  });
  await step('pairing card used link', async () => {
    if (!wanted('pairing card used link')) return;
    await shell.openPairCard(page, first);
    await pairRefused(page);
    await visit('pairing card used link');
  });
  await step('pairing card expired', async () => {
    if (!wanted('pairing card expired')) return;
    const expired = await shell.pairLink();
    await shell.fakeControl('expire', {tokenId: expired.tokenId});
    await shell.openPairCard(page, expired);
    await pairRefused(page);
    await visit('pairing card expired');
  });
  await step('pairing card refused origin', async () => {
    if (!wanted('pairing card refused origin')) return;
    // Direct only, so the relay cannot pair it from the refused address.
    const link = await shell.pairLink({paths: 'direct'});
    await shell.fakeControl('origins', {allowed: ['https://elsewhere.example']});
    try {
      await shell.openPairCard(page, link);
      await pairProblem(page);
      await visit('pairing card refused origin');
    } finally {
      await shell.fakeControl('origins', {allowed: [new URL(url).origin]});
    }
  });
  await step('pairing card no answer', async () => {
    if (!wanted('pairing card no answer')) return;
    const link = await shell.pairLink();
    await shell.fakeControl('direct', {down: true});
    await shell.fakeControl('server-on-relay', {online: false});
    try {
      await shell.openPairCard(page, link);
      await pairProblem(page);
      await visit('pairing card no answer');
    } finally {
      await shell.fakeControl('direct', {down: false});
      await shell.fakeControl('server-on-relay', {online: true});
    }
  });
  await step('pairing card relay down', async () => {
    if (!wanted('pairing card relay down')) return;
    const link = await shell.pairLink();
    await shell.fakeControl('relay', {down: true});
    try {
      await shell.openPairCard(page, link);
      await page.locator('[data-testid="pair-relay-down"]').waitFor({timeout: 15000});
      await visit('pairing card relay down');
    } finally {
      await shell.fakeControl('relay', {down: false});
    }
  });
  await step('server ended', async () => {
    if (!wanted('server ended') && !wanted('server sheet ended')) return;
    await page.goto(serverHash);
    await backOnline(page);
    await shell.fakeControl('revoke', {tokenId: first.tokenId});
    await shell.waitSync(page, 'ended');
    await visit('server ended');
    await overlay('server sheet ended', async () => {
      await shell.openServer(page);
      await page.locator('#server-new-link').waitFor();
    });
  });
}

module.exports = {walkScreens, PAIRING_SCREENS, SERVER_EDITOR_SCREENS};
