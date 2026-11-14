// Button sizes, heights and order on every screen, at 1600, 768 and 390 px.
// The allowed heights are the library's own: a regular button (the New
// document dialog's Cancel) and a dense one (the top bar's), cloned into a
// box outside every screen and measured there. A screen fails on:
//   - a button, field, dropdown or toggle whose height is not one of them;
//   - controls of one row with different heights (a button beside a taller
//     field included);
//   - a group of buttons with uneven gaps;
//   - a primary button that is not the last control of its row, or two
//     primary buttons in one row;
//   - a row of controls that wrapped onto a second line;
//   - icons inside buttons at more than one size;
//   - at 760 px and below, a dialog or form footer whose buttons are not
//     stacked full width with the primary on top.
// A row is the controls laid out by one flex row, through inline wrappers
// and through one-line boxes inside that row. Tabs, menu items, options,
// switches, checkboxes, tree rows and section disclosures are not buttons
// here. A screen that opens a dialog, sheet, menu or drawer is judged inside
// it, not again on the page behind it. The exemptions
// below are not buttons of a row either, each with its reason. Controls
// the editor draws inside a preview frame (zoom, toggles) are checked;
// what the game draws is not.
// Screens: test/support/screens.cjs with its frame screens (every document
// kind in each view, the dialogs, menus, drawers, delete confirmation,
// empty workspace, the start page and pairing; with GLOSS_FAKE_SERVER, the
// pairing card and a server's workspace in each state).
// GLOSS_BUTTONS_WIDTHS (comma separated) limits the widths;
// GLOSS_BUTTONS_SCREENS (a regular expression) checks only the screens
// whose name matches; GLOSS_BUTTONS_WITHIN (a CSS selector list) checks
// only the controls inside those parts; GLOSS_BUTTONS_LIST=1 prints every
// problem; GLOSS_SCREENSHOTS (outside the repository) receives a shot per
// screen.
//
//   dart run jaspr_cli:jaspr build
//   python3 -m http.server <port> --bind 127.0.0.1 --directory build/jaspr
//   GLOSS_WEB_URL=http://127.0.0.1:<port> [GLOSS_FAKE_SERVER=http://127.0.0.1:<fake port>] \
//     PLAYWRIGHT_MODULE=/path/to/node_modules/playwright node test/button_uniformity_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const shell = require('./support/shell.cjs');
const {walkScreens} = require('./support/screens.cjs');

const shots = process.env.GLOSS_SCREENSHOTS;
const widths = (process.env.GLOSS_BUTTONS_WIDTHS || '1600,768,390').split(',').map(Number);
const screens = process.env.GLOSS_BUTTONS_SCREENS ? new RegExp(process.env.GLOSS_BUTTONS_SCREENS) : null;
const within = process.env.GLOSS_BUTTONS_WITHIN || null;

/** Controls that are not buttons of a row, each with its reason. */
const exempt = [
  // The (i) beside a label is a help mark inside the label's text.
  shell.SELECTORS.infoMark,
  // The library's own corner close mark on a dialog or sheet.
  '.arcane-dialog-close', '.arcane-sheet-close',
  // Toasts are passing notices with the library's own close mark.
  '[class*="toast"]', '[class*="sonner"]',
  // Pane splitter handles are drag targets.
  shell.LEGACY.splitter,
  // What the game draws (slots, HUD, world anchors, the canvas artboard).
  shell.SELECTORS.gameDrawn,
  // Marks drawn as SVG (flow-map edges, handles).
  'svg',
].join(', ');

/**
 * The library's regular and dense button heights, cloned from the New
 * document dialog's footer and the top bar into a box outside every screen.
 */
async function reference(page) {
  await shell.rail(page);
  await page.locator('#hui-library-new-document').click();
  const dialog = page.getByRole('dialog', {name: 'New document'});
  await dialog.waitFor();
  const heights = await page.evaluate(() => {
    const root = document.getElementById('arcane-root') || document.body;
    const box = document.createElement('div');
    box.style.cssText = 'position:absolute;left:-10000px;top:0;display:block';
    root.appendChild(box);
    const measure = source => {
      if (!source) return null;
      const clone = source.cloneNode(true);
      clone.removeAttribute('id');
      box.appendChild(clone);
      return Math.round(clone.getBoundingClientRect().height * 10) / 10;
    };
    const visibleDialog = [...document.querySelectorAll('[role="dialog"]')].find(el => el.checkVisibility());
    const regular = visibleDialog && [...visibleDialog.querySelectorAll('.arcane-button[data-size="md"]')].pop();
    const dense = document.querySelector('header .arcane-button[data-size="sm"], [role="banner"] .arcane-button[data-size="sm"]');
    const heights = {
      md: measure(regular || document.querySelector('.arcane-button[data-size="md"]')),
      sm: measure(dense || document.querySelector('.arcane-button[data-size="sm"]')),
    };
    box.remove();
    return heights;
  });
  await shell.closeOverlay(page);
  await shell.closePanes(page);
  return heights;
}

/** Every problem on the page. */
async function scan(page, allowed, skip, selectors, layer) {
  return page.evaluate(({allowed, skip, within, selectors, layer}) => {
    const problems = [];
    const controlSelector = 'button, [role="button"], select, input:not([type="checkbox"]):not([type="radio"]):not([type="range"]):not([type="hidden"]):not([type="color"]):not([type="file"])';
    const notButtons = `[role="tab"], [role="menuitem"], [role="menuitemradio"], [role="menuitemcheckbox"], [role="option"], [role="switch"], [role="checkbox"], [role="treeitem"], summary, [data-arcane-command-item], ${selectors.notButtons}`;
    const shown = el => {
      if (!el.checkVisibility({opacityProperty: true, visibilityProperty: true})) return false;
      const r = el.getBoundingClientRect();
      if (r.width < 2 || r.height < 2) return false;
      // Parked above or left of the page until focused (the skip link).
      if (r.bottom <= 0 || r.right <= 0) return false;
      return !el.closest('[aria-hidden="true"], .sr-only, [inert]');
    };
    const name = el => (el.getAttribute('aria-label') || el.textContent || el.getAttribute('placeholder') || el.value || '').trim().replace(/\s+/g, ' ').slice(0, 30);
    const describe = el => `${el.tagName.toLowerCase()}${el.classList.length ? '.' + [...el.classList].filter(c => c).slice(0, 2).join('.') : ''} "${name(el)}" ${Math.round(el.getBoundingClientRect().height)}`;
    const controls = [...document.querySelectorAll(controlSelector)].filter(el => shown(el) && !el.matches(notButtons) && !el.closest(notButtons) && !el.matches(skip) && !el.closest(skip) && (!within || el.closest(within)) && (!layer || el.closest(layer)));
    const isFlexRow = el => {
      const cs = getComputedStyle(el);
      return (cs.display === 'flex' || cs.display === 'inline-flex') && cs.flexDirection.startsWith('row');
    };
    const rowOf = el => {
      let node = el;
      let root = null;
      const height = el.getBoundingClientRect().height;
      while (node.parentElement && node.parentElement !== document.body) {
        const parent = node.parentElement;
        const display = getComputedStyle(parent).display;
        if (isFlexRow(parent)) {
          root = parent;
          node = parent;
          continue;
        }
        if (display === 'inline' || display === 'contents' || display === 'inline-block') {
          node = parent;
          continue;
        }
        // A one-line box laid out inside a flex row is part of that row.
        if (parent.parentElement && isFlexRow(parent.parentElement) && parent.getBoundingClientRect().height <= height * 1.5 + 1) {
          node = parent;
          continue;
        }
        break;
      }
      return root;
    };

    // Heights.
    for (const el of controls) {
      const h = el.getBoundingClientRect().height;
      if (!allowed.some(a => Math.abs(a - h) <= 0.75)) problems.push(`height ${Math.round(h * 10) / 10} not ${allowed.join(' or ')}: ${describe(el)}`);
    }

    // Rows.
    const rows = new Map();
    for (const el of controls) {
      const root = rowOf(el);
      if (!root) continue;
      if (!rows.has(root)) rows.set(root, []);
      rows.get(root).push(el);
    }
    const where = el => `${el.tagName.toLowerCase()}${el.classList.length ? '.' + [...el.classList].slice(0, 2).join('.') : ''}`;
    for (const [root, items] of rows) {
      if (items.length < 2) continue;
      const rects = items.map(el => el.getBoundingClientRect());
      const heights = rects.map(r => r.height);
      const low = Math.min(...heights);
      if (Math.max(...heights) - low > 1) problems.push(`heights differ in ${where(root)}: ${items.map(describe).join(' + ')}`);
      const tops = rects.map(r => r.top);
      if (Math.max(...tops) - Math.min(...tops) > low / 2) problems.push(`row wraps in ${where(root)}: ${items.map(describe).join(' + ')}`);
      const primaries = items.filter(el => el.matches(selectors.primary));
      if (primaries.length > 1) problems.push(`two primary buttons in ${where(root)}: ${primaries.map(describe).join(' + ')}`);
      for (const primary of primaries) {
        const left = primary.getBoundingClientRect().left;
        const after = items.filter(el => el !== primary && el.getBoundingClientRect().left > left + 1);
        if (after.length) problems.push(`primary not last in ${where(root)}: ${describe(primary)} before ${after.map(describe).join(' + ')}`);
      }
    }

    // Gaps inside a group: a flex row whose children are all one control each.
    const groups = new Set();
    for (const el of controls) {
      let child = el;
      while (child.parentElement && !isFlexRow(child.parentElement)) child = child.parentElement;
      if (child.parentElement) groups.add(child.parentElement);
    }
    for (const group of groups) {
      // A toggle group's items are joined, not spaced.
      if (group.querySelector('.arcane-toggle-group-item')) continue;
      const kids = [...group.children].filter(shown);
      if (kids.length < 3) continue;
      const inner = kids.map(kid => (kid.matches(controlSelector) ? kid : kid.querySelectorAll(controlSelector).length === 1 ? kid.querySelector(controlSelector) : null));
      if (inner.some(el => !el || !controls.includes(el))) continue;
      const rects = inner.map(el => el.getBoundingClientRect()).sort((a, b) => a.left - b.left);
      if (Math.max(...rects.map(r => r.top)) - Math.min(...rects.map(r => r.top)) > 4) continue;
      const gaps = rects.slice(1).map((r, i) => Math.round(r.left - rects[i].right));
      if (Math.max(...gaps) - Math.min(...gaps) > 1) problems.push(`uneven gaps ${gaps.join('/')} in ${where(group)}: ${inner.map(describe).join(' + ')}`);
    }

    // Footers on phones: stacked, full width, primary on top.
    if (window.innerWidth <= 760) {
      const footers = [...document.querySelectorAll(selectors.footer)].filter(shown);
      for (const footer of footers) {
        // A footer row inside another footer is the same footer.
        if (footers.some(other => other !== footer && other.contains(footer))) continue;
        const buttons = controls.filter(el => footer.contains(el));
        if (buttons.length < 2) continue;
        const end = footer.getBoundingClientRect();
        const narrow = buttons.filter(el => el.getBoundingClientRect().width < end.width - 2 - parseFloat(getComputedStyle(footer).paddingLeft) - parseFloat(getComputedStyle(footer).paddingRight));
        if (narrow.length) problems.push(`footer not full width in ${where(footer)}: ${narrow.map(describe).join(' + ')}`);
        const primary = buttons.find(el => el.matches(selectors.primary));
        if (primary && buttons.some(el => el !== primary && el.getBoundingClientRect().top < primary.getBoundingClientRect().top)) problems.push(`footer primary not on top in ${where(footer)}: ${describe(primary)}`);
      }
    }

    // Icon sizes inside buttons.
    const sizes = new Map();
    for (const el of controls) {
      for (const icon of el.querySelectorAll('i, svg')) {
        if (!shown(icon)) continue;
        const w = Math.round(icon.getBoundingClientRect().width);
        if (!sizes.has(w)) sizes.set(w, describe(el));
      }
    }
    if (sizes.size > 1) problems.push(`icons at ${[...sizes.keys()].sort((a, b) => a - b).join(', ')} px: ${[...sizes].map(([w, el]) => `${w} in ${el}`).join('; ')}`);
    return problems;
  }, {allowed, skip, within, selectors, layer});
}

async function run(browser, width, failures, counts, allowedSeen) {
  const options = {viewport: shell.viewportFor(width), colorScheme: 'dark', hasTouch: width <= 390};
  // Measured in a context of its own, so the walk still meets the first-run tour.
  const probeContext = await browser.newContext(options);
  const probe = await probeContext.newPage();
  await shell.ready(probe);
  const heights = await reference(probe);
  await probeContext.close();
  const context = await browser.newContext(options);
  assert.ok(heights.md && heights.sm, `${width}: no regular or dense library button to measure: ${JSON.stringify(heights)}`);
  const allowed = [...new Set([heights.md, heights.sm])];
  allowedSeen.add(`${heights.md}/${heights.sm}`);
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  let count = 0;
  const selectors = {primary: shell.SELECTORS.primary, footer: shell.SELECTORS.footer, notButtons: shell.SELECTORS.notButtons};
  const check = async (name, {layer = false, page: tab = page} = {}) => {
    await tab.mouse.move(0, 0);
    const problems = await scan(tab, allowed, exempt, selectors, layer ? shell.SELECTORS.layer : null);
    counts.push(`${width} ${name}: ${problems.length}`);
    for (const problem of problems) failures.push(`${width} ${name} | ${problem}`);
    if (shots) {
      fs.mkdirSync(shots, {recursive: true});
      await tab.screenshot({path: path.join(shots, `buttons-${width}-${String(++count).padStart(3, '0')}-${name.replace(/[^a-z0-9]+/gi, '-')}.png`)});
    }
  };
  await walkScreens(page, {width, check, frames: true, wanted: name => !screens || screens.test(name), log: line => console.log(`button_uniformity_browser: ${line}`)});
  await context.close();
}

(async () => {
  const browser = await shell.launch();
  const failures = [];
  const counts = [];
  const allowedSeen = new Set();
  try {
    for (const width of widths) {
      const started = Date.now();
      const before = failures.length;
      const seen = counts.length;
      await run(browser, width, failures, counts, allowedSeen);
      console.log(`button_uniformity_browser: ${width} ${counts.length - seen} screens, ${failures.length - before} problems, ${Math.round((Date.now() - started) / 1000)} s`);
    }
  } finally {
    await browser.close();
  }
  console.log(`button_uniformity_browser: library heights ${[...allowedSeen].join(', ')} px`);
  for (const line of counts) console.log(line);
  if (process.env.GLOSS_BUTTONS_LIST) {
    for (const failure of failures) console.log(failure);
  }
  console.log(`button_uniformity_browser: ${failures.length} problems`);
  assert.deepEqual(failures, [], `${failures.length} button problems:\n${failures.slice(0, 80).join('\n')}`);
  console.log('button_uniformity_browser: ok');
})().catch(error => { console.error(error.message || error); process.exit(1); });
