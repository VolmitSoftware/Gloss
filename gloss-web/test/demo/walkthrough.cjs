// Records a walkthrough of the editor for demos: short WebM clips and
// screenshots at 1600, 768 and 390 px of the first run, creating documents
// of a few kinds, editing in the inspector, switching views and the preview
// stages, and, with a server to pair with, the pairing card, the documents
// arriving, an edit and Publish.
//
//   node test/demo/walkthrough.cjs <editor url> <output folder>
//
// The editor URL defaults to GLOSS_WEB_URL. GLOSS_FAKE_SERVER (the fake
// Gloss server, tool/fake_server) mints a pairing link per width;
// GLOSS_PAIR_LINK (the link `/gloss editor pair` prints) pairs with a real
// server instead, at the first width only (a link works once).
// GLOSS_DEMO_WIDTHS
// (comma separated) limits the widths; GLOSS_DEMO_PARTS=editor or =sync
// records only that part. Each width records one video, cut
// into one clip per scene with ffmpeg (FFMPEG overrides the binary); the
// scene times are in scenes.json beside them. Keep the output folder
// outside the repository.
const fs = require('node:fs');
const path = require('node:path');
const {spawnSync} = require('node:child_process');
const shell = require('../support/shell.cjs');

const url = (process.argv[2] || shell.webUrl()).replace(/\/$/, '');
const out = path.resolve(process.argv[3] || (() => { throw new Error('Usage: node test/demo/walkthrough.cjs <editor url> <output folder>'); })());
const widths = (process.env.GLOSS_DEMO_WIDTHS || '1600,768,390').split(',').map(Number);
const fake = shell.fakeServer();
let liveLinkUsed = false;
const ffmpeg = process.env.FFMPEG || 'ffmpeg';
const parts = new Set((process.env.GLOSS_DEMO_PARTS || 'editor,sync').split(','));

/** A pointer drawn into the page, since recordings do not show the mouse. */
function cursorScript() {
  window.addEventListener('DOMContentLoaded', () => {
    const pointer = document.createElement('div');
    pointer.setAttribute('aria-hidden', 'true');
    pointer.style.cssText = 'position:fixed;left:-40px;top:-40px;width:18px;height:24px;pointer-events:none;z-index:2147483647;filter:drop-shadow(0 1px 2px #000)';
    pointer.innerHTML = '<svg xmlns="http://www.w3.org/2000/svg" width="18" height="24" viewBox="0 0 22 30"><path d="M2 2 L2 24 L8 18 L13 29 L17 27 L12 16 L21 16 Z" fill="white" stroke="black" stroke-width="1.3"/></svg>';
    document.body.append(pointer);
    document.addEventListener('mousemove', event => {
      pointer.style.left = `${event.clientX}px`;
      pointer.style.top = `${event.clientY}px`;
    }, true);
  });
}

class Recorder {
  constructor(page, width, label) {
    this.page = page;
    this.width = width;
    this.label = label;
    this.started = Date.now();
    this.scenes = [];
    this.shots = 0;
    this.mouse = {x: width / 2, y: 200};
  }

  async scene(name, body) {
    const start = (Date.now() - this.started) / 1000;
    try {
      await body();
    } catch (error) {
      console.log(`walkthrough: ${this.width} ${name}: ${error.message.split('\n')[0]}`);
      await shell.closeOverlay(this.page).catch(() => {});
    }
    await this.pause(600);
    this.scenes.push({name, start, end: (Date.now() - this.started) / 1000});
  }

  async pause(ms = 900) {
    await this.page.waitForTimeout(ms);
  }

  async shot(name) {
    const file = path.join(out, `${this.label}-${String(++this.shots).padStart(2, '0')}-${name}.png`);
    await this.page.screenshot({path: file});
  }

  /** Moves the drawn pointer to [locator] and clicks it. */
  async click(locator) {
    const target = locator.first();
    await target.scrollIntoViewIfNeeded().catch(() => {});
    const box = await target.boundingBox();
    if (box) {
      const next = {x: box.x + box.width / 2, y: box.y + box.height / 2};
      await this.page.mouse.move(next.x, next.y, {steps: 14});
      this.mouse = next;
    }
    await target.click();
    await this.pause(500);
  }

  async type(locator, text) {
    await this.click(locator);
    await locator.first().press('ControlOrMeta+A');
    await locator.first().pressSequentially(text, {delay: 55});
    await locator.first().press('Tab');
    await this.pause(900);
  }
}

/**
 * A text field of the inspector that shows text in the game: the first one
 * holding colour codes or several words, else the first one holding a word.
 */
async function textField(page) {
  const pane = await shell.inspector(page);
  const fields = pane.locator('input:not([type]), input[type="text"], textarea').filter({visible: true});
  let fallback = null;
  for (let i = 0; i < await fields.count(); i++) {
    const value = await fields.nth(i).inputValue().catch(() => '');
    if (!value || value.length > 80 || !/[a-z]/i.test(value)) continue;
    if (/[&\u00a7][0-9a-fk-or]|\s\S/i.test(value)) return fields.nth(i);
    fallback = fallback || fields.nth(i);
  }
  return fallback;
}

async function editorWalk(browser, width) {
  const label = String(width);
  const context = await browser.newContext({viewport: shell.viewportFor(width), colorScheme: 'dark', hasTouch: width <= 390,
    recordVideo: {dir: path.join(out, '.raw'), size: shell.viewportFor(width)}});
  await context.addInitScript(cursorScript);
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const rec = new Recorder(page, width, label);

  await rec.scene('first-run', async () => {
    await shell.ready(page, {url, tour: 'keep'});
    const dismiss = shell.tourDismiss(page);
    if (await dismiss.waitFor({timeout: 4000}).then(() => true, () => false)) {
      await rec.pause(1400);
      await rec.shot('tour');
      const next = page.getByRole('button', {name: /^(Next|Go to step \d+ of \d+)$/});
      for (let step = 0; step < 2 && await next.first().isVisible().catch(() => false); step++) {
        await rec.click(next);
        await rec.pause(900);
      }
      await rec.click(dismiss);
    }
    await rec.pause(800);
    await rec.shot('start');
  });

  await rec.scene('create-documents', async () => {
    for (const [create, fixture, name] of [['Scoreboard', 'scoreboard', 'scoreboard'], ['Menu', 'menu-shop', 'menu'], ['Hologram', 'hologram', 'hologram']]) {
      await shell.rail(page);
      await rec.click(page.locator('#hui-library-new-document'));
      const dialog = page.getByRole('dialog', {name: 'New document'});
      await dialog.waitFor();
      await rec.pause(500);
      await rec.click(dialog.getByRole('button', {name: create, exact: true}));
      await shell.closePanes(page);
      await shell.importFixture(page, fixture);
      await rec.pause(1000);
      await rec.shot(`created-${name}`);
    }
  });

  await rec.scene('inspector-edit', async () => {
    const field = await textField(page);
    if (!field) throw new Error('no text field in the inspector');
    await rec.type(field, '&6&lWelcome to Gloss');
    await rec.shot('inspector-edit');
    await shell.closePanes(page);
    await rec.pause(800);
    await rec.shot('edited');
  });

  await rec.scene('views', async () => {
    // Only menus and container previews have Preview, and Split is Advanced: show them on the sample menu.
    await shell.setMode(page, 'advanced');
    await shell.openDocument(page, 'my-menu');
    await rec.pause(800);
    for (const view of ['preview', 'code', 'split', 'visual']) {
      if (!(await shell.showView(page, view))) continue;
      await rec.pause(1200);
      await rec.shot(`view-${view}`);
    }
  });

  await rec.scene('preview-stages', async () => {
    for (const [create, fixture, name] of [['Menu', 'menu-shop', 'menu'], ['MOTD', 'motd', 'motd'], ['Tab list', 'tablist', 'tablist'], ['Inventory', 'inventory', 'inventory'], ['Container preview', 'container-preview', 'container-preview']]) {
      await shell.newDocument(page, create);
      await shell.importFixture(page, fixture);
      await shell.showView(page, 'preview');
      await rec.pause(1500);
      await rec.shot(`stage-${name}`);
    }
    await shell.showView(page, 'visual');
  });

  return finish(context, page, rec);
}

async function syncWalk(browser, width) {
  const label = `${width}-sync`;
  const context = await browser.newContext({viewport: shell.viewportFor(width), colorScheme: 'dark', hasTouch: width <= 390,
    recordVideo: {dir: path.join(out, '.raw'), size: shell.viewportFor(width)}});
  await context.addInitScript(cursorScript);
  const page = await context.newPage();
  page.setDefaultTimeout(20000);
  const rec = new Recorder(page, width, label);
  const link = await shell.pairLink();
  liveLinkUsed = !fake;

  await rec.scene('pairing', async () => {
    // The link opens one pairing card: what the editor may do, then Pair.
    await shell.openPairCard(page, link);
    await rec.pause(1200);
    await rec.shot('pairing-card');
    await rec.click(page.locator('[data-testid="pair-submit"]'));
    await shell.waitSync(page, 'connected', 120000);
    await rec.pause(1000);
    await rec.shot('connected');
  });

  await rec.scene('documents-arrive', async () => {
    const library = await shell.rail(page);
    await rec.pause(1500);
    await rec.shot('document-list');
    const rows = library.locator(shell.SELECTORS.docRow).filter({visible: true});
    if (await rows.count()) await rec.click(rows.first());
    await shell.closePanes(page);
    await rec.pause(1000);
    await rec.shot('document-open');
  });

  await rec.scene('edit-and-publish', async () => {
    const field = await textField(page);
    if (field) {
      // A text per width: an earlier width already published the plain one to the same server.
      await rec.type(field, `&6Edited live at ${width} px`);
    } else {
      // A document without text at its top level: flip its first switch.
      const pane = await shell.inspector(page);
      await rec.click(pane.getByRole('switch').filter({visible: true}));
    }
    await shell.closePanes(page);
    await rec.shot('edited');
    const publish = shell.syncAction(page, 'publish');
    await publish.waitFor();
    await rec.click(publish);
    await shell.waitSync(page, 'applied', 20000).catch(() => {});
    await rec.pause(2500);
    await rec.shot('published');
  });

  return finish(context, page, rec);
}

/** Saves the video, cuts it into one clip per scene and returns the scenes. */
async function finish(context, page, rec) {
  const video = page.video();
  await context.close();
  const full = path.join(out, `${rec.label}-full.webm`);
  await video.saveAs(full);
  await video.delete();
  const probe = spawnSync(ffmpeg, ['-version'], {encoding: 'utf8'});
  for (const scene of rec.scenes) {
    scene.file = `${rec.label}-${scene.name}.webm`;
    if (probe.status !== 0) continue;
    const cut = spawnSync(ffmpeg, ['-y', '-v', 'error', '-ss', scene.start.toFixed(2), '-to', scene.end.toFixed(2), '-i', full,
      '-an', '-c:v', 'libvpx-vp9', '-deadline', 'realtime', '-cpu-used', '8', '-row-mt', '1', '-crf', '34', '-b:v', '0', path.join(out, scene.file)], {encoding: 'utf8'});
    if (cut.status !== 0) console.log(`walkthrough: ${scene.file}: ${cut.stderr.trim().split('\n')[0]}`);
  }
  if (probe.status === 0) fs.rmSync(full);
  console.log(`walkthrough: ${rec.label} ${rec.scenes.map(scene => `${scene.name} ${(scene.end - scene.start).toFixed(1)} s`).join(', ')}`);
  return {label: rec.label, width: rec.width, scenes: rec.scenes};
}

(async () => {
  fs.mkdirSync(out, {recursive: true});
  const browser = await shell.launch();
  const recorded = [];
  try {
    for (const width of widths) {
      if (parts.has('editor')) recorded.push(await editorWalk(browser, width));
      if (!parts.has('sync')) continue;
      if (fake || (process.env.GLOSS_PAIR_LINK && !liveLinkUsed)) recorded.push(await syncWalk(browser, width));
      else if (width === widths[0]) console.log('walkthrough: pairing skipped: set GLOSS_FAKE_SERVER or GLOSS_PAIR_LINK');
      else if (liveLinkUsed) console.log(`walkthrough: ${width} pairing skipped: GLOSS_PAIR_LINK was used at ${widths[0]}`);
    }
  } finally {
    await browser.close();
    fs.rmSync(path.join(out, '.raw'), {recursive: true, force: true});
  }
  fs.writeFileSync(path.join(out, 'scenes.json'), `${JSON.stringify(recorded, null, 2)}\n`);
  console.log(`walkthrough: ${recorded.reduce((sum, each) => sum + each.scenes.length, 0)} clips in ${out}`);
})().catch(error => { console.error(error.message || error); process.exit(1); });
