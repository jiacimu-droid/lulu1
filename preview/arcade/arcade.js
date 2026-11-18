import { islandMap } from './island-map.js';
import { relicMap } from './relic-map.js';

const $ = id => document.getElementById(id);
const launch = $('launch'), options = $('options'), game = $('game'), form = $('setup');
const islandCanvas = $('map-3d'), relicCanvas = $('map-2d'), pad = $('pad');
const rotateMedia = matchMedia('(orientation:portrait) and (max-width:760px)');
const names = { island:'方块小岛', relic:'迷雾摸金' };
const counters = { EASY:'轻松', NORMAL:'标准', HARD:'险境' };
const difficultyDetail = {EASY:'守卫更少，生命与伤害较低。',NORMAL:'常规守卫数量、生命与伤害。',HARD:'首层守卫更多，生命与伤害提高。'};
const gamePreferences={island:{partner:false},relic:{partner:false}};
const terminal = new Set(['SUCCESS','FAILURE','SETTLEMENT']);
let kind = '', state, selected = null, facing = [0,1], recipe = '';
let renderer, islandRenderer, angle = 0, overview = false, busy = false, epoch = 0;
let heldTimer, directionHeld = null, pressId = null, initialCentre = false;

async function request(verb, payload = {}) {
  const response = await fetch('./rules', {
    method:'POST', headers:{'Content-Type':'application/json'},
    body:JSON.stringify({kind,verb,session:state?.session,...payload})
  });
  const value = await response.json();
  if (!response.ok || value.error) throw new Error(value.error || '试玩引擎未能回应');
  return value;
}
function storage(mode, key, value) {
  try {
    const id = 'lulu-arcade-session:'+mode;
    if (key === 'get') return sessionStorage.getItem(id);
    if (key === 'delete') sessionStorage.removeItem(id);
    else sessionStorage.setItem(id,value);
  } catch { return null; }
}
function setBusy(value, hint = '') {
  busy = value;
  $('start').disabled = value;
  $('resume').disabled = value;
  if (launch.open) $('launch-status').textContent = hint;
}
function setup(mode) {
  kind = mode;
  $('launch-kicker').textContent = mode === 'island' ? '3D 斜俯视 · 持久家园' : '2D 俯视 · 迷雾遗迹';
  $('launch-title').textContent = names[mode];
  $('launch-copy').textContent = mode === 'island'
    ? '从徒手采集开始。在林地搭工作台，再用石剑守夜。'
    : '挑好武器，从遗迹摸到一件宝物，再守住撤离门。';
  $('relic-config').hidden = mode !== 'relic';
  $('with-partner').checked=gamePreferences[mode].partner;
  $('start').textContent = mode === 'island' ? '新建岛屿' : '开始搜宝';
  $('resume').hidden = !storage(mode,'get');
  $('launch-status').textContent = '';
  updateDifficulty();
  launch.showModal();
}
async function enter(resume) {
  if (busy) return;
  const activeEpoch = ++epoch;
  setBusy(true,'正在启动 Kotlin 游戏引擎，首次需要编译。');
  try {
    if (kind==='island') islandRenderer ??= islandMap(islandCanvas);
    const data = new FormData(form);
    if(!resume)gamePreferences[kind].partner=$('with-partner').checked;
    const next = resume
      ? await request('view',{session:storage(kind,'get'), dx:0, dy:1})
      : await request('start', {difficulty:data.get('difficulty'),weapon:data.get('weapon'),profession:data.get('profession'),
          partner:$('with-partner').checked,seed:Math.floor(Math.random()*100000)+1,dx:0,dy:1});
    if (activeEpoch !== epoch) return;
    state=next;
    storage(kind,'set',state.session);
    selected=null; facing=[0,1]; recipe=''; angle=0; overview=false;
    game.dataset.kind=kind;
    islandCanvas.hidden=kind!=='island'; relicCanvas.hidden=kind!=='relic';
    $('orbit').hidden=kind!=='island'; $('overview').hidden=kind!=='island';
    $('overview').textContent='全岛';
    renderer=kind==='island'?islandRenderer:relicMap(relicCanvas);
    launch.close();
    $('hall').hidden=true;
    game.hidden=false;
    $('leave').focus({preventScroll:true});
    render();
  } catch(error) {
    if (activeEpoch!==epoch) return;
    const expired = resume && error.message.includes('试玩已结束');
    if (expired) {
      storage(kind,'delete');
      $('resume').hidden=true;
    }
    $('launch-status').textContent=expired?'服务进程没有这局了，可以重新开局。':error.message;
  } finally {
    if (activeEpoch===epoch) {
      busy=false;
      $('start').disabled=false;
      $('resume').disabled=false;
      if (!game.hidden) render();
    }
  }
}
function leave() {
  ++epoch;
  busy=false;
  $('start').disabled=false;
  $('resume').disabled=false;
  stopHold();
  if(options.open)options.close();
  game.hidden=true;
  $('hall').hidden=false;
  document.querySelector(`[data-launch="${kind}"]`)?.focus({preventScroll:true});
  state=null;
}
function snapshotInput() {
  return {dx:facing[0],dy:facing[1],build:recipe,
    ...(selected?{x:selected[0],y:selected[1]}:{})};
}
async function update(verb, payload, selectionAfter) {
  if (busy || !state) return;
  const before=state, token=epoch;
  busy=true;
  try {
    const next=await request(verb,{...snapshotInput(),...payload});
    if(token!==epoch)return;
    state=next;
    if (kind==='island' && verb==='act' && recipe===payload.action && next.world.turns>before.world.turns)
      recipe='';
    if(selectionAfter!==undefined) selected=selectionAfter;
    render();
    if (next.receipt) showReceipt(next.receipt);
    if (kind==='relic' && terminal.has(next.phase) && next.phase!==before.phase) showResult();
  } catch(error) {
    if(token===epoch)$('feedback').textContent=error.message;
  } finally {
    if(token===epoch)busy=false;
  }
}
function move(dx,dy) {
  if(!state || busy || options.open || (kind==='relic' && terminal.has(state.phase)))return;
  if(kind==='island'){
    facing=renderer.direction(dx,dy,angle);
    if(overview){overview=false;$('overview').textContent='全岛';}
  } else facing=[dx,dy];
  update('act',{action:'move',dx:facing[0],dy:facing[1]},null);
}
function perform(item) {
  if (!item || !item.enabled || busy) return;
  if(item.action==='choose_build'){
    recipe=item.id;
    const w=state.world;
    const x=w.x+facing[0],y=w.y+facing[1];
    selected=[Math.min(w.width-1,Math.max(0,x)),Math.min(w.height-1,Math.max(0,y))];
    options.close();
    update('view');
    return;
  }
  if(item.action==='use_workbench'){showTools(true);return;}
  if(item.action==='move'){
    const {x,y}=state.hero;
    facing=[item.x-x,item.y-y];
    update('act',{action:'move',dx:facing[0],dy:facing[1]},null);
    return;
  }
  update('act',{action:item.action,id:item.id,x:item.x,y:item.y});
}
function draw(){
  if(state && !game.hidden) renderer.paint(state,selected,angle,overview);
}
function render() {
  if(!state) return;
  const island=kind==='island';
  const s=state,w=s.world;
  const complete=!island&&terminal.has(s.phase);
  const active=s.actions.find(a=>a.section==='main');
  const fight=s.actions.find(a=>a.section==='combat'&&a.action==='attack');
  $('map-name').textContent=island?'方块小岛 · 岸林':`迷雾摸金 · ${counters[s.difficulty]||'标准'}`;
  $('objective').textContent=complete ? (s.phase==='SUCCESS'?'撤离成功':'撤离失败') : s.objective;
  $('stats').textContent=island
    ? `生命 ${w.hp}/12　饱食 ${s.hunger}/10　木 ${w.wood}　石 ${w.stone}`
    : `生命 ${s.hero.hp}/10　负重 ${s.load}/${s.capacity}　宝物 ${s.carried}　估值 ${s.value}`;
  const message=s.notice || '';
  $('feedback').textContent=/^(走到了 \(.*|点击格子选中目标.*|前进一步)$/.test(message)?'':message;
  const selectedField=selected && island ? `选中 ${selected[0]+1}, ${selected[1]+1} · ` : '';
  $('focus').textContent=complete?'点击查看本次实际带出的宝物。'
    : recipe?(active?.hint||'点选要放置的地块。')
    : active?.hint || (island?selectedField+'点选地图目标，靠近后互动。':'移动探索迷雾，或点选已发现的目标。');
  $('primary').textContent=complete?'查看结算':active?(active.action==='open'?'撬开宝箱':active.label):'互动';
  $('primary').disabled=!complete&&!active?.enabled;
  $('primary').onclick=complete?showResult:()=>perform(active);
  $('fight').hidden=!fight||complete;
  $('fight').onclick=()=>perform(fight);
  $('tools').hidden=complete;
  $('tools').textContent=island?'建造 / 行囊':'战术';
  $('partner').hidden=complete || !(island?w.friends.length:s.allies.length);
  draw();
}
function dialogPage(kicker,title,desc) {
  stopHold();
  $('options-kicker').textContent=kicker;
  $('options-title').textContent=title;
  $('options-desc').textContent=desc;
  const list=$('option-list');
  list.replaceChildren();
  if(!options.open)options.showModal();
  return list;
}
function option(list,label,hint,enabled,run){
  const b=document.createElement('button');
  b.type='button';b.textContent=label;b.disabled=!enabled;
  if(hint){
    const sub=document.createElement('small');sub.textContent=hint;b.append(sub);
  }
  b.onclick=run;
  list.append(b);
}
function showTools(workbench=false){
  const island=kind==='island';
  const list=dialogPage(island?'方块小岛':'迷雾摸金',island?(workbench?'工作台':'建造与行囊'):'战术与装备',
    island?`木 ${state.world.wood} · 石 ${state.world.stone} · 野果 ${state.fruit} · 晶石 ${state.crystals}`
      :`药剂 ${state.hero.kits} 瓶 · 弩箭 ${state.bolts} 支`);
  if(recipe)option(list,'退出建造模式','回到普通互动',true,()=>{
    recipe=''; options.close(); update('view');
  });
  const sections=island?(workbench?['bench']:['build','bench']):['combat','gear'];
  for(const action of state.actions.filter(a=>sections.includes(a.section)))
    option(list,action.label,action.hint,action.enabled,()=>{
      if(action.action!=='choose_build')options.close();
      perform(action);
    });
}
function showPartner(){
  const list=dialogPage('本地协作','试玩队友',
    state.actorEvent || '你可以手动指挥队友。菜单只显示当前规则允许的有效动作。');
  const actions=state.actions.filter(a=>a.section==='partner');
  if(!actions.length)option(list,'当前没有可执行的队友动作','先推进一个游戏回合。',false);
  for(const action of actions)option(list,action.label,'',action.enabled,()=>{
    options.close();perform(action);
  });
}
function lootRow(list,text){
  const row=document.createElement('div');row.className='result-loot';row.textContent=text;list.append(row);
}
function showReceipt(receipt){
  const list=dialogPage('宝箱回执',receipt.name,receipt.inBag?'已放进背包。成功撤离后才算带出。':'背包容量不足，宝物留在原地。');
  lootRow(list,`实物估值 ${receipt.value} · 重量 ${receipt.tier==='PRECIOUS'?3:receipt.tier==='RARE'?2:1}`);
  option(list,'继续探索','',true,()=>options.close());
}
function showResult(){
  const success=state.phase==='SUCCESS',items=success?state.secured:state.lost;
  const list=dialogPage('探险结算',success?'成功撤离':'未能撤离',
    success?`带回 ${items.length} 件 · 总估值 ${items.reduce((sum,item)=>sum+item.value,0)}`
      :`本次损失 ${items.length} 件宝物。`);
  for(const item of items)lootRow(list,`${item.name} · ${item.value}`);
  option(list,'返回游戏馆','',true,leave);
  option(list,'再来一局','可以保留上一次的难度与装备',true,()=>{
    leave();
    setup('relic');
  });
}
function pointerPoint(e,element) {
  return window.LuluWorldJoystick.pointerLocal(e.clientX,e.clientY,element.getBoundingClientRect(),rotateMedia.matches);
}
function hitMap(e){
  if(!state||busy || (kind==='relic'&&terminal.has(state.phase)))return;
  const point=renderer.pick(...pointerPoint(e,e.currentTarget));
  if(!point || (kind==='relic'&&state.tiles[point[1]*state.w+point[0]]<0))return;
  selected=point;
  draw();
  update('view');
}
function stopHold(){
  clearInterval(heldTimer);heldTimer=null;
  directionHeld=null;
  for(const b of pad.querySelectorAll('[data-held]'))delete b.dataset.held;
}
function switchDirection(next){
  if(String(directionHeld)===String(next))return;
  stopHold();
  if(!next)return;
  directionHeld=next;
  const hit=pad.querySelector(`[data-vector="${next.join(',')}"]`);
  if(hit)hit.dataset.held='';
  move(...next);
  heldTimer=setInterval(()=>move(...next),kind==='island'?215:405);
}
function padDirection(e){
  return window.LuluWorldJoystick.direction(...pointerPoint(e,pad),pad.clientWidth,pad.clientHeight);
}

document.querySelectorAll('[data-launch]').forEach(b=>b.addEventListener('click',()=>setup(b.dataset.launch)));
document.querySelectorAll('[data-dismiss]').forEach(b=>b.addEventListener('click',()=>b.closest('dialog').close()));
function updateDifficulty(){
  $('difficulty-detail').textContent=difficultyDetail[new FormData(form).get('difficulty')];
}
form.addEventListener('change',updateDifficulty);
form.addEventListener('submit',e=>{e.preventDefault();enter(false);});
$('resume').onclick=()=>enter(true);
launch.addEventListener('close',()=>{
  if(state||!busy)return;
  ++epoch;setBusy(false);
});
$('leave').onclick=leave;
$('tools').onclick=()=>showTools();
$('partner').onclick=showPartner;
$('orbit').onclick=()=>{angle=(angle+1)%4;draw();};
$('overview').onclick=()=>{overview=!overview;$('overview').textContent=overview?'跟随':'全岛';draw();};
for(const canvas of [islandCanvas,relicCanvas])canvas.addEventListener('click',hitMap);
pad.addEventListener('pointerdown',e=>{
  if(pressId!==null)return;
  pressId=e.pointerId;
  pad.setPointerCapture(e.pointerId);
  const next=padDirection(e);
  initialCentre=!next;
  switchDirection(next);
});
pad.addEventListener('pointermove',e=>{
  if(pressId!==e.pointerId)return;
  const next=padDirection(e);
  if(next)initialCentre=false;
  switchDirection(next);
});
function releasePad(e){
  if(pressId!==e.pointerId)return;
  const tap=initialCentre && e.type==='pointerup' && !padDirection(e);
  pressId=null; initialCentre=false; stopHold();
  if(tap)$('primary').click();
}
for(const type of ['pointerup','pointercancel','lostpointercapture'])pad.addEventListener(type,releasePad);
pad.addEventListener('click',e=>{
  const b=e.target.closest('[data-vector]');
  if(!b||e.detail!==0)return;
  const vector=b.dataset.vector.split(',').map(Number);
  if(vector.every(v=>v===0))$('primary').click();
  else move(...vector);
});
const keyDirs={ArrowUp:[0,-1],ArrowLeft:[-1,0],ArrowDown:[0,1],ArrowRight:[1,0],w:[0,-1],a:[-1,0],s:[0,1],d:[1,0]};
window.addEventListener('keydown',e=>{
  if(game.hidden||options.open||e.ctrlKey||e.altKey||e.metaKey)return;
  if(e.key in keyDirs && !e.repeat){e.preventDefault();switchDirection(keyDirs[e.key]);}
});
window.addEventListener('keyup',e=>{if(String(keyDirs[e.key])===String(directionHeld))stopHold();});
window.addEventListener('blur',stopHold);
window.addEventListener('resize',draw);
