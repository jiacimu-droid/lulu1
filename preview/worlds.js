/* One renderer, camera, touch controller and image atlas across three DIFFERENT scenes.
 * Android state/AI/official actions are deliberately not simulated as authoritative facts. */
(function(){
'use strict';
const Core=window.LuluWorldCore;
const Joystick=window.LuluWorldJoystick, ArtCatalog=window.LuluWorldArtCatalog;
const $=id=>document.getElementById(id);
const canvas=$('world-canvas'),ctx=canvas.getContext('2d',{alpha:false});
const names={meet:{chapter:'MEETING / TOWN',title:'见面 · 南岸小镇'},island:{chapter:'CO-OP / ISLAND',title:'方块小岛'},relic:{chapter:'EXPLORE / RELICS',title:'迷雾摸金'}};
const art={town:null,dungeon:null,rogue:null,characters:null,interior:null,city:null};let chosen='meet',s=null,view={tile:46,ox:0,oy:0},path=[],moving=false,held=null,lastMoveAt=0;
const paths={town:'./assets/kenney-tiny-town.png',dungeon:'./assets/kenney-tiny-dungeon.png',rogue:'./assets/kenney-roguelike-rpg.png',characters:'./assets/kenney-roguelike-characters.png',interior:'./assets/kenney-roguelike-interior.png',city:'./assets/kenney-roguelike-city.png'};
const cachePrefix='lulu_shared_sprites_demo_2026_11_17_';
function announce(text){$('floating').textContent=text}
function loadArt(kind,src){const img=new Image();img.onload=()=>{art[kind]=img;announce('Kenney 素材已读取 · 拖动左侧摇杆移动');renderArtGallery()};img.onerror=()=>{announce('无法加载 '+kind+' 图片，请确认 assets 目录齐全');console.error('Kenney asset missing:',src)};img.src=src}
for(const [kind,src] of Object.entries(paths))loadArt(kind,src);
function loadWorld(kind){let raw=null;try{raw=JSON.parse(localStorage.getItem(cachePrefix+kind)||'null')}catch{}return Core.valid(raw)&&raw.kind===kind?raw:Core.createWorld(kind,813)}
function save(){try{localStorage.setItem(cachePrefix+chosen,JSON.stringify(s))}catch{}}
function start(kind){if(!names[kind])return;chosen=kind;s=loadWorld(kind);path=[];moving=false;clearInterval(held);held=null;if($('art-gallery'))$('art-gallery').hidden=true;if($('asset-toggle'))$('asset-toggle').setAttribute('aria-expanded','false');const immersive=kind==='island'||kind==='relic';if(!immersive){if(document.fullscreenElement)document.exitFullscreen().catch(()=>{});try{screen.orientation?.unlock?.()}catch{}}document.body.classList.toggle('world-immersive',immersive);$('exit-world').hidden=!immersive;$('fullscreen-world').hidden=!immersive;$('world-switch').hidden=!immersive;document.querySelectorAll('[data-world]').forEach(b=>{b.classList.toggle('active',b.dataset.world===kind);b.setAttribute('aria-pressed',b.dataset.world===kind?'true':'false')});$('chapter').textContent=names[kind].chapter;$('world-title').textContent=names[kind].title;announce('左侧摇杆拖动移动 · 地图点击可寻路');$('chat-toggle').hidden=kind!=='meet';$('chat-panel').hidden=true;updateUI();draw()}
function near(){return Core.nearest(s,1)}
function describe(p){if(!p)return '附近没有互动对象';return ({friend:'演示同伴',house:'咖啡店',shop:'书店',tree:'森林树木',ore:'矿石',workbench:'工作台',camp:'篝火',monster:'遗迹守卫',chest:'宝箱',exit:'撤离口',bench:'长椅',well:'水井'})[p.kind]||'互动目标'}
function updateUI(){const target=near();$('stat-turn').textContent=s.kind==='meet'?'探索 '+s.turns:'行动 '+s.turns;$('stat-resource').textContent=s.kind==='meet'?(s.holding?'牵手 · 是':'牵手 · 否'):s.kind==='island'?`木材 ${s.inventory.wood} · 石 ${s.inventory.stone}`:`宝物 ${s.inventory.relic} · HP ${s.hp}`;$('focus-label').textContent=target?describe(target):'自由探索';$('focus-hint').textContent=target?'可点击右边互动按钮':s.kind==='relic'?'寻找宝箱 · 走近守卫':'点击地图移动 · 靠近物品互动';$('act').textContent=target?({'friend':s.holding?'松手':'牵手','tree':'采集','ore':'采矿','workbench':'建造','chest':'开箱','monster':'攻击','exit':'撤离'}[target.kind]||'互动'):'互动';$('act').disabled=!target||s.finished}
function transact(result){if(result.message)announce(result.message);if(result.ok){save();updateUI();draw()}}
function move(dx,dy){if(!s||s.finished)return;const r=Core.walk(s,dx,dy);if(r.ok){save();updateUI()}else{path=[];if(r.message)announce(r.message)}}
function interact(){if(s?.finished){announce('当前试玩已结束，点击重开场景');return}const target=near();if(!target){announce('靠近树、房屋、同伴、宝箱等目标再互动');return}transact(Core.use(s,target.id))}
let resizedW=0,resizedH=0;
function resize(){const box=$('viewport'),dpr=Math.min(window.devicePixelRatio||1,2.5),w=Math.max(100,Math.round(box.clientWidth)),h=Math.max(100,Math.round(box.clientHeight));if(canvas.width!==Math.round(w*dpr)||canvas.height!==Math.round(h*dpr)){canvas.width=Math.round(w*dpr);canvas.height=Math.round(h*dpr);resizedW=w;resizedH=h;}ctx.setTransform(dpr,0,0,dpr,0,0);ctx.imageSmoothingEnabled=false;return {w,h}}
function sprite(image,sx,sy,sw,sh,x,y,dw,dh){const pic=art[image];if(!pic)return false;ctx.drawImage(pic,sx,sy,sw,sh,Math.round(x),Math.round(y),Math.ceil(dw),Math.ceil(dh));return true}
function catalogSprite(group,index,x,y,size=32){
  const entry=ArtCatalog.at(group,index);if(!entry)return false;
  return sprite(entry.sheet,entry.x,entry.y,entry.w,entry.h,x,y,size,size);
}
function detailAt(seed,x,y){let n=(Math.imul((x+31),374761393)+Math.imul((y+17),668265263)+Math.imul(seed,1442695041))|0;n=Math.imul(n^(n>>>13),1274126177);return (n^(n>>>16))>>>0}
function drawTileDetails(tile,tx,ty,x,y,t){
  const n=detailAt(s.seed,tx,ty),pick=n%37;
  if(tile==='grass'||tile==='path'){
    if(pick===0||pick===1||pick===2)catalogSprite('terrain',pick===0?1:4,x+(n%7)*t/22,y+(n%5)*t/25,t*.7);
    else if(pick===3&&s.kind!=='meet')catalogSprite('terrain',3,x+t*.2,y+t*.2,t*.62);
  } else if(tile==='floor'&&(pick===0||pick===1)){
    catalogSprite('terrain',pick===0?2:3,x+t*.1,y+t*.14,t*.75);
  }
}
function drawGear(x,y,t,isHero){
  // A visible kit layer, using the 450-piece modular character atlas rather than CSS swords.
  if(s.kind==='relic')catalogSprite('weapons',isHero?0:4,x+t*.51,y+t*.15,t*.65);
  if(s.kind==='island'&&isHero)catalogSprite('weapons',5,x+t*.42,y+t*.32,t*.55);
}
function renderArtGallery(){
  const box=$('art-gallery-grid');if(!box||!ArtCatalog)return;
  box.replaceChildren();
  const headings={terrain:'地形与路面',actors:'玩家／队友／外观',enemies:'怪物样本',weapons:'武器装备',loot:'珍宝图标样本',objects:'建筑／家具／采集物'};
  for(const [group,label] of Object.entries(headings)){
    const section=document.createElement('section');section.className='art-shelf';
    const title=document.createElement('h3');title.textContent=label;section.append(title);
    const grid=document.createElement('div');grid.className='art-shelf-grid';
    for(const [index,item] of ArtCatalog.items.filter(i=>i.group===group).entries()){
      const tile=document.createElement('div');tile.className='art-sample';
      const sample=document.createElement('canvas');sample.width=48;sample.height=48;sample.setAttribute('role','img');sample.setAttribute('aria-label',item.name);
      const c=sample.getContext('2d');c.imageSmoothingEnabled=false;const img=art[item.sheet];
      if(img)c.drawImage(img,item.x,item.y,item.w,item.h,4,4,40,40);
      const caption=document.createElement('span');caption.textContent=item.name;
      tile.append(sample,caption);grid.append(tile);
    }
    section.append(grid);box.append(section);
  }
  const atlasTitle=document.createElement('section');atlasTitle.className='art-shelf';
  const h=document.createElement('h3');h.textContent='查看完整原始图集（滑动浏览全部图块）';atlasTitle.append(h);
  const album={town:'Tiny Town · 室外自然',dungeon:'Tiny Dungeon · 怪物与战斗',rogue:'Roguelike/RPG · 大型道具与地形',characters:'Roguelike Characters · 人物及装备',interior:'Roguelike Indoors · 家具',city:'Roguelike Modern City · 建筑道路'};
  for(const [key,label] of Object.entries(album)){
    const details=document.createElement('details');details.className='art-sheet';
    const summary=document.createElement('summary');summary.textContent=label;
    const frame=document.createElement('div');frame.className='art-sheet-frame';
    const image=document.createElement('img');image.src=paths[key];image.alt=label+'，完整 Kenney CC0 像素图集';image.loading='lazy';
    frame.append(image);details.append(summary,frame);atlasTitle.append(details);
  }
  box.append(atlasTitle);
}
function tileSprite(tile,x,y,t){const specs={grass:['town',0,0],path:['town',0,32],floor:['dungeon',0,0],stone:['dungeon',0,16],wall:['dungeon',16,0],border:['dungeon',16,0],water:['town',0,0]};const a=specs[tile]||specs.grass;if(tile==='water') {ctx.fillStyle='#427d8e';ctx.fillRect(Math.round(x),Math.round(y),Math.ceil(t),Math.ceil(t));return}if(tile==='wall'||tile==='border'){ctx.fillStyle='#343c43';ctx.fillRect(Math.round(x),Math.round(y),Math.ceil(t),Math.ceil(t))}if(!sprite(a[0],a[1],a[2],16,16,x,y,t,t)){ctx.fillStyle=tile==='wall'?'#596770':tile==='floor'?'#765b54':'#739e6b';ctx.fillRect(x,y,t+1,t+1)}}
function drawProp(p,t,x,y,time){const k=p.kind,at=(sx,sy,sw=16,sh=16,dx=x,dy=y-t*(sh/16-1),dw=t*(sw/16),dh=t*(sh/16),sheet='town')=>sprite(sheet,sx,sy,sw,sh,dx,dy,dw,dh);
if(k==='tree'){at(16,0,16,32);if(detailAt(s.seed,p.x,p.y)%3===0)catalogSprite('terrain',1,x-t*.16,y+t*.65,t*.55);return}
if(k==='house'){at(0,32,64,64,x-t*1.3,y-t*3.1,t*3.5,t*3.5);return}
if(k==='shop'){at(64,32,64,64,x-t*1.25,y-t*3.1,t*3.5,t*3.5);return}
if(k==='chest'){catalogSprite('loot',(p.id==='chest-b'?6:0),x,y,t);at(32,0,16,16,x,y,t,t,'dungeon');return}
if(k==='monster'){if(!catalogSprite('enemies',(p.x+p.y)%6,x,y,t))at(16,80,16,16,x,y,t,t,'dungeon');return}
if(k==='friend'){if(!s.companion.following){at(16,96,16,16,x,y,t,t,'dungeon')}return}
if(k==='exit'){at(32,16,16,16,x,y,t,t,'dungeon');catalogSprite('loot',0,x+t*.25,y+t*.3,t*.55);return}
if(k==='workbench'||k==='bench'){if(!catalogSprite('objects',5,x,y,t))at(48,64,16,16);return}
if(k==='camp'){at(80,64,16,16);catalogSprite('objects',4,x+t*.05,y-t*.3,t*.85);return}
if(k==='ore'){if(!catalogSprite('objects',1,x,y,t))at(32,16,16,16,x,y,t,t,'dungeon');return}
if(k==='well'){at(64,80,32,32,x-t*.5,y-t*1.4,t*2,t*2);return}
}
function drawPerson(x,y,kind,t,time){const dy=window.matchMedia&&window.matchMedia('(prefers-reduced-motion: reduce)').matches?0:Math.sin(time/190)*t*.027;const src=kind==='hero'?[32,96]:[16,96];sprite('dungeon',src[0],src[1],16,16,x,y+dy,t,t);drawGear(x,y,t,kind==='hero');if(kind==='hero'){ctx.strokeStyle='#f8eabb';ctx.lineWidth=1.8;ctx.strokeRect(Math.round(x),Math.round(y),Math.round(t),Math.round(t))}}
function draw(time=0){if(!s)return;const {w,h}=resize(),t=Math.max(25,Math.min(54,Math.floor(w/(w>620?15:9.5))));view.tile=t;view.ox=Math.floor(w/2-(s.player.x+.5)*t);view.oy=Math.floor(h/2-(s.player.y+.5)*t);ctx.fillStyle=s.kind==='relic'?'#212830':'#2d6354';ctx.fillRect(0,0,w,h);
  const left=Math.max(0,Math.floor(-view.ox/t)-1),right=Math.min(s.width-1,Math.ceil((w-view.ox)/t)+1),top=Math.max(0,Math.floor(-view.oy/t)-1),bottom=Math.min(s.height-1,Math.ceil((h-view.oy)/t)+1);
  for(let y=top;y<=bottom;y++)for(let x=left;x<=right;x++){const tile=s.terrain[y*s.width+x];tileSprite(tile,view.ox+x*t,view.oy+y*t,t);drawTileDetails(tile,x,y,view.ox+x*t,view.oy+y*t,t)}
  const paint=[];for(const p of s.props)if(!p.used&&p.x>=left-4&&p.x<=right+4&&p.y>=top-4&&p.y<=bottom+4)paint.push({depth:p.y+0.2,draw(){drawProp(p,t,view.ox+p.x*t,view.oy+p.y*t,time)}});
  paint.push({depth:s.player.y+.4,draw(){drawPerson(view.ox+s.player.x*t,view.oy+s.player.y*t,'hero',t,time)}});
  if(s.companion.following)paint.push({depth:s.companion.y+.3,draw(){drawPerson(view.ox+s.companion.x*t,view.oy+s.companion.y*t,'friend',t,time)}});
  paint.sort((a,b)=>a.depth-b.depth).forEach(x=>x.draw());
  // All decorative camera lighting is above the real, licensed image atlas, not a replacement for sprites.
  const glow=ctx.createRadialGradient(w*.54,h*.35,w*.05,w*.52,h*.54,w*.82);glow.addColorStop(0,'#ffffff00');glow.addColorStop(1,s.kind==='relic'?'#040917b5':'#061b205a');ctx.fillStyle=glow;ctx.fillRect(0,0,w,h);
  if(!art.town||!art.dungeon){ctx.fillStyle='#0d241caa';ctx.fillRect(6,6,w-12,31);ctx.fillStyle='#fff';ctx.font='12px sans-serif';ctx.fillText('正在读取 Kenney 贴图；若持续显示请检查 assets',13,27)}
}
function tick(time){if(s&&!s.finished&&path.length&&time-lastMoveAt>120){const next=path.shift(),dx=Math.sign(next.x-s.player.x),dy=Math.sign(next.y-s.player.y);const r=Core.walk(s,dx,dy);if(!r.ok){path=[];announce('路线被挡住了')}else {save();updateUI()}lastMoveAt=time}draw(time);requestAnimationFrame(tick)}
function clickMap(e){if(!s)return;const box=canvas.getBoundingClientRect(),rotated=document.body.classList.contains('world-immersive')&&matchMedia('(orientation: portrait)').matches;const localX=rotated?e.clientY-box.top:e.clientX-box.left,localY=rotated?box.right-e.clientX:e.clientY-box.top;const x=Math.floor((localX-view.ox)/view.tile),y=Math.floor((localY-view.oy)/view.tile);const p=s.props.find(p=>!p.used&&p.x===x&&p.y===y);
  if(p&&Math.abs(p.x-s.player.x)+Math.abs(p.y-s.player.y)<=1){transact(Core.use(s,p.id));return}
  const goals=p?[[p.x-1,p.y],[p.x+1,p.y],[p.x,p.y-1],[p.x,p.y+1]]:[[x,y]];
  const routes=goals.map(([gx,gy])=>Core.route(s,gx,gy)).filter(r=>r.length>0).sort((a,b)=>a.length-b.length);
  if(routes.length){path=routes[0];announce(p?`正在走近${describe(p)} · 到达后再互动`:'正在前往你点选的位置')}else announce('无法走到这里，请选择可行走的地面')
}
canvas.addEventListener('pointerup',clickMap);
window.addEventListener('keydown',e=>{if(e.target instanceof HTMLInputElement||e.target instanceof HTMLTextAreaElement)return;const moves={ArrowUp:[0,-1],w:[0,-1],ArrowDown:[0,1],s:[0,1],ArrowLeft:[-1,0],a:[-1,0],ArrowRight:[1,0],d:[1,0]};if(moves[e.key]&&!e.repeat){e.preventDefault();path=[];move(...moves[e.key])}else if(e.key===' '){e.preventDefault();interact()}});
// Native-style circular joystick: press anywhere, drag continuously, move in the dominant axis.
// Center tap triggers interact. Pointer capture keeps a drag alive even outside the ring.
const joystick=$('world-joystick'),knob=$('joystick-knob');let pointerId=null,activeDir=null,centerTap=false;
function repeatDelay(){return chosen==='relic'?520:195}
function applyJoystick(x,y,initial=false){
  const bounds=joystick.getBoundingClientRect();
  const ox=x-bounds.left,oy=y-bounds.top,dir=Joystick.direction(ox,oy,bounds.width,bounds.height);
  const [kx,ky]=Joystick.knobOffset(ox,oy,bounds.width,bounds.height);
  knob.style.transform=`translate(${kx.toFixed(1)}px,${ky.toFixed(1)}px)`;
  if(dir)centerTap=false;
  if(!dir){activeDir=null;joystick.classList.remove('is-moving');return}
  if(!activeDir||dir[0]!==activeDir[0]||dir[1]!==activeDir[1]){
    activeDir=dir;joystick.classList.add('is-moving');path=[];move(dir[0],dir[1]);
  }
}
function releaseJoystick(e){if(pointerId===null||(e&&e.pointerId!==pointerId))return;
  const tap=centerTap&&!activeDir;clearInterval(held);held=null;pointerId=null;activeDir=null;centerTap=false;
  knob.style.transform='translate(0px,0px)';joystick.classList.remove('is-moving');
  try{joystick.releasePointerCapture(e.pointerId)}catch{}
  if(tap)interact();
}
joystick.addEventListener('pointerdown',e=>{
  if(pointerId!==null)return;e.preventDefault();pointerId=e.pointerId;centerTap=true;
  joystick.setPointerCapture(e.pointerId);path=[];applyJoystick(e.clientX,e.clientY,true);
  clearInterval(held);held=setInterval(()=>{if(pointerId!==null&&activeDir)move(activeDir[0],activeDir[1])},repeatDelay());
});
joystick.addEventListener('pointermove',e=>{if(pointerId===e.pointerId){e.preventDefault();applyJoystick(e.clientX,e.clientY)}});
for(const evt of ['pointerup','pointercancel','lostpointercapture'])joystick.addEventListener(evt,releaseJoystick);
joystick.addEventListener('keydown',e=>{const keys={ArrowUp:[0,-1],ArrowDown:[0,1],ArrowLeft:[-1,0],ArrowRight:[1,0]};if(keys[e.key]){e.preventDefault();e.stopPropagation();move(...keys[e.key])}else if(e.key==='Enter'||e.key===' '){e.preventDefault();e.stopPropagation();interact()}});
function closeGallery(){const p=$('art-gallery');p.hidden=true;$('asset-toggle').setAttribute('aria-expanded','false');$('asset-toggle').textContent='素材图鉴'}
$('asset-toggle').addEventListener('click',()=>{const p=$('art-gallery');p.hidden=!p.hidden;$('asset-toggle').setAttribute('aria-expanded',String(!p.hidden));$('asset-toggle').textContent=p.hidden?'素材图鉴':'关闭图鉴';if(!p.hidden)renderArtGallery()});
$('close-art').addEventListener('click',closeGallery);

document.querySelectorAll('[data-world]').forEach(b=>b.addEventListener('click',()=>start(b.dataset.world)));
$('exit-world').addEventListener('click',()=>{if(document.fullscreenElement)document.exitFullscreen().catch(()=>{});try{screen.orientation?.unlock?.()}catch{}start('meet')});
$('fullscreen-world').addEventListener('click',async()=>{try{if(!document.fullscreenElement&&document.querySelector('.game').requestFullscreen)await document.querySelector('.game').requestFullscreen();if(screen.orientation?.lock)await screen.orientation.lock('landscape')}catch{}draw()});
document.addEventListener('fullscreenchange',draw);
$('chat-toggle').addEventListener('click',()=>{$('chat-panel').hidden=!$('chat-panel').hidden;$('chat-toggle').textContent=$('chat-panel').hidden?'聊天':'收起聊天';if(!$('chat-panel').hidden)$('chat-text').focus()});
$('chat-form').addEventListener('submit',e=>{e.preventDefault();const input=$('chat-text'),msg=input.value.trim();if(!msg)return;const line=document.createElement('div');line.textContent='你：'+msg;$('chat-lines').append(line);$('chat-lines').scrollTop=$('chat-lines').scrollHeight;input.value=''});
$('act').addEventListener('click',interact);$('reset').addEventListener('click',()=>{if(!confirm('清空这个场景的独立试玩进度并重开？正式 App 不受影响。'))return;try{localStorage.removeItem(cachePrefix+chosen)}catch{}start(chosen)});
window.addEventListener('resize',()=>draw());
start(new URLSearchParams(location.search).get('world')||'meet');requestAnimationFrame(tick);
})();
