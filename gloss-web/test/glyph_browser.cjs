const assert = require('node:assert/strict');
const shell = require('./support/shell.cjs');

async function main() {
  const browser = await shell.launch();
  try {
    const {page, errors} = await shell.openEditor(browser);
    // Simple hides Shared resources, glyphs among them.
    await shell.setMode(page, 'advanced');
    const source = {schemaVersion: 1, revision: 17, namespace: 'trails', font: 'icons',
      glyphs: [{id: 'coin', image: 'coin.png', height: 8, ascent: 7, frames: 1, fallback: 'C', extension: {keep: true}}],
      overlays: [{id: 'frame', image: 'frame.png', height: 8, ascent: 7, anchor: 'bottom'}],
      space: {enabled: true, range: [-16, 32]},
      waypointStyles: [{id: 'quest', nearDistance: 8, farDistance: 80,
        sprites: [{id: 'near', image: 'near.png', extension: 'retain'}]}], extension: {keep: 3}};
    await shell.importJson(page, source);
    await page.getByLabel('Namespace', {exact: true}).fill('atlas');
    await page.getByLabel('Glyph image', {exact: true}).fill('icons/coin.png');
    await page.getByLabel('Style ID', {exact: true}).fill('destination');
    await page.getByLabel('Sprite image', {exact: true}).fill('waypoints/near.png');
    await page.getByRole('button', {name: 'Add glyph', exact: true}).click();
    await page.getByLabel('Glyph ID', {exact: true}).last().fill('token');
    await page.getByLabel('Glyph image', {exact: true}).last().fill('icons/token.png');
    await page.getByRole('button', {name: 'Add overlay', exact: true}).click();
    await page.getByRole('button', {name: 'Remove overlay', exact: true}).last().click();
    await page.getByRole('button', {name: 'Add sprite', exact: true}).click();
    await page.getByLabel('Sprite image', {exact: true}).last().fill('waypoints/far.png');
    const output = await shell.exportJson(page);
    assert.equal(output.namespace, 'atlas');
    assert.equal(output.revision, 17);
    assert.equal(output.glyphs.length, 2);
    assert.equal(output.glyphs[0].image, 'icons/coin.png');
    assert.equal(output.glyphs[1].id, 'token');
    assert.deepEqual(output.glyphs[0].extension, source.glyphs[0].extension);
    assert.deepEqual(output.overlays, source.overlays);
    assert.deepEqual(output.space, source.space);
    assert.equal(output.waypointStyles[0].id, 'destination');
    assert.equal(output.waypointStyles[0].sprites.length, 2);
    assert.equal(output.waypointStyles[0].sprites[0].extension, 'retain');
    assert.deepEqual(output.extension, source.extension);
    await shell.assertFitsWidths(page, [1440, 390]);
    assert.deepEqual(errors, []);
    console.log('Glyph browser passed: font identity, glyph and overlay lists, waypoint sprites, lossless export, mobile width.');
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
