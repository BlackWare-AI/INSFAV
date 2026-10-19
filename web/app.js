'use strict';
const C=InsfavCore,$=id=>document.getElementById(id),native=!!window.Android;
let doc=null,profiles={},stats={usage:0,load:0,clear:0},state={},opened=new Set(),query='',rows=[],positions=[0],mounted=new Map(),scheduled=false,previousFocus=null,lastFocus=null,viewerUser='',toastTimer=0,cacheVersion=0,lastRevision='',imageFailureTimer=0,fullLoader=null,appInfo={};
function placeholder(button){const icon=document.createElement('span');icon.className='person-placeholder';icon.setAttribute('aria-hidden','true');button.replaceChildren(icon);}
function request(command,extra={}){if(native)Android.request(JSON.stringify({command,...extra}));else previewCommand(command,extra);}
function message(text){$('toast').textContent=text;$('toast').hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('toast').hidden=true,4200);}
function setDocument(raw){const next=C.parseDocument(raw);doc=next;opened.clear();query='';$('search-input').value='';$('clear-search').hidden=true;rebuild();}
window.nativeEvent=(event,data)=>{
 try{
  if(event==='bootstrap'||event==='imported'){
   profiles=data.profiles||{};state=data;if(data.app)appInfo=data.app;if(data.stats)stats=data.stats;
   if(data.raw)setDocument(data.raw);else{doc=null;rebuild();}
   if(event==='imported'){stats.load++;message('JSON imported. All original fields preserved.');}
   updateSettings();$('splash').hidden=true;
  }
  if(event==='status'){
   if(data.folderMessage&&data.folderMessage!==state.folderMessage)message(data.folderMessage);
   if(data.profiles)profiles=data.profiles;state=data;
   const revision=[data.revision,data.matched,data.missing,data.unreadable,data.folderStatus].join(':');
   if(revision!==lastRevision){lastRevision=revision;cacheVersion++;refreshAvatars();}
   updateSettings();
  }
  if(event==='scan-progress'){state.scan=data;state.scanState=data.state;updateSettings();}
  if(event==='cleared'){doc=null;stats.clear++;opened.clear();rebuild();message('Collections cleared. Local photos are unchanged.');request('status');}
  if(event==='message'||event==='error')message(data);
 }catch(error){message('Unable to read data: '+error.message);$('splash').hidden=true;}
};
function rebuild(preserve=false){
 const groups=doc?C.filterCollections(doc,query):[];
 rows=C.rowsFor(groups,opened,!!query.trim());positions=C.offsets(rows);
 $('list').style.height=positions.at(-1)+'px';
 if(!preserve){for(const node of mounted.values())node.remove();mounted.clear();}
 $('empty').hidden=!!doc;$('search-info').classList.toggle('visible',!!query.trim());
 $('search-info').textContent=groups.reduce((count,collection)=>count+collection.items.length,0)+' RESULTS';renderVisible();
}
function highlighted(el,text){el.replaceChildren();const q=query.trim();if(!q){el.textContent=text;return;}const source=text.toLocaleLowerCase('en'),needle=q.toLocaleLowerCase('en');let p=0,i;while((i=source.indexOf(needle,p))!==-1){el.append(document.createTextNode(text.slice(p,i)));const mark=document.createElement('mark');mark.textContent=text.slice(i,i+q.length);el.append(mark);p=i+q.length;}el.append(document.createTextNode(text.slice(p)));}
function notifyImageFailure(){clearTimeout(imageFailureTimer);imageFailureTimer=setTimeout(()=>request('image-failed'),180);}
function avatarFor(button,user){
 button.dataset.user=user;
 if(profiles[user]?.file){
  const img=document.createElement('img');img.alt='';img.loading='lazy';img.decoding='async';img.src='/thumb/'+encodeURIComponent(user)+'?v='+cacheVersion;
  img.onerror=()=>{placeholder(button);notifyImageFailure();};button.replaceChildren(img);
 }else placeholder(button);
}
function makeRow(r,index){const node=document.createElement('div');node.className='virtual-row';node.style.top=positions[index]+'px';node.style.height=r.height+'px';node.dataset.key=r.key;
 if(r.type==='spacer'){node.setAttribute('aria-hidden','true');return node;}
 if(r.type==='folder'){const open=!!query.trim()||opened.has(r.group.key);node.classList.add('folder-wrap');node.classList.toggle('open',open);const head=document.createElement('button');head.className='folder-header';head.setAttribute('aria-expanded',String(open));const title=document.createElement('span');title.className='folder-title';const name=document.createElement('span');name.textContent=r.group.name.toUpperCase();const count=document.createElement('span');count.className='folder-count';count.textContent=r.group.items.length;title.append(name,count);const arrow=document.createElement('span');arrow.className='folder-arrow';arrow.textContent='›';head.append(title,arrow);head.onclick=()=>{if(query.trim()){message('Clear search to collapse collections.');return;}opened.has(r.group.key)?opened.delete(r.group.key):opened.add(r.group.key);rebuild(true);};node.append(head);}
 else{node.classList.add('account');node.classList.toggle('last',r.last);const a=document.createElement('button');a.className='avatar';a.setAttribute('aria-label','View profile image for @'+r.item.user);avatarFor(a,r.item.user,r.item.name);a.onclick=()=>openViewer(r.item.user);const link=document.createElement('button');link.className='card-link';const name=document.createElement('div');name.className='card-name';highlighted(name,r.item.name);const username=document.createElement('div');username.className='card-user';highlighted(username,'@'+r.item.source.pageId.replace(/^@/,''));link.append(name,username);link.onclick=()=>request('open',{user:r.item.user});node.append(a,link);}
 return node;
}
function renderVisible(){
 scheduled=false;const list=$('list'),top=-list.getBoundingClientRect().top;
 const [start,end]=C.windowRange(positions,top,window.innerHeight,350),wanted=new Set(),fragment=document.createDocumentFragment();
 for(let i=start;i<end;i++){
  const row=rows[i];wanted.add(row.key);let node=mounted.get(row.key);
  if(!node){node=makeRow(row,i);mounted.set(row.key,node);fragment.append(node);}
  else{
   const position=positions[i]+'px';if(node.style.top!==position)node.style.top=position;
   const height=row.height+'px';if(node.style.height!==height)node.style.height=height;
   if(row.type==='folder'){const open=!!query.trim()||opened.has(row.group.key);node.classList.toggle('open',open);node.querySelector('button').setAttribute('aria-expanded',String(open));}
  }
 }
 list.append(fragment);
 for(const [key,node]of mounted)if(!wanted.has(key)){
  for(const image of node.querySelectorAll('img')){image.onload=null;image.onerror=null;image.removeAttribute('src');}
  node.remove();mounted.delete(key);
 }
}
function schedule(){if(!scheduled){scheduled=true;requestAnimationFrame(renderVisible);}}
function refreshAvatars(){for(const node of mounted.values()){const button=node.querySelector('.avatar');if(button)avatarFor(button,button.dataset.user);}}
window.addEventListener('scroll',schedule,{passive:true});window.addEventListener('resize',schedule,{passive:true});
let searchTimer;$('search-input').addEventListener('input',()=>{clearTimeout(searchTimer);searchTimer=setTimeout(()=>{query=$('search-input').value;$('clear-search').hidden=!query;window.scrollTo(0,0);rebuild();},130);});$('clear-search').onclick=()=>{$('search-input').value='';query='';$('clear-search').hidden=true;rebuild();$('search-input').focus();};
function showDialog(title,body,actions){lastFocus=document.activeElement;$('dialog-title').textContent=title;$('dialog-body').replaceChildren();if(typeof body==='string'){const p=document.createElement('p');p.className='muted';p.textContent=body;$('dialog-body').append(p);}else $('dialog-body').append(body);$('dialog-actions').replaceChildren();for(const action of actions){const b=document.createElement('button');b.className='btn'+(action.danger?' destructive':'');b.textContent=action.label;b.onclick=()=>{closeDialog();action.run?.();};$('dialog-actions').append(b);}$('dialog').hidden=false;document.body.classList.add('overlay-open');$('dialog-actions').querySelector('button')?.focus();}
function closeDialog(){$('dialog').hidden=true;if($('settings').hidden)document.body.classList.remove('overlay-open');lastFocus?.focus?.();}
function confirmAction(title,text,action){showDialog(title,text,[{label:'Confirm',danger:true,run:action},{label:'Cancel'}]);}
const features=['Instagram account collections','JSON import and export','Collection management','Account search','Local profile thumbnails','External thumbnail folder integration','Fullscreen image viewer','Efficient large-list rendering','Persistent settings','AMOLED interface'];
function showInfo(){
 const container=document.createElement('div'),logo=document.createElement('img');logo.src='logo.webp';logo.alt='INSFAV logo';logo.className='about-logo';container.append(logo);
 for(const [label,value]of [['Application Name','INSFAV'],['Developer','BlackWare'],['AI Development Assistance','OpenAI ChatGPT'],['Version',appInfo.version||'Unavailable'],['Build',appInfo.build??'Unavailable']]){
  const card=document.createElement('div');card.className='about-detail';const name=document.createElement('span');name.textContent=label;const detail=document.createElement('strong');detail.textContent=value;card.append(name,detail);container.append(card);
 }
 const heading=document.createElement('h3');heading.className='about-heading';heading.textContent='Features & Functions';container.append(heading);
 const list=document.createElement('ul');list.className='features';for(const feature of features){const item=document.createElement('li');item.textContent=feature;list.append(item);}container.append(list);
 const usage=document.createElement('p');usage.className='muted';usage.textContent=`${doc?.total||0} account memberships · ${stats.usage} uses · ${stats.load} imports · ${stats.clear} resets`;container.append(usage);
 const note=document.createElement('p');note.className='muted footnote';note.textContent='INSFAV is an independent Instagram account collection manager and is not affiliated with Meta or Instagram.';container.append(note);
 showDialog('Application Information',container,[{label:'Settings',run:openSettings},{label:'Close'}]);
}
function hideScreen(element,done){
 if(element.dataset.closing)return;element.dataset.closing='true';element.style.pointerEvents='none';
 const finish=()=>{element.hidden=true;delete element.dataset.closing;element.style.pointerEvents='';done?.();};
 if(matchMedia('(prefers-reduced-motion: reduce)').matches){finish();return;}
 element.animate([{opacity:1},{opacity:0}],{duration:160,easing:'cubic-bezier(.2,0,0,1)'}).onfinish=finish;
}
function openSettings(){previousFocus=document.activeElement;$('settings').hidden=false;document.body.classList.add('overlay-open');updateSettings();request('status');$('settings-back').focus();}
function closeSettings(){hideScreen($('settings'),()=>{document.body.classList.remove('overlay-open');previousFocus?.focus?.();});}
const actions=[['Select Thumbnail Folder','select-folder'],['Rescan Thumbnails','rescan'],['Clear Image Cache','clear-cache'],['Clear Thumbnail Index','clear-index']];
for(const [label,action]of actions){const button=document.createElement('button');button.textContent=label;button.dataset.action=action;$('profile-actions').append(button);}
function bytes(value){return (Number(value||0)/1048576).toFixed(2)+' MiB';}
function updateSettings(){
 if($('settings').hidden)return;
 const scan=state.scan||{},busy=state.scanState==='scanning',date=state.lastScan?new Date(state.lastScan).toLocaleString():'Never';
 const values=[['Total Accounts',doc?.total||0],['Unique Accounts',doc?.unique.length||0],['Matched Images',state.matched||0],['Missing Images',state.missing??doc?.unique.length??0],['Unreadable Images',state.unreadable||0],['Duplicate Image Candidates',state.duplicates||0],['Indexed Folder Size',bytes(state.folderBytes)+(state.unknownSizes?' + unknown sizes':'')],['Image Cache Size',bytes(state.cacheBytes)],['Last Scan Date',date],['Selected Folder',state.folder||'Not selected']];
 $('metrics').replaceChildren();
 for(const [label,value]of values){const metric=document.createElement('div');metric.className='metric';const detail=document.createElement('b');detail.textContent=value;const name=document.createElement('span');name.textContent=label;metric.append(detail,name);$('metrics').append(metric);}
 $('scan-state').textContent=(state.scanState||'idle').replaceAll('_',' ').replace(/^./,value=>value.toUpperCase());
 $('progress').max=Math.max(1,scan.total||1);$('progress').value=scan.processed||0;
 $('progress-detail').textContent=scan.message||(busy?`${scan.processed||0} / ${scan.total||0} folder entries checked`:'Photos stay in the selected folder. Rescan after changing its contents.');
 $('folder-warning').hidden=!state.folderMessage;$('folder-warning').textContent=state.folderMessage||'';
 $('version-info').textContent=`INSFAV ${appInfo.version||''} · Build ${appInfo.build??'—'} · BlackWare`;
 for(const button of $('profile-actions').querySelectorAll('button'))button.disabled=busy&&button.dataset.action!=='clear-cache';
}
document.addEventListener('click',event=>{
 const action=event.target.closest('[data-action]')?.dataset.action;if(!action)return;
 if(action.startsWith('clear-'))confirmAction('Confirm deletion',action==='clear-data'?'Remove the collection document? Local photos and their index are unchanged.':'Clear the selected app data? Source photos and collections will be preserved.',()=>request(action));
 else request(action);
});
$('info-btn').onclick=showInfo;$('import-btn').onclick=()=>request('import');$('clear-btn').onclick=()=>confirmAction('Reset collections?','The collection database will be cleared. Profile images are managed separately.',()=>request('clear-data'));$('settings-back').onclick=closeSettings;
let scale=1,tx=0,ty=0,pointers=new Map(),gesture=null,lastTap=0,lastTapX=0,lastTapY=0;
function applyTransform(){const img=$('full-image'),stage=$('image-stage');const mx=Math.max(0,(img.offsetWidth*scale-stage.clientWidth)/2),my=Math.max(0,(img.offsetHeight*scale-stage.clientHeight)/2);tx=Math.min(mx,Math.max(-mx,tx));ty=Math.min(my,Math.max(-my,ty));img.style.transform=`translate(${tx}px,${ty}px) scale(${scale})`;}
function resetTransform(){scale=1;tx=ty=0;applyTransform();}
function openViewer(user){
 if(!profiles[user]?.file){message('No readable local image. Select a thumbnail folder or rescan in Settings.');return;}
 previousFocus=document.activeElement;viewerUser=user;$('viewer').hidden=false;document.body.classList.add('overlay-open');
 $('full-image').src='/thumb/'+encodeURIComponent(user)+'?v='+cacheVersion;
 const image=profiles[user],dimensions=image.width&&image.height?` · ${image.width} × ${image.height}`:'';
 $('viewer-caption').textContent='@'+user+' · Local image'+dimensions+(Math.max(image.width||0,image.height||0)>2048?' · Preview up to 2048 px':'');
 resetTransform();$('viewer-back').focus();
 fullLoader=new Image();fullLoader.onload=()=>{if(viewerUser===user){$('full-image').src=fullLoader.src;resetTransform();}};
 fullLoader.onerror=()=>{if(viewerUser===user)notifyImageFailure();};fullLoader.src='/full/'+encodeURIComponent(user)+'?v='+cacheVersion;
}
function closeViewer(){
 viewerUser='';if(fullLoader){fullLoader.onload=fullLoader.onerror=null;fullLoader.src='';fullLoader=null;}
 pointers.clear();hideScreen($('viewer'),()=>{$('full-image').removeAttribute('src');if($('settings').hidden)document.body.classList.remove('overlay-open');previousFocus?.focus?.();});
}
$('viewer-back').onclick=closeViewer;$('full-image').onload=applyTransform;
const stage=$('image-stage');stage.addEventListener('pointerdown',e=>{stage.setPointerCapture(e.pointerId);pointers.set(e.pointerId,{x:e.clientX,y:e.clientY,startX:e.clientX,startY:e.clientY});if(pointers.size===2){const [a,b]=[...pointers.values()];gesture={distance:Math.hypot(a.x-b.x,a.y-b.y),scale,tx,ty,cx:(a.x+b.x)/2,cy:(a.y+b.y)/2};}else gesture=null;});stage.addEventListener('pointermove',e=>{const p=pointers.get(e.pointerId);if(!p)return;const dx=e.clientX-p.x,dy=e.clientY-p.y;p.x=e.clientX;p.y=e.clientY;if(pointers.size===2&&gesture){const [a,b]=[...pointers.values()];scale=Math.min(6,Math.max(1,gesture.scale*Math.hypot(a.x-b.x,a.y-b.y)/Math.max(1,gesture.distance)));tx=gesture.tx+(a.x+b.x)/2-gesture.cx;ty=gesture.ty+(a.y+b.y)/2-gesture.cy;}else if(scale>1){tx+=dx;ty+=dy;}applyTransform();});function release(e){const p=pointers.get(e.pointerId);if(e.type==='pointerup'&&pointers.size===1&&p&&Math.hypot(p.x-p.startX,p.y-p.startY)<10){const now=Date.now();if(now-lastTap<300&&Math.hypot(p.x-lastTapX,p.y-lastTapY)<40){scale=scale>1?1:2.5;tx=ty=0;applyTransform();lastTap=0;}else{lastTap=now;lastTapX=p.x;lastTapY=p.y;}}pointers.delete(e.pointerId);gesture=null;}stage.addEventListener('pointerup',release);stage.addEventListener('pointercancel',release);stage.addEventListener('wheel',e=>{e.preventDefault();scale=Math.min(6,Math.max(1,scale+(e.deltaY<0?.2:-.2)));applyTransform();},{passive:false});
window.handleBack=()=>{if(!$('viewer').hidden){closeViewer();return true;}if(!$('dialog').hidden){closeDialog();return true;}if(!$('settings').hidden){closeSettings();return true;}if(query){$('clear-search').click();return true;}return false;};
document.addEventListener('keydown',e=>{if(e.key==='Escape')window.handleBack();if(e.key==='Tab'){const surface=!$('viewer').hidden?$('viewer'):!$('dialog').hidden?$('dialog'):!$('settings').hidden?$('settings'):null;if(!surface)return;const focus=[...surface.querySelectorAll('button:not(:disabled),input')].filter(el=>!el.hidden);const first=focus[0],last=focus[focus.length-1];if(e.shiftKey&&document.activeElement===first){last.focus();e.preventDefault();}else if(!e.shiftKey&&document.activeElement===last){first.focus();e.preventDefault();}}});
function previewCommand(command,p){if(command==='bootstrap'){appInfo={version:'Browser preview',build:'—'};try{const raw=localStorage.getItem('insfav-preview-data');window.nativeEvent('bootstrap',{raw:raw||'',profiles:{},stats:{usage:1,load:0,clear:0}});}catch(e){window.nativeEvent('error',e.message);$('splash').hidden=true;}}else if(command==='import'){const input=document.createElement('input');input.type='file';input.accept='.json,application/json';input.onchange=async()=>{try{const raw=await input.files[0].text();C.parseDocument(raw);localStorage.setItem('insfav-preview-data',raw);window.nativeEvent('imported',{raw,profiles:{}});}catch(e){message(e.message);}};input.click();}else if(command==='export'){if(!doc)return;const u=URL.createObjectURL(new Blob([doc.raw],{type:'application/json'}));const a=document.createElement('a');a.href=u;a.download='INSFAV-backup.json';a.click();setTimeout(()=>URL.revokeObjectURL(u),1000);}else if(command==='clear-data'){localStorage.removeItem('insfav-preview-data');window.nativeEvent('cleared','');}else if(command==='open')window.open('https://www.instagram.com/'+encodeURIComponent(p.user)+'/','_blank','noopener');else if(command!=='status'&&command!=='image-failed')message('This feature uses the Android application.');}
window.addEventListener('error',e=>{if(native)request('frontend-error',{code:'script_line_'+e.lineno});});window.addEventListener('unhandledrejection',()=>{if(native)request('frontend-error',{code:'unhandled_promise'});});
request('bootstrap');
