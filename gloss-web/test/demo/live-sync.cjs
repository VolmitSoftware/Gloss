const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const shell = require('../support/shell.cjs');
const {pause,travel,click,type,startCapture,stopCapture} = require('./capture.cjs');
const root = path.resolve(__dirname,'../..');
const scratch = path.join(root,'.qa/demo/captures');
// The owned test server's `/gloss editor pair` link (GLOSS_PAIR_LINK), or the fake's.
const documentName = process.env.GLOSS_DEMO_DOCUMENT || 'showcase';
const coordination = path.join(root,'.qa/demo/live-sync-ready');
const publishTrigger = path.join(root,'.qa/demo/live-sync-publish');
let link;
(async()=>{
  link=await shell.pairLink();
  const browser=await shell.launch();
  try {
    const context=await browser.newContext({viewport:{width:1920,height:1080}});
    const page=await context.newPage();page.setDefaultTimeout(15000);
    const errors=[];page.on('pageerror',error=>errors.push(error.message));
    // The link opens its pairing card; Pair fills the server's workspace.
    await shell.pairServer(page,{link,onStep:async step=>{if(step==='pairing card')await pause(page,1300);}});
    await shell.openDocument(page,documentName);
    // Opening by the library search leaves it focused; the take starts with no focus ring.
    await page.evaluate(()=>document.activeElement?.blur());await pause(page,1800);
    await page.screenshot({path:path.join(scratch,'live-sync-connected.png')});
    const capture=await startCapture(page,'live-sync');await pause(page,1800);
    await page.screenshot({path:path.join(scratch,'live-sync-start.png')});
    // Pages and the show condition are Advanced fields.
    await click(page,page.getByRole('button',{name:'Advanced',exact:true}));
    const pages=page.locator('.hui-extras-row').filter({has:page.locator('[data-slot="field-label"]').getByText('pages',{exact:true})}).locator('input');
    if(!await pages.isVisible())await click(page,page.locator('.hui-extras summary').filter({hasText:'Contents'}));
    const raw=await pages.inputValue();
    const source=JSON.parse(raw);
    assert.ok(Array.isArray(source)&&typeof source[0]?.lines[0]==='string','Actual session must contain a first-page text line');
    const original=JSON.stringify(source[0].lines[0]);
    const index=raw.indexOf(original);
    assert.ok(index>=0,'First-page text must be selectable in the authored pages input');
    await click(page,pages);
    await pages.evaluate((element,selection)=>element.setSelectionRange(selection.start,selection.end),{start:index+1,end:index+original.length-1});
    await pages.pressSequentially('&6Edited live in Gloss',{delay:65});await pages.press('Tab');
    await page.evaluate(()=>document.activeElement?.blur());await pause(page,1800);
    const edited=JSON.parse(await pages.inputValue());
    const expected=structuredClone(source);expected[0].lines[0]='&6Edited live in Gloss';
    assert.deepEqual(edited,expected,'Editing one page line must preserve every other authored page and object');
    const show=page.getByLabel('Show condition',{exact:true});
    await type(page,show,'true');
    await click(page,page.getByRole('button',{name:'Zoom out',exact:true}));
    await page.screenshot({path:path.join(scratch,'live-sync-action.png')});
    fs.writeFileSync(coordination,'staged\n');
    // The fake server has no world to record, so it publishes at once.
    if(!shell.fakeServer()) {
      console.log('Owned editor edits staged; waiting for native capture coordination');
      const deadline=Date.now()+10*60*1000;
      while(!fs.existsSync(publishTrigger)){assert.ok(Date.now()<deadline,'Native capture coordination timed out');await pause(page,500);}
    }
    await click(page,shell.syncAction(page,'publish'));
    await page.locator(shell.SELECTORS.syncStatus('applied')).waitFor({timeout:45000});
    await travel(page,page.locator('body'));await pause(page,5000);await page.screenshot({path:path.join(scratch,'live-sync-end.png')});
    assert.deepEqual(errors,[],'Connected editor raised browser errors');
    const metadata=await stopCapture(page,capture,{id:'live-sync',coverage:['paired server workspace','authored hologram text','explicit Publish to Server','actual server Applied acknowledgement']});
    fs.writeFileSync(path.join(root,'.qa/demo/live-sync-applied'),'applied\n');
    console.log(JSON.stringify(metadata));
    // The status bar's issue count opens the validation panel.
    const issues=page.locator('[aria-controls="hui-validation"]').first();
    if(await issues.count()&&!/No issues/.test(await issues.innerText())) {
      await issues.click();
      console.log(JSON.stringify({validationMessages:await page.locator('#hui-validation [data-validation-message]').allTextContents()}));
    }
    await context.close();
  } finally {await browser.close();}
})().catch(error=>{
  let message=error.message;
  if(link?.code)message=message.split(link.code).join('[private]');
  console.error(message);process.exitCode=1;
});
