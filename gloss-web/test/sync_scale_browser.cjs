#!/usr/bin/env node
// Pairs the built editor with a Gloss server at full scale and measures the
// pairing flow: the link opened to its pairing card; Pair to every document
// listed (the library's count reaching the catalog's) and to the fill done
// (the bar's Connected); opening a document (the click to its inspector); a
// server edit to the open document updated on the page; Publish to Applied
// and to the server holding the new body; 60 s of idle traffic (requests,
// body bytes, long tasks); keystroke to second paint in a menu's inspector
// (13 keys: median, p90, max); then pairing over the relay only and over
// the direct address only, each with a restart (the server down 1 s) timed
// from the server up again to Connected.
//
// Against the fake Gloss server (GLOSS_FAKE_SERVER), whose test controls
// make the server edit, the restarts and the single-path links. With
// GLOSS_PAIR_LINK instead (the link `/gloss editor pair` printed on a live
// server) it pairs once and only reads: no server edit, Publish or restart.
//
// Prints one JSON object. Exit 0 only when every document is listed, the
// server edit shows within 1 s, each restart is back within 1 s of the
// server being up, and the idle minute moved no body bytes.
//
// Env:
//   GLOSS_WEB_URL          the served build (python3 -m http.server)
//   GLOSS_FAKE_SERVER      the fake: dart run tool/fake_server/main.dart --port <p>
//                          --data <plugins/Gloss> --editor-url <GLOSS_WEB_URL>
//   GLOSS_PAIR_LINK        a live server's pairing link (read only), without a fake
//   GLOSS_SCALE_WIDTH      viewport width (1600; 390 is a phone with touch)
//   GLOSS_SCALE_HOLOGRAM   the hologram opened and edited on the server (seed-h001)
//   GLOSS_SCALE_MENU       the menu typed into (m0001)
//   GLOSS_SCALE_IDLE_MS    the idle span (60000)
//   GLOSS_RESULTS          where to write the JSON (optional)
//   PLAYWRIGHT_MODULE
'use strict';

const fs = require('node:fs');
const shell = require('./support/shell.cjs');

const WIDTH = Number(process.env.GLOSS_SCALE_WIDTH || 1600);
const HOLOGRAM = process.env.GLOSS_SCALE_HOLOGRAM || 'seed-h001';
const MENU = process.env.GLOSS_SCALE_MENU || 'm0001';
const IDLE_MS = Number(process.env.GLOSS_SCALE_IDLE_MS || 60000);
const RESULTS = process.env.GLOSS_RESULTS;
const FAKE = shell.fakeServer();
const LIVE = !FAKE;
if (!process.env.GLOSS_PAIR_LINK && !FAKE) {
  console.error('Set GLOSS_FAKE_SERVER (the fake Gloss server) or GLOSS_PAIR_LINK (a live server\'s link)');
  process.exit(2);
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const round = value => (value === null || value === undefined ? null : Math.round(value));
const results = {width: WIDTH, server: LIVE ? 'live' : 'fake'};
const failures = [];
function check(name, ok, detail) {
  console.error(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail === undefined ? '' : `: ${detail}`}`);
  if (!ok) failures.push(name);
}

/**
 * Marks kept by the page: the pairing card's first paint, the Pair click,
 * each bar status and each library count, all on the wall clock (Date.now),
 * so they compare with the times this script takes.
 */
function pageProbe() {
  window.__probe = {card: null, pairClick: null, statuses: [], counts: [], longTasks: []};
  const probe = window.__probe;
  let lastStatus = null;
  let lastCount = null;
  const look = () => {
    if (probe.card === null && document.querySelector('[data-pair-card]')) probe.card = performance.now();
    const bar = document.querySelector('[data-sync-bar]');
    const status = bar ? bar.getAttribute('data-sync-status') : null;
    if (status !== lastStatus) {
      lastStatus = status;
      probe.statuses.push([status, Date.now()]);
    }
    // The library's listed total (a drawer on phones shows no header count).
    const count = document.querySelector('[data-pane="library"] [data-listed]');
    const value = count ? Number(count.getAttribute('data-listed')) : null;
    if (value !== null && value !== lastCount) {
      lastCount = value;
      probe.counts.push([value, Date.now()]);
    }
  };
  const observer = new MutationObserver(look);
  window.addEventListener('DOMContentLoaded', () => {
    observer.observe(document.documentElement, {subtree: true, childList: true, attributes: true, attributeFilter: ['data-sync-status', 'data-pair-card', 'data-listed'], characterData: true});
    look();
  });
  probe.stop = () => observer.disconnect();
  probe.start = () => observer.observe(document.documentElement, {subtree: true, childList: true, attributes: true, attributeFilter: ['data-sync-status', 'data-listed'], characterData: true});
  document.addEventListener('click', event => {
    if (event.target instanceof Element && event.target.closest('[data-testid="pair-submit"]')) probe.pairClick = Date.now();
  }, true);
  try {
    new PerformanceObserver(list => {
      for (const entry of list.getEntries()) probe.longTasks.push([Date.now() - (performance.now() - entry.startTime), entry.duration]);
    }).observe({type: 'longtask', buffered: true});
  } catch (_) {
    // No long task timing in this browser.
  }
}

/** Requests, body bytes and WebSocket frames between the page and the server. */
async function watchTraffic(context, page) {
  const origins = [];
  const traffic = [];
  const urls = new Map();
  const cdp = await context.newCDPSession(page);
  await cdp.send('Network.enable');
  const ours = url => origins.some(origin => url.startsWith(origin));
  cdp.on('Network.requestWillBeSent', event => {
    if (!ours(event.request.url)) return;
    urls.set(event.requestId, event.request.url);
    traffic.push({at: Date.now(), kind: 'request', url: event.request.url, bytes: (event.request.postData || '').length});
  });
  cdp.on('Network.dataReceived', event => {
    if (urls.has(event.requestId)) traffic.push({at: Date.now(), kind: 'body', url: urls.get(event.requestId), bytes: event.dataLength});
  });
  cdp.on('Network.webSocketCreated', event => {
    if (ours(event.url)) urls.set(event.requestId, event.url);
  });
  for (const [name, kind] of [['Network.webSocketFrameSent', 'frame sent'], ['Network.webSocketFrameReceived', 'frame received']]) {
    cdp.on(name, event => {
      if (urls.has(event.requestId)) traffic.push({at: Date.now(), kind, url: urls.get(event.requestId), bytes: (event.response.payloadData || '').length});
    });
  }
  return {traffic, watch: origin => origins.push(origin.replace(/\/$/, ''))};
}

/** A context and page for one pairing, with the probe and traffic watch. */
async function openTab(browser) {
  const context = await browser.newContext({viewport: shell.viewportFor(WIDTH), colorScheme: 'dark', hasTouch: WIDTH <= 390});
  await context.addInitScript(pageProbe);
  // Simple leaves flow maps and Shared resources out of the library's count.
  await context.addInitScript(() => {
    try {
      if (!window.localStorage.getItem('gloss.mode')) window.localStorage.setItem('gloss.mode', 'advanced');
    } catch (_) {
      // Storage blocked: the count stays the Simple one.
    }
  });
  const page = await context.newPage();
  page.setDefaultTimeout(30000);
  page.on('pageerror', error => console.error(`pageerror: ${error.message}`));
  const net = await watchTraffic(context, page);
  return {context, page, net};
}

const probeOf = page => page.evaluate(() => {
  const {card, pairClick, statuses, counts, longTasks} = window.__probe;
  return {card, pairClick, statuses, counts, longTasks};
});

/** The first time after [since] the bar showed [status], or null. */
const statusAt = (probe, status, since = 0) => (probe.statuses.find(([value, at]) => value === status && at >= since) || [null, null])[1];

/** The server's catalog count: the fake's state, else the Server sheet's Documents. */
async function catalogCount(page) {
  if (FAKE) return (await shell.fakeControl('state')).documents;
  const sheet = await shell.openServer(page);
  const text = await sheet.innerText();
  await shell.closeOverlay(page);
  const match = /Documents\s+(\d+)/.exec(text);
  return match ? Number(match[1]) : null;
}

/** Documents of kinds this build cannot edit, from the Server sheet (Read only here). */
async function readOnlyCount(page) {
  const sheet = await shell.openServer(page);
  const text = await sheet.innerText();
  await shell.closeOverlay(page);
  const line = /Read only here\s+([^\n]+)/.exec(text);
  if (!line) return 0;
  return [...line[1].matchAll(/(\d+)/g)].reduce((sum, match) => sum + Number(match[1]), 0);
}

/** Pairs [tab] with [link]; returns the pairing timings. */
async function pair(tab, link, label) {
  const {page} = tab;
  await page.goto(link.link);
  await page.locator('[data-pair-card]').waitFor({timeout: 60000});
  await page.locator('[data-testid="pair-submit"]').click();
  // On a phone the library is a drawer: open it as soon as the editor shows,
  // so its count is drawn while the fill runs.
  const openLibrary = page.getByRole('button', {name: 'Open library', exact: true});
  await openLibrary.or(page.locator(shell.SELECTORS.syncStatus('connected'))).first().waitFor({timeout: 180000});
  if (await openLibrary.isVisible().catch(() => false)) await openLibrary.click().catch(() => {});
  await shell.waitSync(page, 'connected', 180000);
  await shell.closePanes(page);
  const catalog = await catalogCount(page);
  const readOnly = await readOnlyCount(page).catch(() => 0);
  const probe = await probeOf(page);
  const expected = catalog === null ? null : catalog - readOnly;
  const finalCount = probe.counts.length ? probe.counts[probe.counts.length - 1][0] : null;
  const listedAt = (probe.counts.find(([value]) => expected !== null && value >= expected) || [null, null])[1];
  const connectedAt = statusAt(probe, 'connected', probe.pairClick);
  const result = {
    linkToCardMs: round(probe.card),
    pairToListedMs: listedAt === null ? null : round(Math.max(0, listedAt - probe.pairClick)),
    pairToConnectedMs: round(connectedAt - probe.pairClick),
    catalog, readOnly, listed: finalCount,
    path: await serverPath(page),
  };
  await shell.closePanes(page);
  check(`${label}: every document listed`, expected !== null && finalCount === expected, `${finalCount} listed, catalog ${catalog}, read only ${readOnly}`);
  return result;
}

/** The path the Server sheet reports (Advanced shows it). */
async function serverPath(page) {
  await shell.setMode(page, 'advanced');
  const sheet = await shell.openServer(page);
  const badge = sheet.locator('[data-testid="server-path"]');
  const text = await badge.innerText({timeout: 2000}).catch(() => null);
  await shell.closeOverlay(page);
  return text;
}

/**
 * Opens [text] through the library search, then clicks its row and times
 * the click to the second frame where the top bar and the inspector (when
 * shown) name it.
 */
async function timeOpen(page, text) {
  const library = await shell.rail(page);
  const search = library.locator('#hui-library-search');
  await search.fill(text);
  const row = library.locator(shell.SELECTORS.docRow).filter({hasText: text}).first();
  await row.waitFor();
  const ms = await row.evaluate((element, wanted) => new Promise(resolve => {
    const started = performance.now();
    element.click();
    const named = () => {
      const bar = document.querySelector('#hui-bar');
      if (!bar || !bar.textContent.includes(wanted)) return false;
      const pane = [...document.querySelectorAll('[data-pane="inspector"]')].find(each => each.checkVisibility());
      return !pane || pane.textContent.includes(wanted) || [...pane.querySelectorAll('input')].some(input => input.value.includes(wanted));
    };
    const frame = () => (named() ? requestAnimationFrame(() => resolve(performance.now() - started)) : performance.now() - started > 15000 ? resolve(-1) : requestAnimationFrame(frame));
    requestAnimationFrame(frame);
  }), text);
  await (await shell.rail(page)).locator('#hui-library-search').fill('');
  await shell.closePanes(page);
  return round(ms);
}

/** Wall clock time when [marker] first shows in the inspector's fields or text. */
function markerSeen(page, marker) {
  return page.evaluate(wanted => new Promise(resolve => {
    const started = Date.now();
    const seen = () => [...document.querySelectorAll('[data-pane="inspector"]')].some(pane =>
      pane.textContent.includes(wanted) || [...pane.querySelectorAll('input, textarea')].some(input => input.value.includes(wanted)));
    const frame = () => (seen() ? resolve(Date.now()) : Date.now() - started > 15000 ? resolve(null) : requestAnimationFrame(frame));
    requestAnimationFrame(frame);
  }), marker);
}

/** The editor's JSON of the open document, through the Export dialog. */
async function openJson(page) {
  await shell.closePanes(page);
  return shell.exportJson(page);
}

/** Keystroke to second paint for each key of [text] typed into [input]. */
async function keystrokes(page, input, text) {
  await input.click();
  await page.keyboard.press(process.platform === 'darwin' ? 'Meta+A' : 'Control+A');
  await page.keyboard.press('Backspace');
  await page.evaluate(() => {
    window.__keys = [];
    document.addEventListener('keydown', () => {
      const started = performance.now();
      requestAnimationFrame(() => requestAnimationFrame(() => window.__keys.push(performance.now() - started)));
    }, true);
  });
  for (const key of text) {
    await page.keyboard.type(key);
    await sleep(150);
  }
  await sleep(200);
  const times = (await page.evaluate(() => window.__keys.slice())).sort((a, b) => a - b);
  const at = share => times[Math.min(times.length - 1, Math.ceil(share * times.length) - 1)];
  return {median: round(at(0.5)), p90: round(at(0.9)), max: round(times[times.length - 1]), n: times.length};
}

/** A 1 s restart, timed from the fake reporting the server up to the bar's Connected. */
async function timeRestart(page, label) {
  const before = Date.now();
  await shell.fakeControl('restart', {downMillis: 1000});
  let upAt = null;
  while (Date.now() - before < 10000) {
    const state = await shell.fakeControl('state');
    if (!state.paths.restarting) {
      upAt = Date.now();
      break;
    }
    await sleep(10);
  }
  await shell.waitSync(page, 'connected', 30000).catch(() => {});
  const probe = await probeOf(page);
  const offline = probe.statuses.find(([status, at]) => at >= before && status !== 'connected');
  const back = statusAt(probe, 'connected', before + 1);
  const result = {
    downShownMs: offline ? round(offline[1] - before) : null,
    downStatus: offline ? offline[0] : null,
    upToConnectedMs: back === null || upAt === null ? null : round(Math.max(0, back - upAt)),
  };
  check(`${label}: restart back under 1 s after up`, result.upToConnectedMs !== null && result.upToConnectedMs < 1000, JSON.stringify(result));
  return result;
}

async function main() {
  const browser = await shell.launch({args: ['--enable-precise-memory-info']});
  try {
    if (FAKE) await shell.fakeControl('latency', {millis: 0});
    // --- Both paths: pairing, idle, open, server edit, typing, Publish.
    const main = await openTab(browser);
    const {page} = main;
    const link = await shell.pairLink();
    // The server's own addresses, from the link's code.
    const code = JSON.parse(Buffer.from(link.code.replace(/^GLS1\./, ''), 'base64url').toString('utf8'));
    for (const origin of [code.directUrl, code.relayUrl]) if (origin) main.net.watch(origin);
    results.pairing = await pair(main, link, 'both paths');
    // What the fill moved: requests and frames sent, and body bytes both ways.
    const fill = main.net.traffic.slice();
    results.pairing.requests = fill.filter(entry => entry.kind === 'request' || entry.kind === 'frame sent').length;
    results.pairing.bodyKB = round(fill.reduce((sum, entry) => sum + entry.bytes, 0) / 1024);
    results.heapMB = await page.evaluate(() => Math.round((performance.memory?.usedJSHeapSize ?? 0) / 1e6));

    // Idle: nothing changes on either side.
    await page.evaluate(() => window.__probe.stop());
    await sleep(2000);
    const idleFrom = Date.now();
    const trafficFrom = main.net.traffic.length;
    await sleep(IDLE_MS);
    const idleTo = Date.now();
    const idle = main.net.traffic.slice(trafficFrom).filter(entry => entry.at <= idleTo);
    const minutes = (idleTo - idleFrom) / 60000;
    const idleLong = (await probeOf(page)).longTasks.filter(([at]) => at >= idleFrom && at <= idleTo);
    const bodyBytes = idle.filter(entry => entry.kind !== 'request').reduce((sum, entry) => sum + entry.bytes, 0)
      + idle.filter(entry => entry.kind === 'request').reduce((sum, entry) => sum + entry.bytes, 0);
    results.idle = {
      seconds: round((idleTo - idleFrom) / 1000),
      requestsPerMin: round(idle.filter(entry => entry.kind === 'request' || entry.kind === 'frame sent').length / minutes),
      bodyBytesPerMin: round(bodyBytes / minutes),
      longTasksPerMin: round(idleLong.length / minutes),
      maxLongTaskMs: round(Math.max(0, ...idleLong.map(([, ms]) => ms))),
      moved: idle.slice(0, 10).map(entry => `${entry.kind} ${entry.url.replace(/^[a-z]+:\/\/[^/]+/, '')} ${entry.bytes}`),
    };
    check('idle traffic 0 body bytes', bodyBytes === 0, `${bodyBytes} B in ${results.idle.seconds} s, ${results.idle.requestsPerMin} requests/min`);
    await page.evaluate(() => window.__probe.start());

    // Open a hologram; the server changes it while it is open.
    results.openDocumentMs = await timeOpen(page, HOLOGRAM);
    check('a document opens', results.openDocumentMs >= 0, `${results.openDocumentMs} ms`);
    if (FAKE) {
      const hologram = await openJson(page);
      await shell.inspector(page);
      const marker = `ServerEdit${Date.now().toString(36)}`;
      hologram.lines = [`&c${marker}`, ...(hologram.lines || []).slice(1)];
      delete hologram.revision;
      const seen = markerSeen(page, marker);
      const sent = Date.now();
      await shell.fakeControl('edit', {kind: 'hologram', id: HOLOGRAM, json: hologram});
      const seenAt = await seen;
      results.serverEditMs = seenAt === null ? null : round(seenAt - sent);
      check('server edit shows on the open document under 1 s', results.serverEditMs !== null && results.serverEditMs < 1000, `${results.serverEditMs} ms`);
      await shell.closePanes(page);
    }

    // Type into a menu's show condition (an Advanced field), then publish it.
    await shell.setMode(page, 'advanced');
    const menuOpenMs = await timeOpen(page, MENU);
    results.openMenuMs = menuOpenMs;
    const pane = await shell.inspector(page);
    const field = pane.getByLabel('Show condition', {exact: true}).first();
    const typed = 'true && false';
    if (await field.count()) {
      results.keystroke = await keystrokes(page, field, typed);
      await page.keyboard.press('Tab');
    } else {
      results.keystroke = null;
    }
    check('keystrokes timed', results.keystroke !== null && results.keystroke.n === typed.length, JSON.stringify(results.keystroke));
    await shell.closePanes(page);
    if (FAKE) {
      const publish = shell.syncAction(page, 'publish');
      await publish.waitFor({timeout: 10000});
      const clickedAt = await publish.evaluate(element => {
        const at = Date.now();
        element.click();
        return at;
      });
      let storedAt = null;
      while (Date.now() - clickedAt < 20000) {
        const state = await shell.fakeControl('state');
        if (JSON.stringify(state.lastPublish.request || {}).includes(typed.replace(/&/g, '\\u0026')) || JSON.stringify(state.lastPublish.request || {}).includes(typed)) {
          storedAt = Date.now();
          break;
        }
        await sleep(10);
      }
      await shell.waitSync(page, 'applied', 20000).catch(() => {});
      const appliedAt = statusAt(await probeOf(page), 'applied', clickedAt);
      results.publish = {toAppliedMs: appliedAt === null ? null : round(appliedAt - clickedAt), toServerMs: storedAt === null ? null : round(storedAt - clickedAt)};
      check('Publish applied', appliedAt !== null && storedAt !== null, JSON.stringify(results.publish));
    }
    results.dom = await page.evaluate(() => ({nodes: document.getElementsByTagName('*').length}));
    await main.context.close();

    // --- One path each: pairing and a restart.
    if (FAKE) {
      for (const paths of ['relay', 'direct']) {
        const tab = await openTab(browser);
        const single = await shell.pairLink({paths});
        const timings = await pair(tab, single, `${paths} only`);
        timings.restart = await timeRestart(tab.page, `${paths} only`);
        results[`${paths}Only`] = timings;
        await tab.context.close();
      }
    }
  } finally {
    await browser.close();
  }
  results.failures = failures;
  const json = JSON.stringify(results, null, 2);
  if (RESULTS) fs.writeFileSync(RESULTS, `${json}\n`);
  console.log(json);
  process.exit(failures.length ? 1 : 0);
}

main().catch(error => {
  console.error(error);
  process.exit(1);
});
