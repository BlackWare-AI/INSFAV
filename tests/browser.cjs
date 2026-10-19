/* UI tests use a simulated native bridge. SAF/device behavior requires Android testing. */
const fs=require('node:fs'),path=require('node:path'),http=require('node:http'),assert=require('node:assert/strict');
const {chromium}=require('playwright');
const root=path.resolve(__dirname,'../app/src/main/assets');
const fixture=[{name:'SAVED',items:Array.from({length:10000},(_,i)=>({pageId:i===0?'test.user':'user'+i,name:i===0?'Test User':'Account '+i}))},{name:'SECOND',items:[{pageId:'test.user',name:'Test User'}]}];
let checks=0;function check(value,label){assert.ok(value,label);console.log('PASS '+label);checks++;}
(async()=>{
 const server=http.createServer((req,res)=>{
  if(req.url.startsWith('/thumb/')||req.url.startsWith('/full/')){res.setHeader('Content-Type','image/svg+xml');res.end('<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512"><rect width="512" height="512" fill="#777"/></svg>');return;}
  const name=req.url.split('?')[0]==='/'?'index.html':req.url.split('?')[0].slice(1),file=path.join(root,name);
  if(!file.startsWith(root)||!fs.existsSync(file)){res.writeHead(404).end();return;}
  res.setHeader('Content-Type',name.endsWith('.js')?'application/javascript':name.endsWith('.css')?'text/css':name.endsWith('.webp')?'image/webp':name.endsWith('.woff2')?'font/woff2':'text/html');res.end(fs.readFileSync(file));
 });
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 const browser=await chromium.launch({executablePath:process.env.CHROME_BIN,headless:true,args:['--no-sandbox','--no-zygote','--single-process','--disable-gpu']});
 try{
  const page=await browser.newPage({viewport:{width:393,height:820},deviceScaleFactor:1,hasTouch:true,isMobile:true}),errors=[],external=[];
  page.on('pageerror',error=>errors.push(error.message));page.on('request',req=>{if(!req.url().startsWith('http://127.0.0.1:'))external.push(req.url());});
  await page.addInitScript(({fixture})=>{
   window.commands=[];
   window.snapshot={profiles:{'test.user':{file:true,width:512,height:512}},total:10001,unique:10000,matched:1,missing:9999,unreadable:1,duplicates:2,folderBytes:1024,lastScan:100000,revision:1,folder:'.thumbnails',folderStatus:'ready',scanState:'completed',app:{version:'3.1.0',build:2},stats:{usage:1,load:1,clear:0}};
   window.Android={request(raw){const input=JSON.parse(raw);commands.push(input);
    if(input.command==='bootstrap')nativeEvent('bootstrap',{...snapshot,raw:JSON.stringify({preserved:'yes',favorite_page_pref:JSON.stringify(fixture)})});
    else if(input.command==='status')nativeEvent('status',structuredClone(snapshot));
    else if(input.command==='clear-index'){snapshot={...snapshot,profiles:{},matched:0,missing:10000,revision:0,scanState:'not_scanned'};nativeEvent('status',structuredClone(snapshot));}
   }};
  },{fixture});
  await page.goto('http://127.0.0.1:'+server.address().port);await page.waitForSelector('#splash[hidden]',{state:'attached'});
  check(await page.locator('.folder-header').count()===2,'closed collections render headers only');
  check(await page.evaluate(()=>getComputedStyle(document.body).backgroundColor)==='rgb(0, 0, 0)','AMOLED is pure black');
  check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'no horizontal overflow');
  check(await page.evaluate(()=>commands.every(c=>!['start','full','resume'].includes(c.command))),'startup has no retrieval commands');
  await page.locator('.folder-header').first().click();await page.waitForSelector('.account');
  check(await page.locator('.account').count()<25,'10,000 accounts keep bounded DOM');
  const avatar=await page.locator('.avatar').first().evaluate(el=>({width:el.offsetWidth,height:el.offsetHeight,radius:getComputedStyle(el).borderRadius,fit:getComputedStyle(el.querySelector('img')).objectFit}));
  check(avatar.width===40&&avatar.height===40&&avatar.radius==='14px'&&avatar.fit==='cover','original avatar rectangle and center crop preserved');
  check(await page.locator('.avatar').nth(1).locator('.person-placeholder').count()===1,'missing image shows gray person instead of a letter');
  await page.evaluate(()=>window.savedHeader=document.querySelector('.folder-header'));
  await page.locator('.folder-header').first().click();await page.waitForTimeout(180);
  check(await page.evaluate(()=>savedHeader===document.querySelector('.folder-header')),'collapse retains header DOM for arrow transition');
  await page.locator('.folder-header').first().click();
  await page.locator('.card-link').first().click();check(await page.evaluate(()=>commands.at(-1).command==='open'),'account action remains external profile opening');
  await page.locator('.avatar').first().click();check(await page.locator('#viewer').isVisible(),'viewer opens cached local thumbnail');
  await page.waitForFunction(()=>document.getElementById('full-image').src.includes('/full/'));
  check(await page.evaluate(()=>commands.every(c=>c.command!=='full')),'larger view uses local resource without native download request');
  const cdp=await page.context().newCDPSession(page);
  await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:170,y:350,id:1},{x:220,y:350,id:2}]});
  await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:120,y:350,id:1},{x:270,y:350,id:2}]});
  await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
  check(await page.evaluate(()=>scale>1),'pinch zoom works');const before=await page.evaluate(()=>tx);
  await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:190,y:350,id:1}]});
  await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:230,y:350,id:1}]});await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
  check(await page.evaluate(()=>tx)!==before,'zoomed image pans');
  await page.evaluate(()=>{resetTransform();lastTap=0;});await page.touchscreen.tap(190,350);await page.waitForTimeout(80);await page.touchscreen.tap(190,350);
  check(await page.evaluate(()=>scale>1),'double tap zoom works');
  await page.keyboard.press('Escape');await page.locator('#viewer').waitFor({state:'hidden'});check(true,'back closes viewer after exit animation');
  await page.locator('#search-input').fill('user9999');await page.waitForTimeout(180);check(await page.locator('.account').count()===1,'search reaches unmounted accounts');
  await page.locator('#clear-search').click();check(await page.locator('#search-input').inputValue()==='','search clear restores view');
  await page.evaluate(()=>scrollTo(0,400000));await page.waitForTimeout(100);check(await page.locator('.account').count()<30,'deep scrolling recycles rows');
  const colors=await page.evaluate(()=>['info-btn','import-btn','clear-btn'].map(id=>getComputedStyle(document.getElementById(id)).color));check(new Set(colors).size===3,'bottom controls retain three distinct muted colors');
  await page.locator('#info-btn').click();
  check((await page.locator('#dialog-body').innerText()).includes('BlackWare'),'About credits developer BlackWare');
  check((await page.locator('#dialog-body').innerText()).includes('AI Development Assistance'),'AI assistance is separately labeled');
  check((await page.locator('#dialog-body').innerText()).includes('3.1.0'),'About uses native version metadata');
  check(await page.locator('.features li').count()===10,'About lists ten implemented capability descriptions');
  await page.getByRole('button',{name:'Settings',exact:true}).click();check(await page.locator('#settings').isVisible(),'Settings remains accessible');check(await page.locator('#progress').evaluate(el=>el.value===el.max),'completed persisted scan shows a full progress bar');
  check(await page.locator('#profile-actions button').count()===4,'download controls replaced with four local image controls');
  check(!(await page.locator('#settings').innerText()).includes('Retry Failed'),'obsolete retry controls removed');
  await page.getByRole('button',{name:'Select Thumbnail Folder',exact:true}).click();check(await page.evaluate(()=>commands.at(-1).command==='select-folder'),'SAF selection command is dispatched');
  await page.getByRole('button',{name:'Rescan Thumbnails',exact:true}).click();check(await page.evaluate(()=>commands.at(-1).command==='rescan'),'rescan is manual');
  await page.evaluate(()=>nativeEvent('scan-progress',{state:'scanning',processed:25,total:100}));
  check(await page.locator('#progress').evaluate(el=>el.value===25&&el.max===100),'scan progress is shown');
  check(await page.getByRole('button',{name:'Select Thumbnail Folder',exact:true}).isDisabled(),'folder replacement is blocked during scan');
  await page.evaluate(()=>nativeEvent('status',structuredClone(snapshot)));
  await page.getByRole('button',{name:'Clear Image Cache',exact:true}).click();check(await page.locator('#dialog').isVisible(),'cache deletion requires confirmation');
  await page.getByRole('button',{name:'Confirm',exact:true}).click();check(await page.evaluate(()=>commands.at(-1).command==='clear-cache'),'cache clear is separate from source data');
  await page.getByRole('button',{name:'Clear Thumbnail Index',exact:true}).click();await page.getByRole('button',{name:'Confirm',exact:true}).click();
  check(await page.evaluate(()=>doc.total===10001),'index clearing preserves collection memberships');check(await page.locator('#progress').evaluate(el=>el.value===0),'index clearing resets scan progress display');
  check((await page.locator('#metrics').innerText()).includes('Missing Images'),'matched and missing metrics are separately named');
  await page.evaluate(()=>nativeEvent('status',{...snapshot,folderMessage:'Folder permission was lost.',folderStatus:'unavailable'}));
  check(await page.locator('#folder-warning').isVisible(),'lost permission warning appears');
  await page.keyboard.press('Escape');await page.locator('#settings').waitFor({state:'hidden'});
  await page.evaluate(()=>scrollTo(0,0));await page.waitForTimeout(100);check(await page.locator('.avatar img').count()===0,'cleared index removes photo references');
  if(process.env.PRIVATE_JSON){const raw=fs.readFileSync(process.env.PRIVATE_JSON,'utf8');await page.evaluate(raw=>nativeEvent('imported',{raw,profiles:{}}),raw);check(await page.evaluate(()=>doc.total===12612&&doc.unique.length===12197),'private JSON retains membership and unique counts');check(await page.evaluate(()=>doc.raw)===raw,'private JSON text remains unchanged');}
  await page.evaluate(f=>nativeEvent('imported',{raw:JSON.stringify(f),profiles:{}}),fixture);await page.evaluate(()=>scrollTo(0,0));await page.waitForTimeout(200);
  if(process.env.UI_SCREENSHOT)await page.screenshot({path:process.env.UI_SCREENSHOT});
  check(external.length===0,'all UI image/font/resource requests stay on the local origin');check(errors.length===0,'no browser JavaScript errors');
  console.log(checks+' UI checks passed');
 }finally{await browser.close();server.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
