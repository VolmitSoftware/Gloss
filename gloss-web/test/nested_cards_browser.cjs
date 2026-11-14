// No card inside a card, on every screen at 1600, 768 and 390 px. A box is
// a card when it draws a visible border on at least three sides, or a
// background that differs from the page's together with a corner radius or
// a full border. A screen fails for each card that sits inside another
// card. Not cards: form controls (inputs, text areas, dropdowns, buttons,
// toggles, tabs), badges, chips, key caps and colour swatches, table and
// list rows, tooltips, popovers, menus and toasts, skeleton bars, and
// everything the game draws (the game screen and its windows, the WebGL
// stage, the menu canvas artboard, the container preview card) and the
// flow map's diagram nodes. A flow-map node is the one surface on its board:
// a card inside a node counts. A dialog, sheet or drawer is a surface of its
// own: what is inside it is judged up to it, and a screen that opens one is
// judged inside it, not again on the page behind it. The page colour is the
// centre's (`#gloss-main`).
// Screens: test/support/screens.cjs with its frame screens, for every
// document kind the inspector with all its sections opened, the start page
// and, with GLOSS_FAKE_SERVER, pairing. The count per screen is printed.
// GLOSS_CARDS_WIDTHS (comma separated) limits the widths;
// GLOSS_CARDS_SCREENS (a regular expression) checks only the screens whose
// name matches; GLOSS_CARDS_LIST=1 prints every nested card;
// GLOSS_SCREENSHOTS (outside the repository) receives a shot per screen.
//
//   dart run jaspr_cli:jaspr build
//   python3 -m http.server <port> --bind 127.0.0.1 --directory build/jaspr
//   GLOSS_WEB_URL=http://127.0.0.1:<port> [GLOSS_FAKE_SERVER=http://127.0.0.1:<fake port>] \
//     PLAYWRIGHT_MODULE=/path/to/node_modules/playwright node test/nested_cards_browser.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const shell = require('./support/shell.cjs');
const {walkScreens} = require('./support/screens.cjs');

const shots = process.env.GLOSS_SCREENSHOTS;
const widths = (process.env.GLOSS_CARDS_WIDTHS || '1600,768,390').split(',').map(Number);
const screens = process.env.GLOSS_CARDS_SCREENS ? new RegExp(process.env.GLOSS_CARDS_SCREENS) : null;

const selectors = {
  notCards: [
    'input', 'textarea', 'select', 'button', '[role="button"]', '[role="combobox"]', '[role="switch"]', '[role="checkbox"]', '[role="radio"]', '[role="slider"]',
    '[role="tablist"]', '[role="tab"]', '[role="group"]:has(> .arcane-toggle-group)', '.arcane-toggle-group',
    '[class*="badge"]', '[class*="chip"]', 'kbd', '[class*="swatch"]',
    'tr', '[role="row"]', '[role="option"]', '[role="menuitem"]', '[role="treeitem"]',
    '[role="tooltip"]', '[role="menu"]', '[role="listbox"]', '[class*="popover"]', '[class*="hover-card"]', '[class*="dropdown-content"]', '[class*="toast"]', '[class*="sonner"]',
    '[class*="skeleton"]',
    // A progress bar is a meter: its fill sits inside its track.
    '[role="progressbar"]',
    'canvas', 'svg',
    // What the game draws.
    shell.SELECTORS.gameWindows,
    // The flow map's board is a diagram: its nodes are boxes on it. Only the
    // board is exempt; its list view and its inspector are judged.
    '.hui-flow-board',
  ].join(', '),
  // A flow-map node or sink: judged inside, as the only surface on the board.
  nodes: '.hui-flow-node, .hui-flow-sink',
  layers: shell.SELECTORS.layer,
  page: shell.SELECTORS.main,
};

/** Every card inside another card on the page. */
async function scan(page, layerOnly = false) {
  return page.evaluate(({notCards, nodes, layers, page: pageSelector, layerOnly}) => {
    // Any computed colour (rgb(), color(srgb ...) from color-mix, oklab(),
    // ...) as [r, g, b, a], read back from a one-pixel canvas.
    const ink = document.createElement('canvas').getContext('2d', {willReadFrequently: true});
    const parsed = new Map();
    const parse = text => {
      if (!text || text === 'transparent') return [0, 0, 0, 0];
      if (!parsed.has(text)) {
        ink.clearRect(0, 0, 1, 1);
        ink.fillStyle = 'rgba(0, 0, 0, 0)';
        ink.fillStyle = text;
        ink.fillRect(0, 0, 1, 1);
        const [r, g, b, a] = ink.getImageData(0, 0, 1, 1).data;
        parsed.set(text, [r, g, b, a / 255]);
      }
      return parsed.get(text);
    };
    const opaque = el => {
      for (let node = el; node; node = node.parentElement) {
        const c = parse(getComputedStyle(node).backgroundColor);
        if (c[3] > 0.95) return c;
      }
      return [255, 255, 255, 1];
    };
    const page = opaque(document.querySelector(pageSelector) || document.body);
    const tinted = cs => {
      if (cs.backgroundImage && cs.backgroundImage !== 'none') return true;
      const c = parse(cs.backgroundColor);
      if (c[3] < 0.04) return false;
      return [0, 1, 2].some(i => Math.abs(c[i] * c[3] + page[i] * (1 - c[3]) - page[i]) > 2);
    };
    const isCard = el => {
      const cs = getComputedStyle(el);
      const sides = ['Top', 'Right', 'Bottom', 'Left'].filter(side =>
        parseFloat(cs[`border${side}Width`]) > 0 && !['none', 'hidden'].includes(cs[`border${side}Style`]) && parse(cs[`border${side}Color`])[3] > 0.04).length;
      const round = ['TopLeft', 'TopRight', 'BottomRight', 'BottomLeft'].some(corner => parseFloat(cs[`border${corner}Radius`]) > 0);
      return sides >= 3 || (tinted(cs) && (round || sides === 4));
    };
    const shown = el => el.checkVisibility({opacityProperty: true, visibilityProperty: true}) && el.getBoundingClientRect().width >= 4 && el.getBoundingClientRect().height >= 4;
    const name = el => {
      const classes = [...el.classList].filter(c => !/^is-|^has-/.test(c)).slice(0, 2).join('.');
      const r = el.getBoundingClientRect();
      return `${el.tagName.toLowerCase()}${el.id && !/\d/.test(el.id) ? '#' + el.id : ''}${classes ? '.' + classes : ''} ${Math.round(r.width)}x${Math.round(r.height)}`;
    };
    const cards = new Set([...document.body.querySelectorAll('*')].filter(el => !el.closest(notCards) && shown(el) && isCard(el)));
    const nested = [];
    for (const el of cards) {
      if (el.matches(layers) || (layerOnly && !el.closest(layers))) continue;
      for (let up = el.parentElement; up && up !== document.body && !up.matches(layers); up = up.parentElement) {
        if (cards.has(up)) {
          nested.push(`${name(el)} in ${name(up)}`);
          break;
        }
      }
    }
    // Inside a flow-map node the board's exemption ends: the node is the one
    // surface, so any card in it is nested.
    const inNode = notCards.split(', ').filter(selector => selector !== '.hui-flow-board').join(', ');
    for (const node of document.body.querySelectorAll(nodes)) {
      if (!shown(node) || (layerOnly && !node.closest(layers))) continue;
      for (const el of node.querySelectorAll('*')) {
        if (!el.closest(inNode) && shown(el) && isCard(el)) nested.push(`${name(el)} in ${name(node)}`);
      }
    }
    return nested;
  }, {...selectors, layerOnly});
}

/** The scan finds what it looks for, on a page built for it. */
async function selfTest(browser) {
  const page = await browser.newPage({viewport: {width: 800, height: 600}});
  const card = inner => `<div style="border:1px solid #888;padding:8px">${inner}</div>`;
  const cases = [
    ['card in card', card(card('x')), 1],
    ['tinted in bordered', card('<div style="background:#334;border-radius:6px;padding:4px">x</div>'), 1],
    ['dialog is a layer', card(`<div role="dialog">${card('x')}</div>`), 0],
    ['heading and separator', card('<h3>Title</h3><hr style="border:0;border-top:1px solid #888">x'), 0],
    ['game screen exempt', card(`<div class="hui-gloss-game">${card('x')}</div>`), 0],
    ['flow node on the board', card(`<div class="hui-flow-board">${'<article class="hui-flow-node" style="border:1px solid #888;padding:8px">x</article>'}</div>`), 0],
    ['card inside a flow node', card(`<div class="hui-flow-board"><article class="hui-flow-node" style="border:1px solid #888;padding:8px">${card('x')}</article></div>`), 1],
  ];
  for (const [label, html, expected] of cases) {
    await page.setContent(`<body style="margin:0;background:#111"><main class="hui-center">${html}</main></body>`);
    const nested = await scan(page);
    assert.equal(nested.length, expected, `self test "${label}" found ${JSON.stringify(nested)}`);
  }
  await page.close();
}

async function run(browser, width, failures, counts) {
  const context = await browser.newContext({viewport: shell.viewportFor(width), colorScheme: 'dark', hasTouch: width <= 390});
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  let count = 0;
  const check = async (name, {layer = false, page: tab = page} = {}) => {
    await tab.mouse.move(0, 0);
    const nested = await scan(tab, layer);
    counts.push(`${width} ${name}: ${nested.length}`);
    for (const each of nested) failures.push(`${width} ${name} | ${each}`);
    if (shots) {
      fs.mkdirSync(shots, {recursive: true});
      await tab.screenshot({path: path.join(shots, `cards-${width}-${String(++count).padStart(3, '0')}-${name.replace(/[^a-z0-9]+/gi, '-')}.png`)});
    }
  };
  await walkScreens(page, {width, check, frames: true, expand: true, wanted: name => !screens || screens.test(name), log: line => console.log(`nested_cards_browser: ${line}`)});
  await context.close();
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
      console.log(`nested_cards_browser: ${width} ${counts.length - seen} screens, ${failures.length - before} nested cards, ${Math.round((Date.now() - started) / 1000)} s`);
    }
  } finally {
    await browser.close();
  }
  for (const line of counts) console.log(line);
  if (process.env.GLOSS_CARDS_LIST) {
    for (const failure of failures) console.log(failure);
  }
  console.log(`nested_cards_browser: ${failures.length} nested cards`);
  assert.deepEqual(failures, [], `${failures.length} nested cards:\n${failures.slice(0, 80).join('\n')}`);
  console.log('nested_cards_browser: ok');
})().catch(error => { console.error(error.message || error); process.exit(1); });
