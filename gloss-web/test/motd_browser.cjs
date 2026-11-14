const assert = require('node:assert/strict');
const shell = require('./support/shell.cjs');

async function fill(scope, label, value) {
  const input = scope.getByLabel(label, {exact: true});
  await input.fill(value);
  await input.press('Tab');
}
async function textEquals(page, selector, expected) {
  await page.waitForFunction(({selector, expected}) =>
    document.querySelector(selector)?.textContent.trim() === expected, {selector, expected});
}
/** Waits for the game frame's status line to read [expected]; its own text, not its (i) tip. */
async function statusEquals(page, expected) {
  await page.waitForFunction(expected => {
    const status = document.querySelector('.hui-motd-stage .ui-stage-status');
    const label = status?.querySelector(':scope > .ui-tip-label') || status;
    if (!label) return false;
    const own = [...label.childNodes].filter(node => node.nodeType === Node.TEXT_NODE).map(node => node.textContent).join('');
    return own.trim() === expected;
  }, expected);
}
/** A section title starting with [title]; a tip's (i) button can follow it. */
function heading(page, title) {
  return page.locator('.ui-section-title', {hasText: new RegExp(`^${title}(?!\\d)`)});
}
/** The AppSection titled [title]: the innermost section owning that summary. */
function section(page, title) {
  return page.locator('.ui-section').filter({has: page.locator('summary').filter({has: heading(page, title)})}).last();
}
/** Opens the closed disclosure titled [title] inside [scope]. */
async function expand(page, scope, title) {
  const summary = scope.locator('summary').filter({has: heading(page, title)}).first();
  if (await summary.evaluate(node => !node.parentElement.open)) await summary.click();
}
/** A ToggleChoice option: the button [option] in the group named [group]. */
function choice(scope, group, option) {
  return scope.getByRole('group', {name: group, exact: true}).getByRole('button', {name: option, exact: true});
}
async function main() {
  const browser = await shell.launch();
  try {
    const {page, errors} = await shell.openEditor(browser);
    // The response policy, entry selectors and the request simulator are Advanced only.
    if (!(await shell.setMode(page, 'advanced'))) {
      await page.evaluate(() => window.localStorage.setItem('gloss.mode', 'advanced'));
      await shell.ready(page);
    }
    const fixture = {
      schemaVersion: 1, revision: 7, custom: {retained: true},
      state: 'event', favicon: 'fallback.png', icons: ['default.png'],
      rotation: {mode: 'sequence', intervalSeconds: 30, custom: 'rotation'},
      links: [{type: 'website', url: 'https://example.org/original', custom: 'original-link'}],
      serverLinks: {enabled: false, custom: 'links', links: [
        {label: 'Rules', url: 'https://example.org/rules', custom: 'link'},
      ]},
      entries: [
        {lines: ['Event A', 'Online {{ server.online }}'], icons: ['event.png'],
          sample: ['Online {{ server.online }}'], sampleMode: 'hide', weight: 3, custom: 'entry',
          select: {hostnames: ['*.example.org'], minProtocol: 768, maxProtocol: 999,
            zone: 'UTC', startTime: '18:00', endTime: '02:00', days: [5],
            states: ['event'], minOnline: 1, maxOnline: 100, custom: 'selector'},
          counts: {onlineMode: 'offset', onlineValue: -3, maximumMode: 'fixed', maximumValue: 200,
            hide: false, custom: 'counts'}},
        {lines: ['Event B'], icons: ['second.png'],
          select: {hostnames: ['*.example.org'], minProtocol: 768, maxProtocol: 999,
            states: ['maintenance'], minOnline: 1, maxOnline: 100},
          custom: {untouched: true}},
      ],
    };
    await shell.importJson(page, fixture);
    await page.waitForSelector('.hui-inspector-body.is-motd');
    await choice(page, 'Rotation mode', 'first').click();
    await fill(page, 'Rotation interval seconds', '90');
    await fill(page, 'Server state', 'maintenance');
    await fill(page, 'Document icon set', 'default.png\nseason.png');
    await page.getByRole('switch', {name: 'Publish server links', exact: true}).focus();
    await page.keyboard.press('Space');
    await fill(section(page, 'Independent link 1'), 'Address (Independent link 1)', 'https://example.org/updated-rules');
    const entry = section(page, 'Entry 1');
    await expand(page, entry, 'More');
    await fill(entry, 'Hostnames', '*.example.org\nstatus.example.net');
    await fill(entry, 'Matching states', 'maintenance');
    await fill(entry, 'Icon set', 'event-new.png');
    await choice(entry, 'Sample mode', 'replace').click();
    await fill(entry, 'Online count value', '5');
    await entry.getByRole('switch', {name: 'Saturday', exact: true}).focus();
    await page.keyboard.press('Space');

    await expand(page, page.locator('.hui-motd-stage'), 'Status request simulator');
    await page.getByRole('switch', {name: 'Simulate request selection', exact: true}).click();
    await fill(page, 'Requested hostname', 'play.example.org');
    await fill(page, 'Client protocol number', '800');
    await fill(page, 'Request instant', '2026-10-09T19:00:00Z');
    await fill(page, 'Real online count', '17');
    await fill(page, 'Real maximum count', '100');
    await statusEquals(page, 'Selected entry 1; 2 eligible responses.');
    await textEquals(page, '.hui-motd-stage .hui-motd-players', '22/200');
    await textEquals(page, '.hui-motd-stage .hui-motd-line:last-child', 'Online 17');
    assert.equal(await page.getByText('Resolved icon: event-new.png', {exact: true}).isVisible(), true);
    await page.locator('.hui-motd-stage .hui-motd-count').focus();
    await page.locator('.hui-motd-sample-line:visible').waitFor();
    assert.equal(await page.locator('.hui-motd-sample-line:visible').innerText(), 'Online 17');
    await page.keyboard.press('Tab');

    await choice(page, 'Rotation mode', 'sequence').click();
    await page.getByRole('button', {name: 'Refresh', exact: true}).click();
    await statusEquals(page, 'Selected entry 2; 2 eligible responses.');
    await textEquals(page, '.hui-motd-stage .hui-motd-line', 'Event B');
    assert.equal(await page.getByText('Resolved icon: second.png', {exact: true}).isVisible(), true);
    await choice(page, 'Rotation mode', 'first').click();
    await fill(page, 'Requested hostname', 'wrong.example.net');
    await statusEquals(page, 'No eligible response; the original ping is retained.');
    await textEquals(page, '.hui-motd-stage .hui-motd-line', 'Original server response retained.');
    assert.equal(await page.getByText('Published server links: 1', {exact: true}).isVisible(), true);
    await fill(page, 'Requested hostname', 'play.example.org');
    await page.getByRole('switch', {name: 'MOTD feature enabled', exact: true}).click();
    await statusEquals(page, 'No eligible response; the original ping is retained.');
    assert.equal(await page.getByText('Published server links: 1', {exact: true}).isVisible(), true);
    await choice(page, 'Server platform', 'spigot').click();
    assert.equal(await page.getByText('Published server links: 0', {exact: true}).isVisible(), true);
    await page.getByRole('switch', {name: 'MOTD feature enabled', exact: true}).click();
    await statusEquals(page, 'No eligible response; the original ping is retained.');
    await choice(page, 'Server platform', 'paper').click();
    await statusEquals(page, 'Selected entry 1; 2 eligible responses.');

    await page.setViewportSize({width: 390, height: 1000});
    await shell.inspector(page);
    await fill(entry, 'Online count value', '7');
    await textEquals(page, '.hui-motd-stage .hui-motd-players', '24/200');
    await entry.getByRole('switch', {name: 'Hide player counts', exact: true}).focus();
    await page.keyboard.press('Space');
    await textEquals(page, '.hui-motd-stage .hui-motd-players', '???');
    await page.keyboard.press('Space');
    await textEquals(page, '.hui-motd-stage .hui-motd-players', '24/200');
    assert.equal(await shell.noHorizontalScroll(page), true);
    await shell.closePanes(page);
    await page.setViewportSize({width: 1440, height: 1000});
    const output = await shell.exportJson(page);
    const expected = structuredClone(fixture);
    expected.state = 'maintenance';
    expected.icons.push('season.png');
    expected.rotation.mode = 'first';
    expected.rotation.intervalSeconds = 90;
    expected.serverLinks.enabled = true;
    expected.serverLinks.links[0].url = 'https://example.org/updated-rules';
    expected.entries[0].select.hostnames.push('status.example.net');
    expected.entries[0].select.states = ['maintenance'];
    expected.entries[0].select.days.push(6);
    expected.entries[0].icons = ['event-new.png'];
    expected.entries[0].sampleMode = 'replace';
    expected.entries[0].counts.onlineValue = 7;
    assert.deepEqual(output, expected);
    await shell.assertFitsWidths(page, [390, 1440]);
    assert.deepEqual(errors, []);
    console.log('MOTD browser: typed policy edits, request selection, sequence rotation, sampled counts, icons, links, platform limits, keyboard/mobile edits and lossless export passed.');
  } finally {
    await browser.close();
  }
}
main().catch(error => {console.error(error); process.exitCode = 1;});
