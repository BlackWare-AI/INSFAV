'use strict';
const C=InsfavCore,$=id=>document.getElementById(id),native=!!window.Android;
let doc=null,profiles={},stats={usage:0,load:0,clear:0},state={},opened=new Set(),query='',rows=[],positions=[0],mounted=new Map(),scheduled=false,previousFocus=null,lastFocus=null,viewerUser='',toastTimer=0,cacheVersion=0,lastRevision='',imageFailureTimer=0,fullLoader=null,appInfo={},fetcherState={},viewerToken='',remoteLoader=null,remoteShown=false,fetcherPoll=0;
function placeholder(button){const icon=document.createElement('span');icon.className='person-placeholder';icon.setAttribute('aria-hidden','true');button.replaceChildren(icon);}
let uiPreferences={columns:0,language:'en'};
const motionStyle=getComputedStyle(document.documentElement),motion={duration:parseFloat(motionStyle.getPropertyValue('--motion-base'))||160,easing:motionStyle.getPropertyValue('--motion-ease').trim()||'ease-out'};
function request(command,extra={}){if(native)Android.request(JSON.stringify({command,...extra}));else previewCommand(command,extra);}
function message(text){$('toast').textContent=text;$('toast').hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('toast').hidden=true,4200);}
function setDocument(raw){const next=C.parseDocument(raw);doc=next;opened.clear();query='';$('search-input').value='';$('clear-search').hidden=true;rebuild();}
window.nativeEvent=(event,data)=>{
 try{
  if(event==='ui-preferences'){applyUiPreferences(data);return;}
  if(event==='bootstrap'||event==='imported'){
   if(data.uiPreferences)applyUiPreferences(data.uiPreferences,false);
   profiles=data.profiles||{};state=data;if(data.app)appInfo=data.app;if(data.stats)stats=data.stats;
   if(data.raw)setDocument(data.raw);else{doc=null;rebuild();}
   if(event==='imported'){stats.load++;message('JSON imported. All original fields preserved.');}
   updateSettings();$('splash').hidden=true;
  }
  if(event==='status'){
   if(data.folderMessage&&data.folderMessage!==state.folderMessage)message(data.folderMessage);
   let oldProfiles=profiles,changedUsers=null;
   if(data.profiles)profiles=data.profiles;
   else if(data.profileChanges){oldProfiles=Object.create(null);changedUsers=new Set(Object.keys(data.profileChanges));for(const user of changedUsers){oldProfiles[user]=profiles[user];profiles[user]=data.profileChanges[user];}}
   state={...state,...data};delete state.raw;delete state.profiles;delete state.profileChanges;
   const revision=[data.revision,data.matched,data.missing,data.unreadable,data.folderStatus,data.scopeRevision].join(':');
   if(revision!==lastRevision){lastRevision=revision;cacheVersion++;refreshAvatars(oldProfiles,changedUsers);}
   updateSettings();
  }
  if(event==='image-cache-cleared')clearBrowserImages();
  if(event==='thumbnail-cleanup'){message(`${data.deleted} deleted · ${data.preserved} preserved · ${data.errors} errors. ${data.state}`);}
  if(event==='scan-progress'){state.scan=data;state.scanState=data.state;updateSettings();}
  if(event==='cleared'){doc=null;stats.clear++;opened.clear();rebuild();message('Collections cleared. Local photos are unchanged.');request('status');}
  if(event==='cookie-paste'&&!$('fetcher-screen').hidden)$('cookie-text').value=data;
  if(event==='cookie-saved'){$('cookie-text').value='';for(const id of ['cookie-text','cookie-save','cookie-paste'])$(id).disabled=false;}
  if(event==='cookie-save-failed')for(const id of ['cookie-text','cookie-save','cookie-paste'])$(id).disabled=false;
  if(event==='fetcher-status'){
   const revision=[fetcherState.imageRevision||0,fetcherState.sourceRevision||0].join(':');fetcherState=data;updateFetcher();
   if((data.imageRevision||data.sourceRevision)&&[data.imageRevision||0,data.sourceRevision||0].join(':')!==revision)request('status');
  }
  if(event==='remote-full'&&viewerToken===data.token&&viewerUser===data.user){
   if(remoteLoader){remoteLoader.onload=remoteLoader.onerror=null;remoteLoader.src='';}
   remoteLoader=new Image();remoteLoader.onload=()=>{if(viewerToken===data.token){remoteShown=true;$('full-image').src=remoteLoader.src;$('viewer-caption').textContent='@'+data.user+' · Instagram image · '+data.width+' × '+data.height;applyTransform();}};
   remoteLoader.src='/remote-full/'+encodeURIComponent(data.token);
  }
  if(event==='remote-full-failed'&&viewerToken===data.token){
   const login=['COOKIE_REQUIRED','COOKIE_INVALID_EXPIRED','COOKIE_EXPIRED','LOGIN_VERIFICATION_REQUIRED','LOGIN_REQUIRED','ACCESS_BLOCKED'].includes(data.code);
   const detail=data.code==='CHALLENGE_REQUIRED'?'Complete verification in Instagram Login, then tap Refresh.':login?'Open Instagram Login and tap Refresh to verify the session.':data.code==='WEBVIEW_TIMEOUT'?'Instagram did not respond. Open Instagram Login and check the session.':'Online image unavailable';
   $('viewer-caption').textContent+=' · '+detail;
  }
  if(event==='message'||event==='error')message(data);
 }catch(error){message('Unable to read data: '+error.message);$('splash').hidden=true;}
};
function rebuild(preserve=false){
 mainScroll.cancel();
 const groups=doc?C.filterCollections(doc,query):[];
 let measured={};if(uiPreferences.columns){
  const probe=document.createElement('div');probe.className='matrix-row';probe.setAttribute('aria-hidden','true');probe.style.cssText='position:fixed;visibility:hidden;pointer-events:none;left:-1000px;width:'+$('list').getBoundingClientRect().width+'px';probe.style.setProperty('--columns',uiPreferences.columns);
  const cell=document.createElement('div');cell.className='matrix-cell';const image=document.createElement('button');image.className='avatar';const label=document.createElement('button');label.className='matrix-user';label.textContent='@';cell.append(image,label);probe.append(cell);document.body.append(probe);
  measured={cellSize:image.getBoundingClientRect().width,rowHeight:probe.getBoundingClientRect().height,labelHeight:label.getBoundingClientRect().height};probe.classList.add('last');measured.lastRowHeight=probe.getBoundingClientRect().height;probe.remove();
 }
 rows=C.rowsFor(groups,opened,!!query.trim(),{columns:uiPreferences.columns,width:$('list').getBoundingClientRect().width,...measured});positions=C.offsets(rows);
 mainScroll.setExpanded(rows.some(row=>row.type==='account'||row.type==='matrix'));
 $('list').style.height=positions.at(-1)+'px';
 if(!preserve){for(const node of mounted.values()){releaseRow(node);node.remove();}mounted.clear();}
 $('empty').hidden=!!doc;$('search-info').classList.toggle('visible',!!query.trim());
 $('search-info').textContent=groups.reduce((count,collection)=>count+collection.items.length,0)+' RESULTS';renderVisible();
}
function changeLayout(columns){
 const top=-$('list').getBoundingClientRect().top,index=Math.max(0,positions.findIndex((v,i)=>positions[i+1]>top)),row=rows[index];
 const anchor=row?.item?.key||row?.items?.[0]?.key||row?.key,delta=top-(positions[index]||0),oldHeight=row?.height||1;
 uiPreferences.columns=columns;rebuild();
 const next=rows.findIndex(r=>r.key===anchor||r.item?.key===anchor||r.items?.some(i=>i.key===anchor));
 if(next>=0){const listTop=$('list').getBoundingClientRect().top+scrollY;window.scrollTo(0,listTop+positions[next]+Math.min(delta/oldHeight,1)*rows[next].height);schedule();}
}
function applyUiPreferences(data,layout=true){
 const columns=[2,3,4,5].includes(Number(data.columns))?Number(data.columns):0,language=data.language==='tr'?'tr':'en';
 if(layout&&columns!==uiPreferences.columns)changeLayout(columns);else uiPreferences.columns=columns;
 uiPreferences.language=language;$('display-mode').value=String(columns);$('display-mode').textContent=columns?'Matrix · '+columns+' columns':'List';$('language-choice').value=language;$('language-choice').textContent=language.toUpperCase();$('list').classList.toggle('matrix-mode',!!columns);InsfavI18n.set(language);updateSettings();updateFetcher();
}
$('display-mode').onchange=()=>{const data={...uiPreferences,columns:Number($('display-mode').value)};applyUiPreferences(data);request('ui-preferences',data);};
$('language-choice').onchange=()=>{const data={...uiPreferences,language:$('language-choice').value};applyUiPreferences(data);request('ui-preferences',data);};
let pickerFocus=null;
function closePreferencePicker(){hideScreen($('preference-picker'),()=>{$('settings').inert=false;pickerFocus?.focus();});}
function openPreferencePicker(kind){
 const input=$(kind);pickerFocus=input;const language=kind==='language-choice',options=language?[['en','English'],['tr','Türkçe']]:[['0','List'],...[2,3,4,5].map(n=>[String(n),'Matrix · '+n+' columns'])];
 $('preference-title').textContent=language?'Language':'Display Mode';$('preference-options').replaceChildren();
 for(const [value,label]of options){const button=document.createElement('button');button.type='button';button.dataset.value=value;button.id='preference-option-'+value;button.textContent=label;button.setAttribute('role','option');button.setAttribute('aria-selected',String(input.value===value));button.onclick=()=>{input.value=value;input.onchange();closePreferencePicker();};$('preference-options').append(button);}
 $('settings').inert=true;$('preference-picker').hidden=false;InsfavI18n.apply();$('preference-options').querySelector('[aria-selected="true"]')?.focus();
}
$('language-choice').onclick=()=>openPreferencePicker('language-choice');$('display-mode').onclick=()=>openPreferencePicker('display-mode');$('preference-close').onclick=closePreferencePicker;
$('preference-picker').onclick=event=>{if(event.target===$('preference-picker'))closePreferencePicker();};
function highlighted(el,text){el.replaceChildren();const q=query.trim();if(!q){el.textContent=text;return;}const source=text.toLocaleLowerCase('en'),needle=q.toLocaleLowerCase('en');let p=0,i;while((i=source.indexOf(needle,p))!==-1){el.append(document.createTextNode(text.slice(p,i)));const mark=document.createElement('mark');mark.textContent=text.slice(i,i+q.length);el.append(mark);p=i+q.length;}el.append(document.createTextNode(text.slice(p)));}
function notifyImageFailure(){clearTimeout(imageFailureTimer);imageFailureTimer=setTimeout(()=>request('image-failed'),180);}
// A bounded pool of browser Image elements, never a native bitmap or disk cache.
function releaseRow(node){
 for(const button of node.querySelectorAll('.avatar')){releaseAvatar(button);button.onclick=null;button.removeAttribute('data-user');button._rowIndex=-1;button.replaceChildren();}
 const link=node.querySelector('.card-link');if(!link)return;
 clearTimeout(link._hold);link._hold=0;
 link.onclick=link.oncontextmenu=link.onkeydown=link.onpointerdown=link.onpointermove=link.onpointerup=link.onpointercancel=null;
 link.removeAttribute('title');link.removeAttribute('aria-label');
 link.querySelector('.card-name')?.replaceChildren();link.querySelector('.card-user')?.replaceChildren();
}
function makeRow(r,index){const reused=r.type==='account'?rowPool.pop():null;const node=reused||document.createElement('div');node.className='virtual-row';node.style.top=positions[index]+'px';node.style.height=r.height+'px';node.dataset.key=r.key;
 if(r.type==='spacer'){node.setAttribute('aria-hidden','true');return node;}
 if(r.type==='matrix'){
  node.classList.add('matrix-row');node.classList.toggle('last',r.last);node.style.setProperty('--columns',r.columns);node.style.setProperty('--cell',r.cell+'px');
  for(const item of r.items){const cell=document.createElement('div');cell.className='matrix-cell';const a=document.createElement('button');a.className='avatar';a.setAttribute('aria-label','View profile image for @'+item.user);avatarFor(a,item.user);a.onclick=()=>openViewer(item.user);const label=document.createElement('button');label.className='matrix-user';label.textContent='@'+item.user;label.title=label.textContent;label.onclick=()=>request('open',{user:item.user});cell.append(a,label);node.append(cell);}return node;
 }
 if(r.type==='folder'){const open=!!query.trim()||opened.has(r.group.key);node.classList.add('folder-wrap');node.classList.toggle('open',open);const head=document.createElement('button');head.className='folder-header';head.setAttribute('aria-expanded',String(open));const title=document.createElement('span');title.className='folder-title';const name=document.createElement('span');name.textContent=r.group.name.toUpperCase();const count=document.createElement('span');count.className='folder-count';count.textContent=r.group.items.length;title.append(name,count);const arrow=document.createElement('span');arrow.className='folder-arrow';arrow.textContent='›';head.append(title,arrow);head.onclick=()=>{if(query.trim()){message('Clear search to collapse collections.');return;}opened.has(r.group.key)?opened.delete(r.group.key):opened.add(r.group.key);rebuild(true);};node.append(head);}
 else if(reused){node.classList.add('account');node.classList.toggle('last',r.last);const a=node.querySelector('.avatar'),link=node.querySelector('.card-link');a.setAttribute('aria-label','View profile image for @'+r.item.user);avatarFor(a,r.item.user);a.onclick=()=>openViewer(r.item.user);highlighted(link.querySelector('.card-name'),r.item.name);highlighted(link.querySelector('.card-user'),'@'+r.item.source.pageId.replace(/^@/,''));link.onclick=()=>request('open',{user:r.item.user});}
 else{node.classList.add('account');node.classList.toggle('last',r.last);const a=document.createElement('button');a.className='avatar';a.setAttribute('aria-label','View profile image for @'+r.item.user);avatarFor(a,r.item.user,r.item.name);a.onclick=()=>openViewer(r.item.user);const link=document.createElement('button');link.className='card-link';const name=document.createElement('div');name.className='card-name';highlighted(name,r.item.name);const username=document.createElement('div');username.className='card-user';highlighted(username,'@'+r.item.source.pageId.replace(/^@/,''));link.append(name,username);link.onclick=()=>request('open',{user:r.item.user});node.append(a,link);}
 return node;
}
function renderVisible(){
 scheduled=false;if(document.hidden)return;const list=$('list'),top=-list.getBoundingClientRect().top;
 const now=performance.now(),elapsed=now-scrollTimeLast;if(elapsed>0&&elapsed<160)scrollVelocity=(top-scrollTopLast)/elapsed;else scrollVelocity=0;scrollTopLast=top;scrollTimeLast=now;
 const [start,end]=C.windowRange(positions,top,window.innerHeight,C.overscanFor(scrollVelocity)),wanted=new Set(),fragment=document.createDocumentFragment();
 for(let i=start;i<end;i++){
  const row=rows[i];wanted.add(row.key);let node=mounted.get(row.key);
  if(!node){node=makeRow(row,i);mounted.set(row.key,node);fragment.append(node);}
  else{
   const position=positions[i]+'px';if(node.style.top!==position)node.style.top=position;
   const height=row.height+'px';if(node.style.height!==height)node.style.height=height;
   if(row.type==='folder'){const open=!!query.trim()||opened.has(row.group.key);node.classList.toggle('open',open);node.querySelector('button').setAttribute('aria-expanded',String(open));}
  }
  for(const button of node.querySelectorAll('.avatar'))button._rowIndex=i;
 }
 list.append(fragment);
 for(const [key,node]of mounted)if(!wanted.has(key)){
  releaseRow(node);
  const button=node.classList.contains('account')?node.querySelector('.avatar'):null;node.remove();mounted.delete(key);if(button&&rowPool.length<32){button.replaceChildren();rowPool.push(node);}
 }
 prioritizeAvatars(top);
 mainScroll.update();
}
function schedule(){if(document.hidden)return;if(!scheduled){scheduled=true;requestAnimationFrame(renderVisible);}}
// Floating controls overlay the right edge without changing list geometry.
const mainScroll=(()=>{
 const controls=document.createElement('nav');controls.id='main-scroll';controls.setAttribute('aria-label','List scrolling');controls.hidden=true;
 const makeButton=(id,label,path)=>{const button=document.createElement('button');button.id=id;button.type='button';button.setAttribute('aria-label',label);button.innerHTML='<svg viewBox="0 0 12 12" aria-hidden="true"><path d="'+path+'"/></svg>';return button;};
 const up=makeButton('scroll-start','Go to start of list','M2 8 6 4l4 4'),down=makeButton('scroll-end','Go to end of list','M2 4l4 4 4-4');
 const track=document.createElement('div');track.className='scroll-track';
 const thumb=document.createElement('div');thumb.id='scroll-thumb';thumb.tabIndex=0;thumb.setAttribute('role','scrollbar');thumb.setAttribute('aria-controls','list');thumb.setAttribute('aria-orientation','vertical');thumb.setAttribute('aria-label','List position; hold for 0.1 seconds to drag');thumb.setAttribute('aria-valuemin','0');thumb.setAttribute('aria-valuemax','100');
 const grip=document.createElement('span');grip.id='scroll-grip';grip.setAttribute('aria-hidden','true');thumb.append(grip);track.append(thumb);controls.append(up,down,track);document.body.append(controls);
 const surfaces=['splash','preference-picker','dialog','settings','fetcher-screen','features-screen','viewer'].map($);
 let nativePaintHidden=false,expanded=false,hold=null,idleTimer=0,timer=0,jumpFrame=0,jumpSerial=0,metrics=C.scrollMetrics(0,0,0,0);
 const available=()=>!document.hidden&&!document.body.classList.contains('overlay-open')&&surfaces.every(el=>el.hidden);
 function wake(){controls.classList.remove('idle');clearTimeout(idleTimer);idleTimer=setTimeout(()=>{if(hold||controls.querySelector(':focus-visible')){wake();return;}controls.classList.add('idle');},480);}
 function cancelJump(){if(jumpFrame)cancelAnimationFrame(jumpFrame);jumpFrame=0;jumpSerial++;}
 function cancel(){cancelJump();clearTimeout(timer);timer=0;const previous=hold;hold=null;thumb.classList.remove('holding','armed');if(previous&&thumb.hasPointerCapture(previous.id))thumb.releasePointerCapture(previous.id);}
 function update(){
  const root=document.scrollingElement,viewport=document.documentElement.clientHeight,right=document.documentElement.getBoundingClientRect().right,height=document.querySelector('.bottom-nav').getBoundingClientRect().top-110;
  metrics=C.scrollMetrics(root.scrollHeight,viewport,Math.max(0,height),root.scrollTop);
  const visible=expanded&&available()&&metrics.max>0&&height>=48;
  if(nativePaintHidden!==visible){nativePaintHidden=visible;request('main-scroll-controls',{visible});}if(visible&&controls.hidden)wake();controls.hidden=!visible;document.documentElement.classList.toggle('main-scroll-visible',visible);
  if(!visible){cancel();return;}
  controls.style.left=(right-30)+'px';track.style.height=height+'px';thumb.style.height=metrics.thumb+'px';thumb.style.transform='translateY('+metrics.offset+'px)';
  thumb.setAttribute('aria-valuenow',String(Math.round(metrics.position/metrics.max*100)));
  up.disabled=metrics.position<=1;down.disabled=metrics.max-metrics.position<=1;
 }
 const go=target=>{
  cancel();wake();const serial=jumpSerial,started=performance.now(),origin=document.scrollingElement.scrollTop;
  const duration=matchMedia('(prefers-reduced-motion: reduce)').matches?0:Math.min(650,Math.max(240,Math.abs(target-origin)/10));
  let settleStarted=0,frames=8;
  const step=()=>{if(serial!==jumpSerial)return;jumpFrame=0;if(!available())return;
   const root=document.scrollingElement,end=Math.max(0,Math.min(root.scrollHeight-root.clientHeight,target)),now=performance.now(),progress=duration?Math.min(1,(now-started)/duration):1;
   window.scrollTo(0,origin+(end-origin)*(1-Math.pow(1-progress,3)));schedule();
   if(progress<1){jumpFrame=requestAnimationFrame(step);return;}
   // Bound the final settling so a residual compositor fling cannot move the endpoint.
   if(!settleStarted)settleStarted=now;if(--frames>0&&now-settleStarted<150)jumpFrame=requestAnimationFrame(step);
  };step();
 };
 up.onclick=()=>go(0);down.onclick=()=>go(metrics.max);
 // Chrome suppresses click for the tap that stops an active touch fling.
 for(const [button,target]of [[up,()=>0],[down,()=>metrics.max]])button.addEventListener('pointerup',event=>{if(event.pointerType==='touch'&&event.isPrimary){event.preventDefault();go(target());}});
 thumb.addEventListener('pointerdown',event=>{
  if(controls.hidden||!available()||!event.isPrimary||(event.pointerType==='mouse'&&event.button!==0))return;
  if(hold){cancel();return;}event.preventDefault();wake();
  hold={id:event.pointerId,x:event.clientX,y:event.clientY,lastY:event.clientY,width:innerWidth,height:innerHeight,armed:false};thumb.setPointerCapture(event.pointerId);thumb.classList.add('holding');
  timer=setTimeout(()=>{if(!hold||!available()||hold.width!==innerWidth||hold.height!==innerHeight){cancel();return;}update();if(!hold)return;hold.armed=true;hold.originY=hold.lastY;hold.originScroll=document.scrollingElement.scrollTop;hold.metrics=metrics;thumb.classList.remove('holding');thumb.classList.add('armed');},100);
 });
 thumb.addEventListener('pointermove',event=>{
  if(!hold||hold.id!==event.pointerId)return;if(hold.width!==innerWidth||hold.height!==innerHeight){cancel();return;}event.preventDefault();hold.lastY=event.clientY;
  if(!hold.armed){if(Math.hypot(event.clientX-hold.x,event.clientY-hold.y)>8)cancel();return;}
  window.scrollTo(0,C.dragScrollTarget(hold.metrics,hold.originScroll,event.clientY-hold.originY));schedule();
 });
 for(const name of ['pointerup','pointercancel','lostpointercapture'])thumb.addEventListener(name,event=>{if(hold?.id===event.pointerId)cancel();});
 thumb.addEventListener('contextmenu',event=>event.preventDefault());
 thumb.addEventListener('keydown',event=>{
  const actions={Home:0,End:metrics.max,ArrowUp:metrics.position-68,ArrowDown:metrics.position+68,PageUp:metrics.position-innerHeight,PageDown:metrics.position+innerHeight};
  if(Object.hasOwn(actions,event.key)){event.preventDefault();go(Math.max(0,Math.min(metrics.max,actions[event.key])));}
 });
 window.addEventListener('scroll',wake,{passive:true});controls.addEventListener('pointerenter',wake);controls.addEventListener('focusin',wake);
 const observer=new MutationObserver(()=>{if(!available())cancel();schedule();});observer.observe(document.body,{attributes:true,attributeFilter:['class']});for(const surface of surfaces)observer.observe(surface,{attributes:true,attributeFilter:['hidden']});
 window.addEventListener('blur',cancel);window.addEventListener('resize',cancel,{passive:true});window.addEventListener('wheel',cancel,{passive:true});document.addEventListener('visibilitychange',()=>{cancel();schedule();});
 document.addEventListener('pointerdown',event=>{wake();cancelJump();if(hold&&hold.id!==event.pointerId)cancel();},true);document.addEventListener('keydown',event=>{cancelJump();if(event.key==='Escape')cancel();},true);
 return {cancel,update,setExpanded:value=>{expanded=value;}};
})();
function refreshAvatars(previous={},changedUsers=null){for(const node of mounted.values()){for(const button of node.querySelectorAll('.avatar')){const user=button.dataset.user;if((!changedUsers||changedUsers.has(user))&&(previous[user]?.file!==profiles[user]?.file||previous[user]?.version!==profiles[user]?.version))avatarFor(button,user);}}prioritizeAvatars();}
window.addEventListener('scroll',schedule,{passive:true});window.addEventListener('resize',()=>{if(uiPreferences.columns)changeLayout(uiPreferences.columns);else schedule();},{passive:true});
let searchTimer;$('search-input').addEventListener('input',()=>{clearTimeout(searchTimer);searchTimer=setTimeout(()=>{query=$('search-input').value;$('clear-search').hidden=!query;window.scrollTo(0,0);rebuild();},130);});$('clear-search').onclick=()=>{$('search-input').value='';query='';$('clear-search').hidden=true;rebuild();$('search-input').focus();};
function showDialog(title,body,actions){const overlay=$('dialog');overlay._exitAnimation?.cancel();delete overlay.dataset.closing;overlay.style.pointerEvents='';overlay.classList.toggle('about-dialog',!title);lastFocus=document.activeElement;$('dialog-title').textContent=title;$('dialog-title').hidden=!title;const modal=$('dialog-title').parentElement;if(title){modal.setAttribute('aria-labelledby','dialog-title');modal.removeAttribute('aria-label');}else{modal.removeAttribute('aria-labelledby');modal.setAttribute('aria-label','Application information');}for(const id of ['import-btn','clear-btn']){const button=$(id);if(button&&$('dialog-body').contains(button))$('collection-actions').append(button);}$('dialog-body').replaceChildren();if(typeof body==='string'){const p=document.createElement('p');p.className='muted';p.textContent=body;$('dialog-body').append(p);}else $('dialog-body').append(body);$('dialog-actions').replaceChildren();for(const action of actions){const b=document.createElement('button');b.className='btn'+(action.danger?' destructive':'');b.textContent=action.label;b.onclick=()=>{b.disabled=true;closeDialog(action.run);};$('dialog-actions').append(b);}$('dialog').hidden=false;document.body.classList.add('overlay-open');$('dialog-actions').querySelector('button')?.focus();}
function closeDialog(done){if($('dialog').hidden){done?.();return;}hideScreen($('dialog'),()=>{if($('settings').hidden&&$('fetcher-screen').hidden)document.body.classList.remove('overlay-open');lastFocus?.focus?.();done?.();});}
function confirmAction(title,text,action){showDialog(title,text,[{label:'Confirm',danger:true,run:action},{label:'Cancel'}]);}
const features=['Instagram account collections','JSON import and export','Collection management','Account search','Local profile thumbnails','Optional session-based thumbnail retrieval','External thumbnail folder integration','Fullscreen image viewer','Efficient large-list rendering','Persistent settings','AMOLED interface'];
function showInfo(){
 const container=document.createElement('div');
 for(const [label,value]of [['Developer','BlackWare'],['AI Development Assistance','OpenAI ChatGPT'],['Version',appInfo.version||'Unavailable'],['Build',appInfo.build??'Unavailable'],['Total Accounts',doc?.total||0],['Usage Count',stats.usage],['Load Count',stats.load],['Reset Count',stats.clear]]){
  const card=document.createElement('div');card.className='about-detail';const name=document.createElement('span');name.textContent=label;const detail=document.createElement('strong');detail.textContent=value;card.append(name,detail);container.append(card);
 }
 for(const [label,id]of [['Add List','import-btn'],['Delete List','clear-btn']]){const row=document.createElement('div');row.className='about-detail about-action';const name=document.createElement('span');name.textContent=label;row.append(name,$(id));container.append(row);}
 const menu=document.createElement('button');menu.id='features-menu';menu.className='about-menu';menu.textContent='Features & Functions';menu.onclick=openFeatures;container.append(menu);
 const note=document.createElement('p');note.className='muted footnote';note.textContent='INSFAV is an independent Instagram account collection manager and is not affiliated with Meta or Instagram.';container.append(note);
 showDialog('',container,[{label:'Settings',run:openSettings},{label:'Close'}]);
}
function openFeatures(){
 const list=$('features-list');list.replaceChildren();for(const feature of features){const item=document.createElement('li');item.textContent=feature;list.append(item);}
 $('dialog').inert=true;$('features-screen').hidden=false;$('features-screen').scrollTop=0;$('features-back').focus();
}
function closeFeatures(){hideScreen($('features-screen'),()=>{$('dialog').inert=false;$('features-menu')?.focus();});}
$('features-back').onclick=closeFeatures;
function hideScreen(element,done){
 if(element.dataset.closing)return;element.dataset.closing='true';element.style.pointerEvents='none';
 const finish=()=>{element.hidden=true;delete element.dataset.closing;element.style.pointerEvents='';element._exitAnimation=null;done?.();};
 if(matchMedia('(prefers-reduced-motion: reduce)').matches){finish();return;}
 element._exitAnimation=element.animate([{opacity:1},{opacity:0}],{duration:motion.duration,easing:motion.easing});element._exitAnimation.onfinish=finish;
}
function openSettings(){previousFocus=document.activeElement;$('settings').hidden=false;document.body.classList.add('overlay-open');updateSettings();request('status');$('settings-back').focus();}
function closeSettings(){hideScreen($('settings'),()=>{document.body.classList.remove('overlay-open');previousFocus?.focus?.();});}
const actions=[['Select Thumbnail Folder','select-folder'],['Rescan Thumbnails','rescan'],['Clear Image Cache','clear-cache'],['Clear Thumbnail Index','clear-index']];
for(const [label,action]of actions){const button=document.createElement('button');button.textContent=label;button.dataset.action=action;$('profile-actions').append(button);}
function bytes(value){return (Number(value||0)/1048576).toFixed(2)+' MiB';}
function changedText(node,value){const text=String(value);if(node.textContent!==text)node.textContent=text;}
function updateSettings(){
 if($('settings').hidden)return;
 const scan=state.scan||{},busy=state.scanState==='scanning',date=state.lastScan?new Date(state.lastScan).toLocaleString():'Never';
 const values=[['Total Accounts',doc?.total||0],['Unique Accounts',doc?.unique.length||0],['Matched Images',state.matched||0],['Missing Images',state.missing??doc?.unique.length??0],['Indexed Folder Size',bytes(state.folderBytes)+(state.unknownSizes?' + unknown sizes':'')],['Image Cache Size',bytes(state.cacheBytes)],['Thumbnail Index Size',bytes(state.storage?.thumbnailIndex)],['WebView Storage',bytes(state.storage?.app_webview)],['Scan Checkpoint Index Size',bytes(state.storage?.thumbnailCheckpoint)],['Invalid Resolution Images',state.resolution?.count||0],['Last Scan Date',date],['Selected Folder',state.folder||'Not selected']];
 for(const [label,value]of values){
  const existing=[...$('metrics').children].find(node=>node.dataset.metric===label);
  if(existing){
   const detail=existing.querySelector('b');changedText(detail,value);
   const copy=existing.querySelector('.copy-missing');if(copy)copy.disabled=Number(value)===0;
   if(label==='Selected Folder')detail.title=String(value);
   if(label==='Invalid Resolution Images'){
    let note=existing.querySelector('.resolution-note');const visible=!state.resolution?.complete||state.resolution?.unknown;
    if(!note&&visible){note=document.createElement('span');note.className='resolution-note';existing.append(note);}
    if(note){note.hidden=!visible;changedText(note,!state.resolution?.complete?'Rescan for full folder count':state.resolution.unknown+' unknown dimensions');}
    existing.title='Target: 256×256 · '+(state.resolution?.complete?'All images in the last completed scan':'Saved dimensions; Rescan includes every folder image')+(state.resolution?.unknown?' · '+state.resolution.unknown+' unknown dimensions':'');
   }
   continue;
  }
  const metric=document.createElement('div');metric.className='metric';metric.dataset.metric=label;
  const detail=document.createElement('b');detail.textContent=value;const name=document.createElement('span');name.textContent=label;
  if(label==='Missing Images'){
   const head=document.createElement('div');head.className='metric-head';const copy=document.createElement('button');copy.id='copy-missing-users';copy.className='copy-missing';copy.setAttribute('aria-label','Copy missing usernames');copy.title='Copy missing usernames';copy.disabled=Number(value)===0;
   copy.innerHTML='<svg viewBox="0 0 24 34" aria-hidden="true"><rect x="8" y="10" width="12" height="20" rx="2"/><path d="M16 10V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v17a2 2 0 0 0 2 2h3"/></svg>';
   copy.onclick=()=>request('copy-missing-users');head.append(detail,copy);metric.append(head,name);
  }else metric.append(detail,name);
  if(label==='Selected Folder'){metric.classList.add('folder-metric');detail.title=String(value);}
  if(label==='Invalid Resolution Images'&&(!state.resolution?.complete||state.resolution?.unknown)){const note=document.createElement('span');note.className='resolution-note';note.textContent=!state.resolution?.complete?'Rescan for full folder count':state.resolution.unknown+' unknown dimensions';metric.append(note);}
  if(label==='Invalid Resolution Images')metric.title='Target: 256×256 · '+(state.resolution?.complete?'All images in the last completed scan':'Saved dimensions; Rescan includes every folder image')+(state.resolution?.unknown?' · '+state.resolution.unknown+' unknown dimensions':'');
  $('metrics').append(metric);
 }
 changedText($('scan-state'),(state.scanState||'idle').replaceAll('_',' ').replace(/^./,value=>value.toUpperCase()));
 $('progress').max=busy?Math.max(1,scan.total||1):1;$('progress').value=busy?(scan.processed||0):state.scanState==='completed'?1:0;
 changedText($('progress-detail'),scan.message||(busy?`${scan.processed||0} / ${scan.total||0} folder entries checked`:'Rescan updates the index without changing source files. Retry an interrupted scan to resume saved work. Image Cache Size counts legacy app image copies; WebView Storage includes browser-managed assets and data. Rescan after external changes.'));
 $('folder-warning').hidden=!state.folderMessage;changedText($('folder-warning'),state.folderMessage||'');
 changedText($('version-info'),`INSFAV ${appInfo.version||''} · Build ${appInfo.build??'—'} · BlackWare`);
 for(const button of $('profile-actions').querySelectorAll('button'))button.disabled=busy&&button.dataset.action!=='clear-cache';
}
document.addEventListener('click',event=>{
 const action=event.target.closest('[data-action]')?.dataset.action;if(!action)return;
 if(action.startsWith('clear-'))confirmAction('Confirm deletion',action==='clear-data'?'Remove the collection document? Local photos and their index are unchanged.':'Clear the selected app data? Source photos and collections will be preserved.',()=>request(action));
 else request(action);
});
$('info-btn').onclick=showInfo;$('import-btn').onclick=()=>closeDialog(()=>request('import'));$('clear-btn').onclick=()=>closeDialog(()=>confirmAction('Reset collections?','The collection database will be cleared. Profile images are managed separately.',()=>request('clear-data')));$('settings-back').onclick=closeSettings;
function openFetcher(){
 previousFocus=document.activeElement;$('settings').inert=true;$('fetcher-screen').hidden=false;$('fetcher-back').focus();request('fetcher-status');
 clearInterval(fetcherPoll);if(!native)fetcherPoll=setInterval(()=>request('fetcher-status'),1500);
}
function closeFetcher(){clearInterval(fetcherPoll);fetcherPoll=0;$('cookie-text').value='';hideScreen($('fetcher-screen'),()=>{$('settings').inert=false;$('fetcher-menu').focus();});}
function updateFetcher(){
 const f=fetcherState;if($('fetcher-screen').hidden)return;
 $('fetcher-disabled').checked=!!f.bulkDisabled;
 changedText($('instagram-session-state'),f.instagramSession||'Persistent WebView Session · Not verified');
 changedText($('cookie-state'),f.manualCookiesSaved?'Cookies saved · session checked during retrieval':'Not configured');
 changedText($('fetcher-state'),(f.scheduleStatus||'')+' · '+(f.state||'Idle')+(f.blocked==='RATE_LIMITED'?' · '+(f.blockedHttp===429?'HTTP 429':'Instagram restriction')+(f.retryAt?' · Cooldown until '+new Date(f.retryAt).toLocaleTimeString():'')+(f.automaticResume?' · Automatic resume scheduled':' · Manual resume'):''));changedText($('fetcher-folder'),(f.folder||'Not selected')+(f.writable?' · Write access granted':' · Read only'));$('fetcher-write').hidden=!!f.writable;
 for(const [id,value] of [['fetcher-concurrency',f.requestedViews],['fetcher-min',f.minimum],['fetcher-max',f.maximum],['fetcher-start',f.start],['fetcher-end',f.end]]){const input=$(id);if(value!==undefined&&document.activeElement!==input&&!input.dataset.edited)input.value=value;}
 if(!$('fetcher-background').dataset.edited)$('fetcher-background').checked=!!f.background;
 changedText($('fetcher-capacity'),'Requested WebViews: '+(f.requestedViews||1)+' · Effective WebViews: '+(f.effectiveViews||1)+' · Active WebViews: '+(f.activeViews||0)+(f.effectiveViews<f.requestedViews?' · Reduced by the memory budget':''));
 const values=[['Active Accounts',f.activeAccounts||0],['Accounts / Minute',Number(f.completedPerMinute||0).toFixed(1)],['Peak Active Accounts',f.peakAccounts||0],['App PSS (MiB)',((f.memoryPssKiB||0)/1024).toFixed(1)],['Total Unique Accounts',f.total||0],['Successful',f.success||0],['Existing',f.existing||0],['Failed',f.failed||0],['Skipped Profiles',f.skipped||0],['Retry Later',f.retry||0],['Pending',f.pending||0],['Processed',f.processed||0]];
 for(const [label,value]of values){let metric=[...$('fetcher-metrics').children].find(node=>node.dataset.metric===label);if(metric){changedText(metric.querySelector('b'),value);continue;}const row=document.createElement('div');row.className='metric';row.dataset.metric=label;const name=document.createElement('span'),count=document.createElement('b');name.textContent=label;count.textContent=value;row.append(count,name);$('fetcher-metrics').append(row);}
 $('fetcher-progress').max=Math.max(1,f.total||0);$('fetcher-progress').value=f.processed||0;
 const process=f.process||{},reviewed=Math.min(f.total||0,(f.processed||0)+(f.held||0));
 changedText($('fetcher-process-phase'),process.phase||f.state||'Idle');
 changedText($('fetcher-process-detail'),(process.active??f.activeAccounts??0)+' active · '+(process.queued||0)+' queued · '+(process.handledThisRun||0)+' handled this run'+(process.waitSeconds>0?' · Next request in '+process.waitSeconds+' s':''));
 changedText($('fetcher-process-result'),process.lastResult?'Last result: '+process.lastResult.replaceAll('_',' '):'Waiting for the next result');
 $('fetcher-run-progress').max=Math.max(1,f.total||0);$('fetcher-run-progress').value=reviewed;
 changedText($('fetcher-history'),reviewed+' / '+(f.total||0)+' accounts reviewed · '+((f.skipped||0)+(f.failed||0)+(f.held||0))+' saved outcomes · Retried only with Retry Skipped / Failed Profiles');
 changedText($('fetcher-current'),(f.resolution?.verificationRecommended?'External folder changes detected · Verify Fetcher Index recommended · ':'')+'Elapsed '+(f.elapsedSeconds||0)+' s'+(f.etaSeconds>=0?' · ETA '+f.etaSeconds+' s':''));
 $('fetcher-verify').disabled=!!f.running||!!f.bulkDisabled;$('fetcher-missing').disabled=!!f.running||!!f.bulkDisabled;$('fetcher-resume').disabled=!!f.running||!!f.bulkDisabled;$('fetcher-retry-skipped').disabled=!!f.running||!!f.bulkDisabled;
}
$('fetcher-menu').onclick=openFetcher;$('fetcher-back').onclick=closeFetcher;
for(const input of $('fetcher-screen').querySelectorAll('input'))input.addEventListener('input',()=>input.dataset.edited='true');
$('instagram-webview').onclick=()=>request('instagram-session-method',{manual:false});
$('instagram-manual').onclick=()=>request('instagram-session-method',{manual:true});
$('instagram-login').onclick=()=>request('instagram-session-open');
$('fetcher-disabled').onchange=()=>request('fetcher-disabled',{disabled:$('fetcher-disabled').checked});
$('cookie-paste').onclick=async()=>{if(native){request('fetcher-paste');return;}try{$('cookie-text').value=await navigator.clipboard.readText();}catch(error){$('cookie-text').focus();message('Use the Android Paste command in the cookie field.');}};
$('cookie-save').onclick=()=>{const text=$('cookie-text').value;for(const id of ['cookie-text','cookie-save','cookie-paste'])$(id).disabled=true;request('fetcher-cookies-save',{text});};
$('cookie-remove').onclick=()=>{$('cookie-text').value='';confirmAction('Remove Instagram Cookies?','Retrieval stops. Collections and local photos stay unchanged.',()=>request('fetcher-cookies-remove'));};
$('fetcher-save').onclick=()=>{request('fetcher-config',{minimum:Number($('fetcher-min').value),maximum:Number($('fetcher-max').value),start:$('fetcher-start').value,end:$('fetcher-end').value,background:$('fetcher-background').checked,concurrentWebViews:Number($('fetcher-concurrency').value)});for(const input of $('fetcher-screen').querySelectorAll('input'))delete input.dataset.edited;};
$('fetcher-write').onclick=()=>request('fetcher-write-access');
for(const [id,action]of [['fetcher-missing','missing'],['fetcher-pause','pause'],['fetcher-resume','resume'],['fetcher-stop','stop'],['fetcher-retry-skipped','retry_skipped']])$(id).onclick=()=>request('fetcher-action',{action});
$('fetcher-verify').onclick=()=>confirmAction('Verify Fetcher Index?','Explicitly validate the selected folder and repair Fetcher metadata. Photos, normal INSFAV indexing and collections are unchanged.',()=>request('fetcher-action',{action:'verify_index'}));
$('fetcher-diagnostics').onclick=()=>request('fetcher-logs');
let scale=1,tx=0,ty=0,pointers=new Map(),gesture=null,lastTap=0,lastTapX=0,lastTapY=0;
let transformFrame=0,transformGeometry=null;
function clampTransform(){if(!transformGeometry)return;const [width,height,stageWidth,stageHeight]=transformGeometry,mx=Math.max(0,(width*scale-stageWidth)/2),my=Math.max(0,(height*scale-stageHeight)/2);tx=Math.min(mx,Math.max(-mx,tx));ty=Math.min(my,Math.max(-my,ty));}
function cancelTransform(){cancelAnimationFrame(transformFrame);transformFrame=0;transformGeometry=null;}
function applyTransform(){
 clampTransform();if(transformFrame||document.hidden||$('viewer').hidden)return;
 transformFrame=requestAnimationFrame(()=>{transformFrame=0;if(document.hidden||$('viewer').hidden)return;const img=$('full-image'),stage=$('image-stage');transformGeometry=[img.offsetWidth,img.offsetHeight,stage.clientWidth,stage.clientHeight];clampTransform();img.style.transform=`translate(${tx}px,${ty}px) scale(${scale})`;});
}
function resetTransform(){scale=1;tx=ty=0;applyTransform();}
function openViewer(user){
 previousFocus=document.activeElement;viewerUser=user;viewerToken='v'+Date.now().toString(36)+Math.random().toString(36).slice(2);remoteShown=false;$('viewer').hidden=false;document.body.classList.add('overlay-open');
 if(profiles[user]?.file)$('full-image').src='/thumb/'+encodeURIComponent(user)+'?v='+cacheVersion;else $('full-image').removeAttribute('src');
 const image=profiles[user]||{},dimensions=image.width&&image.height?` · ${image.width} × ${image.height}`:'';
 $('viewer-caption').textContent='@'+user+(image.file?' · Local image':'')+dimensions+(Math.max(image.width||0,image.height||0)>2048?' · Preview up to 2048 px':'');
 resetTransform();$('viewer-back').focus();
 if(profiles[user]?.file){fullLoader=new Image();fullLoader.onload=()=>{if(viewerUser===user&&!remoteShown){$('full-image').src=fullLoader.src;applyTransform();}};
 fullLoader.onerror=()=>{if(viewerUser===user)notifyImageFailure();};fullLoader.src='/full/'+encodeURIComponent(user)+'?v='+cacheVersion;}
 request('view-remote',{user,token:viewerToken});
}
function closeViewer(){
 cancelTransform();
 viewerUser='';viewerToken='';remoteShown=false;request('view-closed');if(remoteLoader){remoteLoader.onload=remoteLoader.onerror=null;remoteLoader.src='';remoteLoader=null;}if(fullLoader){fullLoader.onload=fullLoader.onerror=null;fullLoader.src='';fullLoader=null;}
 pointers.clear();hideScreen($('viewer'),()=>{$('full-image').removeAttribute('src');if($('settings').hidden&&$('fetcher-screen').hidden)document.body.classList.remove('overlay-open');previousFocus?.focus?.();});
}
$('viewer-back').onclick=closeViewer;$('full-image').onload=applyTransform;
const stage=$('image-stage');stage.addEventListener('pointerdown',e=>{stage.setPointerCapture(e.pointerId);pointers.set(e.pointerId,{x:e.clientX,y:e.clientY,startX:e.clientX,startY:e.clientY});if(pointers.size===2){const [a,b]=[...pointers.values()];gesture={distance:Math.hypot(a.x-b.x,a.y-b.y),scale,tx,ty,cx:(a.x+b.x)/2,cy:(a.y+b.y)/2};}else gesture=null;});stage.addEventListener('pointermove',e=>{const p=pointers.get(e.pointerId);if(!p)return;const dx=e.clientX-p.x,dy=e.clientY-p.y;p.x=e.clientX;p.y=e.clientY;if(pointers.size===2&&gesture){const [a,b]=[...pointers.values()];scale=Math.min(6,Math.max(1,gesture.scale*Math.hypot(a.x-b.x,a.y-b.y)/Math.max(1,gesture.distance)));tx=gesture.tx+(a.x+b.x)/2-gesture.cx;ty=gesture.ty+(a.y+b.y)/2-gesture.cy;}else if(scale>1){tx+=dx;ty+=dy;}applyTransform();});function release(e){const p=pointers.get(e.pointerId);if(e.type==='pointerup'&&pointers.size===1&&p&&Math.hypot(p.x-p.startX,p.y-p.startY)<10){const now=Date.now();if(now-lastTap<300&&Math.hypot(p.x-lastTapX,p.y-lastTapY)<40){scale=scale>1?1:2.5;tx=ty=0;applyTransform();lastTap=0;}else{lastTap=now;lastTapX=p.x;lastTapY=p.y;}}pointers.delete(e.pointerId);gesture=null;}stage.addEventListener('pointerup',release);stage.addEventListener('pointercancel',release);stage.addEventListener('wheel',e=>{e.preventDefault();scale=Math.min(6,Math.max(1,scale+(e.deltaY<0?.2:-.2)));applyTransform();},{passive:false});
window.handleBack=()=>{if(!$('preference-picker').hidden){closePreferencePicker();return true;}if(!$('dialog').hidden&&!$('fetcher-screen').hidden){closeDialog();return true;}if(!$('fetcher-screen').hidden){closeFetcher();return true;}if(!$('features-screen').hidden){closeFeatures();return true;}if(!$('viewer').hidden){closeViewer();return true;}if(!$('dialog').hidden){closeDialog();return true;}if(!$('settings').hidden){closeSettings();return true;}if(query){$('clear-search').click();return true;}return false;};
document.addEventListener('keydown',e=>{if(e.key==='Escape')window.handleBack();if(e.key==='Tab'){const surface=!$('preference-picker').hidden?$('preference-picker'):!$('dialog').hidden&&!$('fetcher-screen').hidden?$('dialog'):!$('fetcher-screen').hidden?$('fetcher-screen'):!$('features-screen').hidden?$('features-screen'):!$('viewer').hidden?$('viewer'):!$('dialog').hidden?$('dialog'):!$('settings').hidden?$('settings'):null;if(!surface)return;const focus=[...surface.querySelectorAll('button:not(:disabled),input')].filter(el=>!el.hidden);const first=focus[0],last=focus[focus.length-1];if(e.shiftKey&&document.activeElement===first){last.focus();e.preventDefault();}else if(!e.shiftKey&&document.activeElement===last){first.focus();e.preventDefault();}}});
function previewCommand(command,p){if(command==='bootstrap'){appInfo={version:'Browser preview',build:'—'};try{const raw=localStorage.getItem('insfav-preview-data');window.nativeEvent('bootstrap',{raw:raw||'',uiPreferences:JSON.parse(localStorage.getItem('insfav-ui')||'{}'),profiles:{},stats:{usage:1,load:0,clear:0}});}catch(e){window.nativeEvent('error',e.message);$('splash').hidden=true;}}else if(command==='ui-preferences'){localStorage.setItem('insfav-ui',JSON.stringify({columns:p.columns,language:p.language}));}else if(command==='import'){const input=document.createElement('input');input.type='file';input.accept='.json,application/json';input.onchange=async()=>{try{const raw=await input.files[0].text();C.parseDocument(raw);localStorage.setItem('insfav-preview-data',raw);window.nativeEvent('imported',{raw,profiles:{}});}catch(e){message(e.message);}};input.click();}else if(command==='export'){if(!doc)return;const u=URL.createObjectURL(new Blob([doc.raw],{type:'application/json'}));const a=document.createElement('a');a.href=u;a.download='INSFAV-backup.json';a.click();setTimeout(()=>URL.revokeObjectURL(u),1000);}else if(command==='clear-data'){localStorage.removeItem('insfav-preview-data');window.nativeEvent('cleared','');}else if(command==='copy-missing-users'){const users=(doc?.unique||[]).filter(user=>/^[a-z0-9._]{1,30}$/.test(user)&&!profiles[user]?.file);if(!users.length){message('No missing usernames.');return;}navigator.clipboard.writeText(users.join('\n')).then(()=>message(users.length+' usernames copied.')).catch(()=>message('Clipboard unavailable. Try again.'));}else if(command==='open')window.open('https://www.instagram.com/'+encodeURIComponent(p.user)+'/','_blank','noopener');else if(command!=='status'&&command!=='image-failed'&&command!=='main-scroll-controls')message('This feature uses the Android application.');}
window.addEventListener('error',e=>{if(native)request('frontend-error',{code:'script_line_'+e.lineno});});window.addEventListener('unhandledrejection',()=>{if(native)request('frontend-error',{code:'unhandled_promise'});});
request('bootstrap');

document.addEventListener('visibilitychange',()=>{if(document.hidden)cancelTransform();else{schedule();applyTransform();}});
window.addEventListener('resize',()=>{transformGeometry=null;applyTransform();},{passive:true});
