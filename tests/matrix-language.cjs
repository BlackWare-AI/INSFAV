/* Offline browser fixtures; no real Instagram access. */
const fs=require('node:fs'),path=require('node:path'),http=require('node:http'),assert=require('node:assert/strict'),{chromium}=require('playwright');
let checks=0;const check=(ok,label)=>{assert.ok(ok,label);checks++;console.log('PASS '+label);};
(async()=>{
 const root=path.resolve(__dirname,'../app/src/main/assets');const server=http.createServer((req,res)=>{const relative=req.url.split('?')[0],file=path.join(root,relative.startsWith('/thumb/')?'logo.webp':relative==='/'?'index.html':relative);if(!file.startsWith(root+path.sep)||!fs.existsSync(file)){res.writeHead(404).end();return;}res.setHeader('Content-Type',file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':file.endsWith('.webp')?'image/webp':'text/html');res.end(fs.readFileSync(file));});await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({executablePath:process.env.CHROME_BIN,args:['--no-sandbox','--no-zygote','--single-process']});
 try{
 const page=await browser.newPage({viewport:{width:393,height:820},hasTouch:true,isMobile:true}),errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>{window.calls=[];window.Android={request(raw){const input=JSON.parse(raw);calls.push(input);if(input.command==='bootstrap'){const collections=['A','B'].map((name,g)=>({name:name+' folder',items:Array.from({length:10001},(_,i)=>({pageId:(g?'b':'a')+'very.long_username_'+i,name:'Private display name '+i}))})),profiles={};for(const group of collections)for(const i of group.items)profiles[i.pageId]={file:'fixture',version:1};nativeEvent('bootstrap',{raw:JSON.stringify(collections),profiles,uiPreferences:window.savedUI||{columns:0,language:'en'}});}if(input.command==='ui-preferences')window.savedUI={columns:input.columns,language:input.language};}};});
 await page.goto('http://127.0.0.1:'+server.address().port);await page.locator('#splash').waitFor({state:'hidden'});await page.emulateMedia({reducedMotion:'reduce'});await page.locator('.folder-header').first().click();
 const width=await page.locator('#list').evaluate(el=>el.clientWidth);
 check(await page.locator('.account').first().evaluate(el=>el.getBoundingClientRect().height===68),'default list keeps 68px rows');
 await page.evaluate(()=>openSettings());check(await page.locator('#language-choice').boundingBox().then(b=>b.x>250&&b.y<80),'language choice is top-right in Settings');
 check(await page.locator('#display-settings').evaluate(el=>el===document.querySelector('#settings .screen-content').lastElementChild),'layout preference is at bottom of Settings');
 await page.locator('#language-choice').selectOption('tr');await page.waitForTimeout(50);check(await page.locator('#settings h2').textContent()==='Ayarlar','Turkish settings heading');check(await page.locator('#display-settings h3').textContent()==='Görünüm Modu','Turkish matrix setting');check(await page.locator('#search-input').getAttribute('placeholder')==='Ara...','Turkish search placeholder');
 for(const columns of [2,3,4]){
  await page.setViewportSize({width:393,height:820});await page.waitForTimeout(90);
  await page.locator('#display-mode').selectOption(String(columns));await page.evaluate(()=>closeSettings());await page.waitForTimeout(50);
  check(await page.locator('.account').count()===0,'matrix '+columns+' replaces account rows only');
  check(await page.locator('#list').evaluate((el,w)=>el.clientWidth===w,width),'matrix '+columns+' preserves list width');
  for(const viewport of [280,320,393,600]){
   await page.setViewportSize({width:viewport,height:820});await page.waitForTimeout(90);
   const geometry=await page.locator('.matrix-row').first().evaluate(el=>{const cells=[...el.querySelectorAll('.matrix-cell')];return {columns:cells.length,overflow:document.documentElement.scrollWidth>innerWidth,equal:cells.every(c=>{const a=c.querySelector('.avatar').getBoundingClientRect();return Math.abs(a.width-a.height)<.1;}),labels:cells.every(c=>{const a=c.querySelector('.avatar').getBoundingClientRect(),u=c.querySelector('.matrix-user'),b=u.getBoundingClientRect();return b.top>=a.bottom&&b.width<=a.width+.1&&getComputedStyle(u).textOverflow==='ellipsis'&&u.textContent.startsWith('@')&&!c.querySelector('.card-name');}),radius:getComputedStyle(cells[0].querySelector('.avatar')).borderRadius};});
   check(geometry.columns===columns&&geometry.equal&&!geometry.overflow&&geometry.labels&&geometry.radius==='14px','square aligned '+columns+'-column cells and usernames at '+viewport+'px');
  }
  const large=await page.addStyleTag({content:'.matrix-user{font-size:18px;line-height:30px}'});await page.evaluate(()=>changeLayout(uiPreferences.columns));await page.waitForTimeout(50);check(await page.locator('.matrix-row').first().evaluate(r=>[...r.querySelectorAll('.matrix-user')].every(u=>u.getBoundingClientRect().bottom<=r.getBoundingClientRect().bottom)),'matrix labels fit enlarged text '+columns);await large.evaluate(el=>el.remove());await page.evaluate(()=>changeLayout(uiPreferences.columns));
  await page.setViewportSize({width:393,height:820});await page.waitForTimeout(70);
  await page.evaluate(()=>window.scrollTo(0,10000));await page.waitForTimeout(70);const anchor=await page.locator('.matrix-row').evaluateAll(nodes=>nodes.find(n=>n.getBoundingClientRect().bottom>0)?.querySelector('.matrix-user').textContent);await page.setViewportSize({width:320,height:820});await page.waitForTimeout(90);check(await page.locator('.matrix-user').allTextContents().then(names=>names.includes(anchor)),'resize retains visible account anchor '+columns);
  await page.evaluate(()=>document.getElementById('scroll-end').click());await page.waitForTimeout(180);check(await page.locator('.matrix-user').last().textContent()==='@avery.long_username_10000','finite end includes final partial matrix row '+columns);
  check(await page.locator('.matrix-cell').count()<100,'20002 accounts keep bounded matrix DOM '+columns);
  await page.locator('.matrix-user').last().click();check(await page.evaluate(()=>calls.at(-1).command==='open'&&calls.at(-1).user==='avery.long_username_10000'),'matrix username opens matching profile '+columns);
  await page.evaluate(()=>document.getElementById('scroll-start').click());await page.waitForTimeout(180);await page.evaluate(()=>openSettings());
 }
 await page.locator('#display-mode').selectOption('0');await page.locator('#language-choice').selectOption('en');await page.waitForTimeout(40);check(await page.locator('#settings h2').textContent()==='Settings','English restored after Turkish');check(await page.locator('#search-input').getAttribute('placeholder')==='Search...','English search restored');await page.evaluate(()=>closeSettings());await page.waitForTimeout(40);
 check(await page.locator('.account').first().evaluate(el=>el.getBoundingClientRect().height===68&&el.querySelector('.avatar').getBoundingClientRect().width===67.5),'return to original list geometry');
 check(await page.evaluate(()=>calls.filter(c=>c.command==='fetcher-action').length===0),'view/language/status changes never request retrieval');
 check(await page.evaluate(()=>savedUI.columns===0&&savedUI.language==='en'),'both preferences sent to native persistence');
 // Search expands filtered folders in matrix mode, without displaying real names.
 await page.evaluate(()=>{applyUiPreferences({columns:4,language:'tr'});query='bvery.long_username_10000';rebuild();});await page.waitForTimeout(80);check(await page.locator('.matrix-user').allTextContents().then(x=>x.length===1&&x[0]==='@bvery.long_username_10000'),'matrix search finds only matching account in correct folder');
 await page.evaluate(()=>{query='';opened.add(1);rebuild();});await page.waitForTimeout(80);await page.evaluate(()=>document.getElementById('scroll-end').click());await page.waitForTimeout(180);check(await page.locator('.matrix-user').last().textContent()==='@bvery.long_username_10000','matrix end spans all open folders');
 if(process.env.MATRIX_SCREENSHOT){await page.evaluate(()=>document.getElementById('scroll-start').click());await page.waitForTimeout(180);await page.screenshot({path:process.env.MATRIX_SCREENSHOT});}
 check(errors.length===0,'no JavaScript errors in mode/language/resize/search transitions');console.log(checks+' matrix/language checks passed');
 }finally{await browser.close();server.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
