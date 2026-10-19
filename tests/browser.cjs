/* UI integration tests. Set CHROME_BIN to a local Chromium/headless-shell executable. */
const fs=require('node:fs'),path=require('node:path'),http=require('node:http'),assert=require('node:assert/strict');
const {chromium}=require('playwright');
const root=path.resolve(__dirname,'../app/src/main/assets');
const fixture=[{name:'SAVED',items:Array.from({length:10000},(_,i)=>({pageId:i===0?'test.user':'user'+i,name:i===0?'Test User':'Account '+i}))},{name:'SECOND',items:[{pageId:'test.user',name:'Test User'}]}];
let count=0;function check(b,label){assert.ok(b,label);console.log('PASS '+label);count++;}
(async()=>{
 const server=http.createServer((req,res)=>{if(req.url.startsWith('/thumb/')||req.url.startsWith('/full/')){res.setHeader('Content-Type','image/svg+xml');res.end('<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512"><rect width="512" height="512" fill="#777"/></svg>');return;}const name=req.url.split('?')[0]==='/'?'index.html':req.url.split('?')[0].slice(1);const file=path.join(root,name);if(!file.startsWith(root)||!fs.existsSync(file)){res.writeHead(404).end();return;}res.setHeader('Content-Type',name.endsWith('.js')?'application/javascript':name.endsWith('.css')?'text/css':'text/html');res.end(fs.readFileSync(file));});
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const url='http://127.0.0.1:'+server.address().port;
 const browser=await chromium.launch({executablePath:process.env.CHROME_BIN,headless:true,args:['--no-sandbox','--no-zygote','--single-process','--disable-gpu']});
 try{
 const page=await browser.newPage({viewport:{width:393,height:820},deviceScaleFactor:1,hasTouch:true,isMobile:true});const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(({fixture})=>{window.commands=[];window.Android={request(raw){const p=JSON.parse(raw);window.commands.push(p);if(p.command==='bootstrap')window.nativeEvent('bootstrap',{raw:JSON.stringify({preserved:'yes',favorite_page_pref:JSON.stringify(fixture)}),profiles:{'test.user':{file:true}},total:10001,unique:10000,downloaded:1,cacheBytes:1024,remaining:9999,stats:{usage:1,load:1,clear:0}});if(p.command==='full')window.nativeEvent('full-ready',p.user);}};},{fixture});
 await page.goto(url);await page.waitForSelector('#splash[hidden]',{state:'attached'});
 check(await page.locator('.folder-header').count()===2,'closed collections render headers only');
 check(await page.evaluate(()=>getComputedStyle(document.body).backgroundColor)==='rgb(0, 0, 0)','AMOLED body is pure black');
 check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'no horizontal overflow at phone width');
 check(await page.evaluate(()=>commands.every(c=>!['start','full'].includes(c.command))),'startup never initiates photo retrieval');
 await page.locator('.folder-header').first().click();await page.waitForSelector('.account');
 check(await page.locator('.account').count()<25,'10,000 expanded accounts keep bounded DOM');
 const avatar=await page.locator('.avatar').first().evaluate(el=>({width:el.offsetWidth,height:el.offsetHeight,radius:getComputedStyle(el).borderRadius,fit:getComputedStyle(el.querySelector('img')).objectFit}));
 check(avatar.width===40&&avatar.height===40&&avatar.radius==='14px'&&avatar.fit==='cover','avatar keeps original rounded rectangle and crop');
 await page.locator('.card-link').first().click();check(await page.evaluate(()=>commands.at(-1).command==='open'),'account row opens profile separately from image');
 await page.locator('.avatar').first().click();check(await page.locator('#viewer').isVisible(),'cached thumbnail opens fullscreen viewer');
 await page.waitForFunction(()=>commands.some(c=>c.command==='full'),{},{timeout:8000});await page.waitForFunction(()=>document.getElementById('full-image').getAttribute('src').includes('/full/'),{},{timeout:4000});
 check(true,'delayed full-resolution request replaces cached thumbnail');
 const cdp=await page.context().newCDPSession(page);
 await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:170,y:350,id:1},{x:220,y:350,id:2}]});
 await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:120,y:350,id:1},{x:270,y:350,id:2}]});
 await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
 check(await page.evaluate(()=>scale>1),'two-finger pinch zoom changes image scale');
 const before=await page.evaluate(()=>tx);
 await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:190,y:350,id:1}]});
 await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:230,y:350,id:1}]});
 await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
 check(await page.evaluate(()=>tx)!==before,'zoomed image can be panned');
 await page.evaluate(()=>{resetTransform();lastTap=0;});
 await page.touchscreen.tap(190,350);await page.waitForTimeout(90);await page.touchscreen.tap(190,350);
 check(await page.evaluate(()=>scale>1),'double tap zooms image');
 await page.keyboard.press('Escape');check(await page.locator('#viewer').isHidden(),'back action closes fullscreen viewer');
 await page.locator('#search-input').fill('user9999');await page.waitForTimeout(180);
 check(await page.locator('.account').count()===1,'search finds account outside mounted viewport');
 await page.locator('#clear-search').click();check(await page.locator('#search-input').inputValue()==='','search clear restores collection view');
 await page.evaluate(()=>scrollTo(0,400000));await page.waitForTimeout(100);check(await page.locator('.account').count()<30,'deep scrolling recycles DOM rows');
 await page.locator('#info-btn').click();await page.getByRole('button',{name:'Settings',exact:true}).click();
 check(await page.locator('#settings').isVisible(),'settings reachable through preserved information button');
 check(await page.locator('#profile-actions button').count()===9,'all nine profile management actions are present');
 await page.getByRole('button',{name:'Refresh All Thumbnails',exact:true}).click();check(await page.locator('#dialog').isVisible(),'refresh requires confirmation');await page.getByRole('button',{name:'Cancel',exact:true}).click();
 check(await page.evaluate(()=>commands.every(c=>c.command!=='start')),'cancelled confirmation does not start retrieval');
 await page.getByRole('button',{name:'Clear Thumbnail Cache',exact:true}).click();await page.getByRole('button',{name:'Confirm',exact:true}).click();check(await page.evaluate(()=>commands.at(-1).command==='clear-thumbs'),'thumbnail deletion uses a separate data operation');
 await page.evaluate(()=>nativeEvent('status',{downloaded:1,cacheBytes:1024,failed:1,queue:{state:'paused',reason:'HTTP_429',cursor:1,total:5,success:0,failed:1,elapsedMs:2000},profiles:{'test.user':{file:true,error:'NETWORK'}}}));
 check(await page.getByRole('button',{name:'Resume Retrieval',exact:true}).isEnabled(),'paused queue enables resume');
 check((await page.locator('#queue-state').textContent()).includes('HTTP 429'),'HTTP 429 is shown distinctly');
 await page.getByRole('button',{name:'Resume Retrieval',exact:true}).click();check(await page.evaluate(()=>commands.at(-1).command==='resume'),'resume dispatches native queue command');
 await page.keyboard.press('Escape');await page.evaluate(()=>scrollTo(0,0));await page.waitForTimeout(100);
 check(await page.locator('.avatar img').first().count()===1,'temporary failure preserves existing thumbnail');
 // Inspect real private data only when explicitly supplied; no usernames or raw content are printed.
 if(process.env.PRIVATE_JSON){const raw=fs.readFileSync(process.env.PRIVATE_JSON,'utf8');await page.evaluate(raw=>nativeEvent('imported',{raw,profiles:{}}),raw);check(await page.evaluate(()=>doc.total)===12612,'provided private JSON has all 12,612 memberships');check(await page.evaluate(()=>doc.unique.length)===12197,'provided JSON deduplicates only image retrieval');check(await page.evaluate(()=>doc.raw)===raw,'original private JSON remains byte-for-byte string equivalent');}
 await page.evaluate(f=>nativeEvent('imported',{raw:JSON.stringify(f),profiles:{}}),fixture);await page.evaluate(()=>scrollTo(0,0));await page.waitForTimeout(100);
 if(process.env.UI_SCREENSHOT)await page.screenshot({path:process.env.UI_SCREENSHOT});
 check(errors.length===0,'no browser JavaScript errors');console.log(count+' UI checks passed');
 }finally{await browser.close();server.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
