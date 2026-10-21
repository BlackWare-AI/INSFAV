/* Same synthetic original WebP source and scroll trace before/after. Host Chromium, not Android FPS. */
const {chromium}=require('playwright'),fs=require('fs'),path=require('path'),http=require('http');
(async()=>{
 const roots={baseline:path.resolve(process.env.BASELINE_ASSETS),current:path.resolve(__dirname,'../app/src/main/assets')};
 const fixture=Buffer.from('UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA','base64');
 let requests=0;const server=http.createServer((req,res)=>{if(req.url.startsWith('/thumb/')){requests++;res.setHeader('Content-Type','image/webp');res.setHeader('Cache-Control','private,max-age=300');res.end(fixture);return;}
 const [variant,...parts]=req.url.split('?')[0].slice(1).split('/'),root=roots[variant];if(!root){res.writeHead(404).end();return;}const name=parts.join('/')||'index.html',file=path.join(root,name);if(!file.startsWith(root)||!fs.existsSync(file)){res.writeHead(404).end();return;}res.setHeader('Content-Type',name.endsWith('.js')?'application/javascript':name.endsWith('.css')?'text/css':name.endsWith('.webp')?'image/webp':'text/html');res.end(fs.readFileSync(file));});
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({executablePath:process.env.CHROME_BIN,headless:true,args:['--no-sandbox']});const result={environment:'Host Chromium; synthetic images, no SAF or Instagram',samples:[]};
 try{for(const size of [5000,10000,20000])for(const variant of ['baseline','current']){
  requests=0;const page=await browser.newPage({viewport:{width:393,height:820}});await page.addInitScript(n=>{window.Android={request(raw){if(JSON.parse(raw).command==='bootstrap'){const items=Array.from({length:n},(_,i)=>({pageId:'user'+i,name:'Account '+i}));const profiles=Object.fromEntries(items.map(i=>[i.pageId,{file:true,width:256,height:256,version:'1'}]));nativeEvent('bootstrap',{raw:JSON.stringify([{name:'Synthetic',items}]),profiles,stats:{usage:10,load:3,clear:0}});}}};},size);
  const opened=Date.now();await page.goto('http://127.0.0.1:'+server.address().port+'/'+variant+'/');await page.waitForSelector('.folder-header');await page.locator('.folder-header').click();await page.waitForSelector('.account');const initialMs=Date.now()-opened;
  const measures=await page.evaluate(async()=>{const frames=[],work=[],loads=[];let maxNodes=0,last=0;const render=renderVisible;renderVisible=function(){const t=performance.now();render();work.push(performance.now()-t);};
   const scenarios=[['slow',i=>i*40],['fast',i=>3000+i*900],['return',i=>39000-i*900],['reverse',i=>i%2?8000-i*40:8000+i*40]];const output={};
   for(const [name,pos]of scenarios){const begin=frames.length;for(let i=0;i<45;i++)await new Promise(r=>requestAnimationFrame(t=>{if(last)frames.push(t-last);last=t;scrollTo(0,Math.max(0,pos(i)));maxNodes=Math.max(maxNodes,document.querySelectorAll('.account').length);r();}));output[name]=frames.slice(begin);}
   scrollTo(0,0);await new Promise(r=>setTimeout(r,250));
   const sorted=frames.slice().sort((a,b)=>a-b),pct=p=>sorted[Math.min(sorted.length-1,Math.floor(sorted.length*p))]||0,mean=frames.reduce((a,b)=>a+b,0)/frames.length;
   return {fps:1000/mean,frameMeanMs:mean,frameP95Ms:pct(.95),frameP99Ms:pct(.99),jankOver32Ms:frames.filter(n=>n>32).length/frames.length,renderMeanMs:work.reduce((a,b)=>a+b,0)/work.length,maxNodes,jsHeapBytes:performance.memory?.usedJSHeapSize??'Not Measured',imageQueue:window.thumbnailMetrics?.()??'Not Measured',scenarios:output};
  });
  result.samples.push({size,variant,initialMs,requests,...measures});await page.close();
 }console.log(JSON.stringify(result,null,2));}finally{await browser.close();server.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
