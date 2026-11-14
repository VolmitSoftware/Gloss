// Every preview fits its frame: at 1600 (panes beside the centre), 768 and
// 390 px (panes as drawers), in the Visual, Preview and Split views, a
// screen fails when
//   - a preview frame (`[data-preview-frame]`: the menu canvas, the 3D
//     preview, the game screen, the flow-map board, each kind's stage) is
//     larger inside than its box (scrollHeight > clientHeight + 1 or
//     scrollWidth > clientWidth + 1), so it scrolls or spills;
//   - a box between the frame and the centre's scroller, or inside the
//     frame, clips or scrolls what it holds (overflow hidden, clip, auto or
//     scroll with content larger than the box);
//   - a game window (chest, MOTD, tab list, HUD, the WebGL stage) has an
//     edge outside the visible part of its frame;
//   - the content a pan/zoom surface draws (the menu canvas, the container
//     card; the surface writes it as `data-content-box`, its edges as
//     fractions of the surface) has an edge outside the surface's visible
//     box, measured on screen through every transform and CSS zoom. This is
//     what a cropped first frame looks like: after a document opens, is
//     imported or switched to, or the viewport changes, before anyone
//     touches the camera. Screens zoomed by hand are exempt;
//   - a window drawn through the fit box (`[data-window-fit]`) is zoomed
//     above its natural size, or the space kept for it differs from its
//     scaled size;
//   - content scaled with `transform: scale` (the zoom of today's game
//     preview) has an edge outside the visible part of its frame, or keeps
//     a layout box of a different size than it paints, so the frame cannot
//     grow to fit it.
// The WebGL host and the pan/zoom artboard clip their own content on
// purpose, and form controls, text cut with an ellipsis and screen-reader
// text clip their own text; those are not counted.
// Screens: every document view of test/support/screens.cjs, each frame
// zoomed in twice where it has Zoom in and, with GLOSS_FAKE_SERVER, the
// pairing screens that show a server's document (SERVER_EDITOR_SCREENS);
// then a switch walk (`fit ...` screens): a small new menu, the shop menu
// and a container preview imported, each switched back to, the container's
// Preview view, and the viewport narrowed and restored.
// GLOSS_FIT_WIDTHS (comma separated) limits the widths; GLOSS_FIT_SCREENS
// (a regular expression) checks only the screens whose name matches;
// GLOSS_FIT_LIST=1 prints every problem; GLOSS_SCREENSHOTS (outside the
// repository) receives a shot per screen.
//
//   dart run jaspr_cli:jaspr build
//   python3 -m http.server <port> --bind 127.0.0.1 --directory build/jaspr
//   GLOSS_WEB_URL=http://127.0.0.1:<port> [GLOSS_FAKE_SERVER=http://127.0.0.1:<fake port>] \
//     PLAYWRIGHT_MODULE=/path/to/node_modules/playwright node test/preview_fit_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const shell = require('./support/shell.cjs');
const {walkScreens, SERVER_EDITOR_SCREENS} = require('./support/screens.cjs');

const shots = process.env.GLOSS_SCREENSHOTS;
const widths = (process.env.GLOSS_FIT_WIDTHS || '1600,768,390').split(',').map(Number);
const screens = process.env.GLOSS_FIT_SCREENS ? new RegExp(process.env.GLOSS_FIT_SCREENS) : null;

/** Every problem in the previews on the page. */
async function scan(page, selectors, {content = true} = {}) {
  return page.evaluate(({roots, windows, ownClipList, scrollers, windowFit, content}) => {
    const ownClip = `${ownClipList}, button, input, textarea, select, [role="combobox"], .sr-only, svg`;
    const problems = [];
    const full = document.fullscreenElement;
    const scroller = full || document.querySelector(scrollers) || document.scrollingElement;
    const shown = el => el.checkVisibility({opacityProperty: true, visibilityProperty: true}) && el.getBoundingClientRect().width >= 2;
    const name = el => {
      const classes = [...el.classList].filter(c => !/^is-/.test(c)).slice(0, 2).join('.');
      return `${el.tagName.toLowerCase()}${classes ? '.' + classes : ''}`;
    };
    const size = el => `${el.clientWidth}x${el.clientHeight} holds ${el.scrollWidth}x${el.scrollHeight}`;
    const larger = el => el.scrollHeight > el.clientHeight + 1 || el.scrollWidth > el.clientWidth + 1;
    const clips = el => {
      const cs = getComputedStyle(el);
      return cs.overflowX !== 'visible' || cs.overflowY !== 'visible';
    };
    const cutsOwn = el => el.closest(ownClip) || getComputedStyle(el).textOverflow === 'ellipsis';
    const scaleOf = el => {
      const t = getComputedStyle(el).transform;
      if (!t || t === 'none') return 1;
      const m = /matrix\(([^)]+)\)/.exec(t);
      if (!m) return 1;
      const [a, b] = m[1].split(',').map(Number);
      return Math.hypot(a, b);
    };
    const seen = new Set();
    const report = (el, text) => {
      const key = `${text}|${name(el)}`;
      if (seen.has(el) && seen.has(key)) return;
      seen.add(el);
      seen.add(key);
      problems.push(text);
    };
    // The part of [root] that shows on screen: its box cut by every
    // clipping box up to the scroller.
    const visibleBox = (root, from) => {
      let box = root.getBoundingClientRect();
      box = {left: box.left, top: box.top, right: box.right, bottom: box.bottom};
      for (let el = from.parentElement; el && el !== scroller && el !== document.body; el = el.parentElement) {
        if (!clips(el)) continue;
        const r = el.getBoundingClientRect();
        box = {left: Math.max(box.left, r.left), top: Math.max(box.top, r.top), right: Math.min(box.right, r.right), bottom: Math.min(box.bottom, r.bottom)};
      }
      return box;
    };
    const outside = (r, box) => {
      const out = [];
      if (r.bottom > box.bottom + 1) out.push(`bottom ${Math.round(r.bottom - box.bottom)}px`);
      if (r.right > box.right + 1) out.push(`right ${Math.round(r.right - box.right)}px`);
      if (r.left < box.left - 1) out.push(`left ${Math.round(box.left - r.left)}px`);
      if (r.top < box.top - 1) out.push(`top ${Math.round(box.top - r.top)}px`);
      return out;
    };
    const found = [...document.querySelectorAll(roots)].filter(el => shown(el) && (!full || full.contains(el)));
    for (const root of found) {
      // The frame: never larger inside than outside.
      if (larger(root) && !root.matches(ownClipList)) report(root, `frame ${name(root)} scrolls or spills: ${size(root)}`);
      // Anything inside the frame, or between it and the centre's scroller,
      // that clips or scrolls its content.
      const inside = [...root.querySelectorAll('*')].filter(el => shown(el) && !cutsOwn(el));
      const above = [];
      for (let el = root.parentElement; el && el !== scroller && el !== document.body && (!full || full.contains(el)); el = el.parentElement) above.push(el);
      for (const el of [...inside, ...above]) {
        if (el === root || !clips(el) || !larger(el) || found.includes(el)) continue;
        report(el, `${name(el)} clips ${inside.includes(el) ? 'inside' : 'around'} ${name(root)}: ${size(el)}`);
      }
      // Windows and scaled content wholly inside the visible part of the
      // outermost frame.
      if (found.some(other => other !== root && other.contains(root))) continue;
      for (const win of [...(root.matches(windows) ? [root] : []), ...root.querySelectorAll(windows)].filter(shown)) {
        const out = outside(win.getBoundingClientRect(), visibleBox(root, win));
        if (out.length) report(win, `${name(win)} ${Math.round(win.getBoundingClientRect().width)}x${Math.round(win.getBoundingClientRect().height)} cut off in ${name(root)} (${out.join(', ')} outside)`);
      }
      for (const el of [root, ...root.querySelectorAll('*')]) {
        const scale = scaleOf(el);
        if (Math.abs(scale - 1) < 0.001 || !shown(el) || el.closest(ownClipList)) continue;
        const r = el.getBoundingClientRect();
        // Icons and small marks that grow on hover are not content.
        if (r.width < 24 || r.height < 24) continue;
        const out = outside(r, visibleBox(root, el));
        if (out.length) report(el, `${name(el)} scaled ${scale.toFixed(2)} by transform cut off in ${name(root)} (${out.join(', ')} outside)`);
        const keeps = el.offsetHeight;
        if (keeps && Math.abs(keeps - r.height) > 1) report(el, `${name(el)} scaled ${scale.toFixed(2)} by transform keeps ${keeps}px for ${Math.round(r.height)}px`);
      }
      // What a pan/zoom surface draws: wholly inside the surface's visible
      // box, unless the camera was moved by hand.
      for (const surface of content ? root.querySelectorAll('[data-content-box]') : []) {
        if (!shown(surface)) continue;
        const [left, top, right, bottom] = surface.dataset.contentBox.split(' ').map(Number);
        const r = surface.getBoundingClientRect();
        const drawn = {left: r.left + left * r.width, top: r.top + top * r.height, right: r.left + right * r.width, bottom: r.top + bottom * r.height};
        const out = outside(drawn, visibleBox(surface, surface));
        // At the fit floor (`data-fit-floor`, 50%) a big menu stays readable,
        // centred, and the user pans: a cut even on both sides is meant.
        const box = visibleBox(surface, surface);
        const even = (a, b) => Math.abs(a - b) <= 2;
        const centred = even(box.left - drawn.left, drawn.right - box.right) && even(box.top - drawn.top, drawn.bottom - box.bottom);
        if (out.length && surface.hasAttribute('data-fit-floor') && centred) continue;
        if (out.length) report(surface, `content ${Math.round(drawn.right - drawn.left)}x${Math.round(drawn.bottom - drawn.top)} cut off in ${name(surface)} (${out.join(', ')} outside)`);
      }
      // A window scaled to fit: never above its natural size, and the space
      // kept for it is its scaled size.
      for (const host of root.querySelectorAll(windowFit)) {
        const win = host.firstElementChild;
        if (!win || !shown(win)) continue;
        const zoom = Number(getComputedStyle(win).zoom || '1');
        if (zoom > 1.0001) report(win, `${name(win)} scaled above its natural size (zoom ${zoom})`);
        const gap = host.getBoundingClientRect().height - win.getBoundingClientRect().height;
        if (Math.abs(gap) > 1) report(host, `${name(win)} keeps ${Math.round(host.getBoundingClientRect().height)}px for ${Math.round(win.getBoundingClientRect().height)}px`);
      }
    }
    return {frames: found.length, problems};
  }, {...selectors, content});
}

const selectors = {
  roots: shell.SELECTORS.previewFrame,
  windows: shell.SELECTORS.windows,
  ownClipList: shell.SELECTORS.ownClip,
  scrollers: shell.SELECTORS.main,
  windowFit: shell.SELECTORS.windowFit,
};

/** The scan finds what it looks for, on pages built for it. */
async function selfTest(browser) {
  const page = await browser.newPage({viewport: {width: 800, height: 600}});
  const frame = inner => `<main class="hui-center" style="height:600px;overflow:auto"><div data-preview-frame style="width:300px;height:200px;overflow:hidden">${inner}</div></main>`;
  const cases = [
    ['inner scrollbar', '<div data-preview-frame style="width:300px;height:200px;overflow:auto"><div style="height:400px"></div></div>', /scrolls or spills/],
    ['transform zoom', frame('<div class="z" style="width:280px;height:180px;transform:scale(2);transform-origin:0 0"></div>'), /scaled 2\.00 by transform cut off/],
    ['fit box above natural', frame('<div data-window-fit style="height:100px"><div style="zoom:1.5;height:100px"></div></div>'), /above its natural size/],
    ['cropped content', frame('<canvas data-content-box="-0.2000 0.1000 0.9000 1.3000" style="width:280px;height:180px"></canvas>'), /content 308x216 cut off in canvas \(bottom 54px, left 56px outside\)/],
    ['content in a zoomed surface', frame('<div style="zoom:0.5"><canvas data-content-box="0.1000 0.1000 0.9000 0.9000" style="width:560px;height:360px"></canvas></div>'), null],
    ['fits', frame('<div style="width:280px;height:180px"></div>'), null],
  ];
  for (const [label, html, expected] of cases) {
    await page.setContent(`<body style="margin:0">${html}</body>`);
    const {problems} = await scan(page, selectors);
    if (expected) assert.ok(problems.some(p => expected.test(p)), `self test "${label}" found ${JSON.stringify(problems)}`);
    else assert.deepEqual(problems, [], `self test "${label}"`);
  }
  await page.close();
}

async function run(browser, width, failures, counts) {
  const context = await browser.newContext({viewport: shell.viewportFor(width), colorScheme: 'dark', hasTouch: width <= 390});
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  let count = 0;
  const check = async (name, {framed = false, page: tab = page} = {}) => {
    await tab.mouse.move(0, 0);
    await shell.settle(tab, 250);
    // A camera zoomed in by hand may run past the surface.
    const {frames, problems} = await scan(tab, selectors, {content: !/ zoomed$/.test(name)});
    if (framed && !frames) problems.push('no preview frame');
    counts.push(`${width} ${name}: ${problems.length}`);
    for (const problem of problems) failures.push(`${width} ${name} | ${problem}`);
    if (shots) {
      fs.mkdirSync(shots, {recursive: true});
      await tab.screenshot({path: path.join(shots, `fit-${width}-${String(++count).padStart(3, '0')}-${name.replace(/[^a-z0-9]+/gi, '-')}.png`)});
    }
  };
  const documentView = name => /( (visual|preview|split|zoomed))$/.test(name) || SERVER_EDITOR_SCREENS.includes(name);
  const log = line => console.log(`preview_fit_browser: ${line}`);
  await walkScreens(page, {width, check, zoom: true, wanted: name => documentView(name) && (!screens || screens.test(name)), log});
  await switchScreens(page, {width, check, wanted: name => !screens || screens.test(name), log});
  await context.close();
}

/**
 * Documents of different sizes opened, imported and switched between, and
 * the viewport narrowed and restored: every first frame fits. Documents are
 * switched through their address (`#/workspace/<id>/document/<id>`, the
 * store's openDocument, as a library click does).
 */
async function switchScreens(page, {width, check, wanted, log}) {
  const names = ['fit new menu', 'fit import shop', 'fit import container', 'fit switch menu', 'fit switch shop',
    'fit switch container', 'fit container preview', 'fit narrowed', 'fit restored'];
  if (!names.some(wanted)) return;
  const create = kind => shell.KINDS.find(each => each.kind === kind).create;
  const visit = async (name, framed = true) => {
    if (!wanted(name)) return;
    await check(name, {framed});
  };
  const address = () => page.evaluate(() => location.hash);
  const open = async hash => {
    await page.evaluate(value => { location.hash = value; }, hash);
    await page.waitForFunction(value => location.hash === value, hash);
    await shell.settle(page, 300);
  };
  try {
    await shell.showView(page, 'visual');
    await shell.newDocument(page, create('menu'));
    const small = await address();
    await visit('fit new menu');
    await shell.newDocument(page, create('menu'));
    await shell.importFixture(page, 'menu-shop');
    const shop = await address();
    await visit('fit import shop');
    await shell.newDocument(page, create('containerPreview'));
    await shell.importFixture(page, 'container-preview');
    const container = await address();
    await visit('fit import container');
    await open(small);
    await visit('fit switch menu');
    await open(shop);
    await visit('fit switch shop');
    await open(container);
    await visit('fit switch container');
    if (await shell.showView(page, 'preview')) await visit('fit container preview');
    await shell.showView(page, 'visual');
    await open(shop);
    const size = page.viewportSize();
    await page.setViewportSize({width: Math.max(320, Math.round(size.width * 0.6)), height: Math.round(size.height * 0.75)});
    await shell.settle(page, 400);
    await visit('fit narrowed');
    await page.setViewportSize(size);
    await shell.settle(page, 400);
    await visit('fit restored');
  } catch (error) {
    log(`${width} switch walk stopped: ${error.message.split('\n')[0]}`);
  }
}

(async () => {
  const browser = await shell.launch();
  const failures = [];
  const counts = [];
  try {
    await selfTest(browser);
    for (const width of widths) {
      const started = Date.now();
      const before = failures.length;
      const seen = counts.length;
      await run(browser, width, failures, counts);
      console.log(`preview_fit_browser: ${width} ${counts.length - seen} screens, ${failures.length - before} problems, ${Math.round((Date.now() - started) / 1000)} s`);
    }
  } finally {
    await browser.close();
  }
  for (const line of counts) console.log(line);
  if (process.env.GLOSS_FIT_LIST) {
    for (const failure of failures) console.log(failure);
  }
  console.log(`preview_fit_browser: ${failures.length} problems`);
  assert.deepEqual(failures, [], `${failures.length} preview fit problems:\n${failures.slice(0, 80).join('\n')}`);
  console.log('preview_fit_browser: ok');
})().catch(error => { console.error(error.message || error); process.exit(1); });
