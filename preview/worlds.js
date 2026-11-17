/* One renderer, camera, touch controller and image atlas across three DIFFERENT scenes.
 * Android state/AI/official actions are deliberately not simulated as authoritative facts. */
(function(){
'use strict';
const Core=window.LuluWorldCore;
const $=id=>document.getElementById(id);
const canvas=$('world-canvas'),ctx=canvas.getContext('2d',{alpha:false});
const names={meet:{chapter:'MEETING / TOWN',title:'见面 · 南岸小镇'},island:{chapter:'CO-OP / ISLAND',title:'方块小岛'},relic:{chapter:'EXPLORE / RELICS',title:'迷雾摸金'}};
const art={town:null,dungeon:null};let chosen='meet',s=null,view={tile:46,ox:0,oy:0},path=[],moving=false,held=null,lastMoveAt=0;
const paths={town:'./assets/kenney-tiny-town.png',dungeon:'./assets/kenney-tiny-dungeon.png'};
const cachePrefix='lulu_shared_sprites_demo_2026_11_17_';
function announce(text){$('floating').textContent=text}
function loadArt(kind,src){const img=new Image();img.onload=()=>{art[kind]=img;announce('真实 Kenney 像素素材已加载 · 点击地图可走路')};img.onerror=()=>{announce('无法加载 '+kind+' 图片，请确认 assets 目录齐全');console.error('Kenney asset missing:',src)};img.src=src}
for(const [kind,src] of Object.entries(paths))loadArt(kind,src);
function loadWorld(kind){let raw=null;try{raw=JSON.parse(localStorage.getItem(cachePrefix+kind)||'null')}catch{}return Core.valid(raw)&&raw.kind===kind?raw:Core.createWorld(kind,813)}
function save(){try{localStorage.setItem(cachePrefix+chosen,JSON.stringify(s))}catch{}}
function start(kind){if(!names[kind])return;chosen=kind;s=loadWorld(kind);path=[];moving=false;const immersive=kind==='island'||kind==='relic';if(!immersive){if(document.fullscreenElement)document.exitFullscreen().catch(()=>{});try{screen.orientation?.unlock?.()}catch{}}document.body.classList.toggle('world-immersive',immersive);$('exit-world').hidden=!immersive;$('fullscreen-world').hidden=!immersive;$('world-switch').hidden=!immersive;document.querySelectorAll('[data-world]').forEach(b=>{b.classList.toggle('active',b.dataset.world===kind);b.setAttribute('aria-pressed',b.dataset.world===kind?'true':'false')});$('chapter').textContent=names[kind].chapter;$('world-title').textContent=names[kind].title;announce('点击地面自动走近目标 · 方向键自由走路');$('chat-toggle').hidden=kind!=='meet';$('chat-panel').hidden=true;updateUI();draw()}
function near(){return Core.nearest(s,1)}
function describe(p){if(!p)return '附近没有互动对象';return ({friend:'演示同伴',house:'咖啡店',shop:'书店',tree:'森林树木',ore:'矿石',workbench:'工作台',camp:'篝火',monster:'遗迹守卫',chest:'宝箱',exit:'撤离口',bench:'长椅',well:'水井'})[p.kind]||'互动目标'}
function updateUI(){const target=near();$('stat-turn').textContent=s.kind==='meet'?'探索 '+s.turns:'行动 '+s.turns;$('stat-resource').textContent=s.kind==='meet'?(s.holding?'牵手 · 是':'牵手 · 否'):s.kind==='island'?`木材 ${s.inventory.wood} · 石 ${s.inventory.stone}`:`宝物 ${s.inventory.relic} · HP ${s.hp}`;$('focus-label').textContent=target?describe(target):'自由探索';$('focus-hint').textContent=target?'可点击右边互动按钮':s.kind==='relic'?'寻找宝箱 · 走近守卫':'点击地图移动 · 靠近物品互动';$('act').textContent=target?({'friend':s.holding?'松手':'牵手','tree':'采集','ore':'采矿','workbench':'建造','chest':'开箱','monster':'攻击','exit':'撤离'}[target.kind]||'互动'):'互动';$('act').disabled=!target||s.finished}
function transact(result){if(result.message)announce(result.message);if(result.ok){save();updateUI();draw()}}
function move(dx,dy){if(!s||s.finished)return;const r=Core.walk(s,dx,dy);if(r.ok){save();updateUI()}else{path=[];if(r.message)announce(r.message)}}
function interact(){if(s?.finished){announce('当前试玩已结束，点击重开场景');return}const target=near();if(!target){announce('靠近树、房屋、同伴、宝箱等目标再互动');return}transact(Core.use(s,target.id))}
let resizedW=0,resizedH=0;
function resize(){const box=$('viewport'),dpr=Math.min(window.devicePixelRatio||1,2.5),w=Math.max(100,Math.round(box.clientWidth)),h=Math.max(100,Math.round(box.clientHeight));if(canvas.width!==Math.round(w*dpr)||canvas.height!==Math.round(h*dpr)){canvas.width=Math.round(w*dpr);canvas.height=Math.round(h*dpr);resizedW=w;resizedH=h;}ctx.setTransform(dpr,0,0,dpr,0,0);ctx.imageSmoothingEnabled=false;return {w,h}}
function sprite(image,sx,sy,sw,sh,x,y,dw,dh){const pic=art[image];if(!pic)return false;ctx.drawImage(pic,sx,sy,sw,sh,Math.round(x),Math.round(y),Math.ceil(dw),Math.ceil(dh));return true}
function tileSprite(tile,x,y,t){const specs={grass:['town',0,0],path:['town',0,32],floor:['dungeon',0,0],stone:['dungeon',0,16],wall:['dungeon',16,0],border:['dungeon',16,0],water:['town',0,0]};const a=specs[tile]||specs.grass;if(tile==='water') {ctx.fillStyle='#427d8e';ctx.fillRect(Math.round(x),Math.round(y),Math.ceil(t),Math.ceil(t));return}if(tile==='wall'||tile==='border'){ctx.fillStyle='#343c43';ctx.fillRect(Math.round(x),Math.round(y),Math.ceil(t),Math.ceil(t))}if(!sprite(a[0],a[1],a[2],16,16,x,y,t,t)){ctx.fillStyle=tile==='wall'?'#596770':tile==='floor'?'#765b54':'#739e6b';ctx.fillRect(x,y,t+1,t+1)}}
function drawProp(p,t,x,y,time){const k=p.kind,at=(sx,sy,sw=16,sh=16,dx=x,dy=y-t*(sh/16-1),dw=t*(sw/16),dh=t*(sh/16),sheet='town')=>sprite(sheet,sx,sy,sw,sh,dx,dy,dw,dh);
if(k==='tree'){at(16,0,16,32);return}
if(k==='house'){at(0,32,64,64,x-t*1.3,y-t*3.1,t*3.5,t*3.5);return}
if(k==='shop'){at(64,32,64,64,x-t*1.25,y-t*3.1,t*3.5,t*3.5);return}
if(k==='chest'){at(32,0,16,16,x,y,t,t,'dungeon');return}
if(k==='monster'){at(16,80,16,16,x,y,t,t,'dungeon');return}
if(k==='friend'){if(!s.companion.following){at(16,96,16,16,x,y,t,t,'dungeon')}return}
if(k==='exit'){at(32,16,16,16,x,y,t,t,'dungeon');return}
if(k==='workbench'||k==='bench'){at(48,64,16,16);return}
if(k==='camp'){at(80,64,16,16);return}
if(k==='ore'){at(32,16,16,16,x,y,t,t,'dungeon');return}
if(k==='well'){at(64,80,32,32,x-t*.5,y-t*1.4,t*2,t*2);return}
}
function drawPerson(x,y,kind,t,time){const dy=window.matchMedia&&window.matchMedia('(prefers-reduced-motion: reduce)').matches?0:Math.sin(time/190)*t*.027;const src=kind==='hero'?[32,96]:[16,96];sprite('dungeon',src[0],src[1],16,16,x,y+dy,t,t);if(kind==='hero'){ctx.strokeStyle='#f8eabb';ctx.lineWidth=1.8;ctx.strokeRect(Math.round(x),Math.round(y),Math.round(t),Math.round(t))}}
function draw(time=0){if(!s)return;const {w,h}=resize(),t=Math.max(25,Math.min(54,Math.floor(w/(w>620?15:9.5))));view.tile=t;view.ox=Math.floor(w/2-(s.player.x+.5)*t);view.oy=Math.floor(h/2-(s.player.y+.5)*t);ctx.fillStyle=s.kind==='relic'?'#212830':'#2d6354';ctx.fillRect(0,0,w,h);
  const left=Math.max(0,Math.floor(-view.ox/t)-1),right=Math.min(s.width-1,Math.ceil((w-view.ox)/t)+1),top=Math.max(0,Math.floor(-view.oy/t)-1),bottom=Math.min(s.height-1,Math.ceil((h-view.oy)/t)+1);
  for(let y=top;y<=bottom;y++)for(let x=left;x<=right;x++)tileSprite(s.terrain[y*s.width+x],view.ox+x*t,view.oy+y*t,t);
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
document.querySelectorAll('[data-step]').forEach(b=>{const step=b.dataset.step.split(',').map(Number);b.addEventListener('pointerdown',e=>{e.preventDefault();path=[];move(...step);clearInterval(held);held=setInterval(()=>move(...step),180)});for(const event of ['pointerup','pointercancel','pointerleave'])b.addEventListener(event,()=>{clearInterval(held);held=null})});
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
