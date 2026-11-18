import { islandMap } from './island-map.js';
import { relicMap } from './relic-map.js';
import { chestShow, lootAsset } from './chest-show.js';

const $ = id => document.getElementById(id);
const launch = $('launch'), options = $('options'), game = $('game'), form = $('setup');
const islandCanvas = $('map-3d'), relicCanvas = $('map-2d'), pad = $('pad');
const browserMode = document.documentElement.dataset.runtime === 'browser';
const nativeBridge = window.LuluArcade;
const nativeMode = Boolean(nativeBridge);
const nativePending = new Map();
let requestId=0, nativeSession=null, inviting=false;
if(nativeMode){
  document.documentElement.dataset.runtime='android';
  nativeBridge.onmessage=({data})=>{
    const response=JSON.parse(data);
    const pending=nativePending.get(response.requestId);
    if(!pending)return;
    nativePending.delete(response.requestId);
    if(response.error)pending.reject(new Error(response.error));
    else pending.resolve(response);
  };
}
let rulesModule;
const browserSessions = new Set();
const landscape = matchMedia('(orientation:landscape)');
const crewPicker=$('crew-picker');
const equipmentDialog=$('equipment-dialog');
let crewProfiles=[
  { name:'苔羽', title:'林地采集', desc:'能寻找近处资源，并能动手采集或参与建造。'},
  { name:'砾灯', title:'营地护卫', desc:'可下达近身护卫和应战指令。'},
  { name:'渡鸦', title:'遗迹探路', desc:'可前往指定区域，或者围堵附近威胁。'}
].map(role=>({...role,id:role.name}));
const roleName=id=>crewProfiles.find(role=>role.id===id)?.name||'队友';
let crew=[], chestSession=null, ceremony;
let extraction=null, gather=null;
let paused=false;

const names = { island:'方块小岛', relic:'迷雾摸金' };
const counters = { EASY:'轻松', NORMAL:'标准', HARD:'险境' };
const difficultyDetail = {EASY:'守卫更少，生命与伤害较低。',NORMAL:'常规守卫数量、生命与伤害。',HARD:'首层守卫更多，生命与伤害提高。'};
const gamePreferences={island:[],relic:[]};
const terminal = new Set(['SUCCESS','FAILURE','SETTLEMENT']);
let kind = '', state, selected = null, facing = [0,1], recipe = '';
let renderer, islandRenderer, overview = false, busy = false, epoch = 0;
let camera = { yaw: .48, pitch: -.21 }, lookDrag, redraw;
let heldTimer, directionHeld = null, pressId = null, initialCentre = false;

async function request(verb, payload = {}) {
  const input={kind,verb,session:state?.session,...payload};
  if(nativeMode){
    return await new Promise((resolve,reject)=>{
      const id=++requestId;
      nativePending.set(id,{resolve,reject});
      try { nativeBridge.postMessage(JSON.stringify({...input,requestId:id})); }
      catch(error){nativePending.delete(id);reject(error);}
    });
  }
  if (browserMode) {
    rulesModule ??= import('./rules-browser.mjs');
    const rules=await rulesModule;
    if(input.session&&!browserSessions.has(input.session)){
      const saved=localGet('lulu-arcade-save:'+kind);
      if(saved) rules.arcadeRestore(input.session,saved);
      browserSessions.add(input.session);
    }
    const result=JSON.parse(rules.arcadeRequest(JSON.stringify(input)));
    if(result.error)throw new Error(result.error);
    if(input.verb!=='view'){
      browserSessions.add(result.session);
      localSet('lulu-arcade-save:'+kind,rules.arcadeSave(result.session));
    }
    return result;
  }
  const response = await fetch('./rules', {
    method:'POST', headers:{'Content-Type':'application/json'},
    body:JSON.stringify(input)
  });
  const value = await response.json();
  if (!response.ok || value.error) throw new Error(value.error || '试玩引擎未能回应');
  return value;
}
function localGet(key) {
  try { return localStorage.getItem(key); } catch { return null; }
}
function localSet(key,value) {
  try { localStorage.setItem(key,value); } catch { /* The session is still playable in this tab. */ }
}
function storage(mode, key, value) {
  if(nativeMode)return nativeSession;
  try {
    const id = 'lulu-arcade-session:'+mode;
    const database=browserMode?localStorage:sessionStorage;
    if (key === 'get') return database.getItem(id);
    if (key === 'delete') database.removeItem(id);
    else database.setItem(id,value);
  } catch { return null; }
}
function setBusy(value, hint = '') {
  busy = value;
  $('start').disabled = value;
  $('resume').disabled = value;
  if (!launch.hidden) $('launch-status').textContent = hint;
}
function orient() {
  const gated=!landscape.matches && (!launch.hidden||!game.hidden);
  $('orientation-game').textContent=names[kind]||'游戏馆';
  $('orientation-gate').hidden=!gated;
  launch.inert=gated;
  game.inert=gated;
  if(gated){
    stopHold();
    for(const dialog of [crewPicker, equipmentDialog, options])if(dialog.open)dialog.close();
    if(chestSession)cancelOpening();
    closeGather();
  }
  if(!gated)draw();
  syncExtraction();
}
function showCrew() {
  if(!landscape.matches)return;
  crewPage=0;
  drawCrewPicker();
  crewPicker.showModal();
}
function drawCrewPicker() {
  const list=$('crew-options');
  list.replaceChildren();
  for(const role of crewProfiles){
    const joined=crew.includes(role.id);
    const button=document.createElement('button');
    button.type='button';
    button.disabled=inviting||!joined&&crew.length>=2;
    const copy=document.createElement('span');
    copy.textContent=role.title?role.name+' · '+role.title:role.name;
    if(role.desc){
      const description=document.createElement('small');
      description.textContent=role.desc;
      copy.append(description);
    }
    const action=document.createElement('strong');
    action.textContent=joined?'移出':'邀请';
    button.append(copy,action);
    button.addEventListener('click',async()=>{
      if(nativeMode){
        inviting=true;
        drawCrewPicker();
        $('crew-status').textContent=joined?'正在更新队伍…':`正在等${role.name}回复邀请…`;
        try{
          const result=await request(joined?'remove':'invite',{actor:role.id});
          crew=result.party;
          $('crew-status').textContent=joined?`${role.name}已离队。`:`${role.name} · ${result.message}`;
        }catch(error){ $('crew-status').textContent=error.message; }
        finally{ inviting=false; }
      }else crew=joined?crew.filter(id=>id!==role.id):[...crew,role.id];
      drawParty();
      drawCrewPicker();
      list.querySelectorAll('button')[crewProfiles.indexOf(role)]?.focus();
    });
    list.append(button);
  }
  if(nativeMode&&!crewProfiles.length)$('crew-status').textContent='还没有角色，可以先单人出发。';
  crewPage=paginate(list,$('crew-pages'),crewPage,change=>{crewPage=change;drawCrewPicker();});
}
function drawParty() {
  const party=$('lobby-party');
  party.replaceChildren();
  const create=(label,detail,interactive)=>{
    const el=document.createElement(interactive?'button':'div');
    el.textContent=label;
    if(interactive){el.type='button';el.addEventListener('click',showCrew);}
    else el.className='party-player';
    const sub=document.createElement('span');
    sub.textContent=detail;el.append(sub);
    return el;
  };
  party.append(create('你','领队',false));
  for(let i=0;i<2;i++)party.append(create(crew[i]?roleName(crew[i]):'＋ 空位',crew[i]?'已入队':'邀请角色',true));
  $('crew-count').textContent=`${crew.length+1} / 3`;
}
function setup(mode) {
  kind = mode;
  if(!nativeMode)crew=[...gamePreferences[mode]];
  const island=mode==='island';
  launch.dataset.kind=mode;
  launch.style.setProperty('--lobby-image',`url(${new URL(island?'./art/island.png':'./art/relic.webp',import.meta.url)})`);
  $('lobby-location').textContent=island?'海岸营地':'回声遗迹 · 前哨营地';
  $('launch-kicker').textContent=island?'海岸、雪松与灯塔':'外廊、钟楼与地下宝库';
  $('launch-title').textContent = names[mode];
  $('launch-copy').textContent = island
    ? '沿沙径进林子，备好木石和工具，选一块能看见海的地方起屋。'
    : '外廊有一条稳妥的撤离路，钟楼后藏着丰厚的战利品。开箱时留意守卫。';
  $('lobby-landmarks').textContent=island?'第一人称 3D　·　采集 / 建造 / 种植':'斜俯视　·　开箱 / 掩护 / 撤离';
  $('relic-config').hidden = island;
  $('start').textContent = island ? '登岛' : '进入遗迹';
  $('resume').hidden = !storage(mode,'get');
  $('launch-status').textContent = '';
  drawParty();
  updateDifficulty();
  $('hall').hidden=true;
  launch.hidden=false;
  orient();
  if(landscape.matches)$('lobby-back').focus({preventScroll:true});
  if(nativeMode)loadNativeLobby();
}
async function loadNativeLobby(){
  setBusy(true,'正在读取本机存档…');
  try{
    const lobby=await request('lobby');
    nativeSession=lobby.session;
    crewProfiles=lobby.roles;
    crew=lobby.party;
    drawParty();
    $('resume').hidden=!nativeSession;
    $('start').hidden=lobby.activeRun;
    if(kind==='relic'){
      for(const input of equipmentDialog.querySelectorAll('[name="weapon"]'))input.disabled=!lobby.weapons.includes(input.value);
    }
  }catch(error){
    $('launch-status').textContent=error.message;
    busy=false;
    return;
  }
  setBusy(false);
}
function closeLobby(){
  if(nativeMode){ request('exit');return; }
  ++epoch;
  busy=false;
  $('start').disabled=false;
  $('resume').disabled=false;
  launch.hidden=true;
  $('hall').hidden=false;
  if(crewPicker.open)crewPicker.close();
  orient();
  document.querySelector(`[data-launch="${kind}"]`)?.focus({preventScroll:true});
}
async function enter(resume) {
  if (busy||!landscape.matches||launch.hidden) return;
  const activeEpoch = ++epoch;
  setBusy(true,nativeMode?'正在读取地图…':browserMode?'正在加载岛屿与遗迹规则…':'正在启动 Kotlin 游戏引擎，首次需要编译。');
  try {
    if (kind==='island'&&!islandRenderer) islandRenderer = await islandMap(islandCanvas);
    if(kind==='relic'){
      ceremony??=chestShow();
      await ceremony.prepare();
    }
    const data = new FormData(form);
    if(!resume)gamePreferences[kind]=[...crew];
    const next = resume
      ? await request(nativeMode?'resume':'view',{session:storage(kind,'get'), dx:0, dy:1})
      : await request('start', {difficulty:data.get('difficulty'),weapon:data.get('weapon'),profession:data.get('profession'),
          crew, map:kind==='island'?'coast':data.get('route'),
          seed:kind==='island'?4317:Math.floor(Math.random()*100000)+1,dx:0,dy:1});
    if (activeEpoch !== epoch) return;
    state=next;
    if(nativeMode)nativeSession=state.session;
    storage(kind,'set',state.session);
    selected=null; facing=[0,1]; recipe=''; camera={yaw:next.world?.width===35?1.58:.48,pitch:-.15}; overview=false;
    game.dataset.kind=kind;
    islandCanvas.hidden=kind!=='island'; relicCanvas.hidden=kind!=='relic';
    $('overview').hidden=kind!=='island';
    $('crosshair').hidden=kind!=='island';
    $('overview').textContent='地图';
    renderer=kind==='island'?islandRenderer:relicMap(relicCanvas);
    if(kind==='island')renderer.reset();
    launch.hidden=true;
    $('hall').hidden=true;
    game.hidden=false;
    orient();
    $('leave').focus({preventScroll:true});
    render();
    setTimeout(syncAim,0);
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
  finishOpening();
  closeGather();
  clearExtraction();
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
  lookDrag=null;
  if(redraw)cancelAnimationFrame(redraw);
  redraw=null;
  if(nativeMode){setup(kind);return;}
  orient();
}
function snapshotInput() {
  return {dx:facing[0],dy:facing[1],build:recipe,timedExtraction:kind==='relic',
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
    if (next.receipt&&!chestSession) showReceipt(next.receipt);
    if (kind==='relic' && terminal.has(next.phase) && next.phase!==before.phase) showResult();
    return next;
  } catch(error) {
    if(token===epoch)$('feedback').textContent=error.message;
  } finally {
    if(token===epoch){
      busy=false;
      if(verb==='act'&&kind==='island'&&!directionHeld)syncAim();
    }
  }
}
function move(dx,dy) {
  if(!state || busy || options.open || chestSession || !landscape.matches || (kind==='relic' && terminal.has(state.phase)))return;
  if(gather)closeGather();
  if(kind==='island'){
    facing=renderer.direction(dx,dy,camera);
    if(overview)toggleOverview();
  } else facing=[dx,dy];
  return update('act',{action:'move',dx:facing[0],dy:facing[1]},null);
}
function perform(item) {
  if (!item || !item.enabled || busy || !landscape.matches) return;
  if(kind==='relic'&&item.action==='open'){beginOpening(item);return;}
  if(kind==='island'&&item.action==='gather'&&!state.tools.includes('axe')&&
    state.world.tiles[item.y*state.world.width+item.x]===1){openGather(item);return;}
  if(item.action==='choose_build'){
    recipe=item.id;
    const w=state.world;
    const aim=renderer.aim();
    const x=aim?.[0]??w.x+facing[0],y=aim?.[1]??w.y+facing[1];
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
  update('act',{action:item.action,id:item.id,x:item.x,y:item.y,actor:item.actor});
}
function draw(){
  if(state && !game.hidden && landscape.matches) renderer.paint(state,selected,camera,overview,chestSession?.box);
}
function render() {
  if(!state) return;
  const island=kind==='island';
  const s=state,w=s.world;
  const complete=!island&&terminal.has(s.phase);
  const withdrawing=!island&&s.phase==='EXTRACT';
  const active=s.actions.find(a=>a.section==='main');
  const fight=s.actions.find(a=>a.section==='combat'&&a.action==='attack');
  const compass=['南','东','北','西'];
  const heading=compass[((Math.round(camera.yaw/Math.PI*2)%4)+4)%4];
  $('map-name').textContent=island?(overview?'方块小岛 · 全岛地图':`方块小岛 · ${s.swimming?'海湾 · 游泳':'岸林'} · ${heading}`):`迷雾摸金 · ${counters[s.difficulty]||'标准'}`;
  game.dataset.swimming=island&&s.swimming?'yes':'no';
  $('objective').textContent=complete?(s.phase==='SUCCESS'?'撤离成功':'撤离失败'):withdrawing?'撤离中 · 留在门边':s.objective;
  $('stats').textContent=island
    ? `生命 ${w.hp}/12　饱食 ${s.hunger}/10　木 ${w.wood}　石 ${w.stone}`
    : `生命 ${s.hero.hp}/10　负重 ${s.load}/${s.capacity}　宝物 ${s.carried}　估值 ${s.value}`;
  const message=s.notice || '';
  $('feedback').textContent=/^((?:走|游)到了 \(.*|点击格子选中目标.*|前进一步|原地观察|撤离启动 · 留在出口附近)$/.test(message)?'':message;
  game.dataset.overview=overview?'yes':'no';
  $('crosshair').classList.toggle('ready',island&&!!active?.enabled);
  $('focus').textContent=complete?'点击查看本次实际带出的宝物。'
    : withdrawing?'倒计时会自动推进，离开出口会取消。'
    : recipe?(active?.hint||'把准星对准要放置的地块。')
    : active?.hint || (island?'左手走路 · 拖动场景看四周':'移动探索迷雾，或点选已发现的目标。');
  $('primary').textContent=complete?'查看结算':withdrawing?'撤离中':active?.label||'互动';
  $('primary').disabled=withdrawing||!complete&&!active?.enabled;
  $('primary').onclick=complete?showResult:()=>perform(active);
  $('fight').hidden=!fight||complete;
  $('fight').onclick=()=>perform(fight);
  $('tools').hidden=complete;
  $('tools').textContent=island?'建造 / 行囊':'战术';
  $('partner').hidden=complete || !(island?w.friends.length:s.allies.length);
  updateHotbar(island);
  syncExtraction();
  draw();
}
const quickRecipes=[
  { id:'workbench',icon:'⚒',label:'工作台' }, { id:'house_warm',icon:'⌂',label:'木屋' },
  { id:'garden',icon:'♧',label:'菜圃' }, { id:'campfire',icon:'♨',label:'篝火' },
  { id:'bag',icon:'▤',label:'背包' }
];
let hotbarKey='';
function updateHotbar(island) {
  const bar=$('hotbar');
  bar.hidden=!island;
  if(!island||hotbarKey===recipe&&bar.children.length)return;
  hotbarKey=recipe;
  const fragment=document.createDocumentFragment();
  for(const entry of quickRecipes){
    const b=document.createElement('button');
    b.type='button';b.setAttribute('aria-label',entry.label);
    if(entry.id===recipe)b.classList.add('active');
    b.textContent=entry.icon;
    const label=document.createElement('small');label.textContent=entry.label;b.append(label);
    b.onclick=()=>{
      if(entry.id==='bag'){showBag();return;}
      if(recipe===entry.id){
        recipe='';
        update('view');return;
      }
      perform(state.actions.find(a=>a.action==='choose_build'&&a.id===entry.id));
    };
    fragment.append(b);
  }
  bar.replaceChildren(fragment);
}
function showBag(){
  const {world:w}=state;
  const list=dialogPage('个人物品','背包',`生命 ${w.hp}/12 · 饱食 ${state.hunger}/10`);
  const inventory=document.createElement('dl');
  inventory.className='bag-resources';
  const toolNames={axe:'木斧',wood_pickaxe:'木镐',pickaxe:'石镐',sword:'木剑',rod:'鱼竿',charm:'护符'};
  for(const [label,quantity] of [['木材',w.wood],['石材',w.stone],['野果',state.fruit],['晶石',state.crystals],
    ['鱼',state.fish||0],['工具',state.tools.map(id=>toolNames[id]||id).join('、')||'空']]){
    const cell=document.createElement('div');
    const name=document.createElement('dt');name.textContent=label;
    const value=document.createElement('dd');value.textContent=quantity;
    cell.append(name,value);
    inventory.append(cell);
  }
  list.append(inventory);
  const footer=$('option-footer');
  option(footer,'查看配方','',true,()=>showTools(true));
  const eat=state.actions.find(a=>a.action==='eat');
  option(footer,'吃野果','',!!eat?.enabled,()=>{options.close();perform(eat);});
}
function guardOrder() {
  return state?.actions.find(a=>a.action==='partner'&&a.id==='guard_player'&&a.enabled);
}
function bindHold(button, begin, end) {
  let pointer=null, key=null, active=false;
  function stop(){
    pointer=null;key=null;
    if(active){active=false;end();}
  }
  button.addEventListener('pointerdown',event=>{
    if(event.button!==0||button.disabled||active)return;
    event.preventDefault();
    pointer=event.pointerId;
    button.setPointerCapture(pointer);
    active=true;begin();
  });
  for(const type of ['pointerup','pointercancel','lostpointercapture']){
    button.addEventListener(type,event=>{if(event.pointerId===pointer)stop();});
  }
  button.addEventListener('keydown',event=>{
    if(event.key!==' '&&event.key!=='Enter')return;
    event.preventDefault();
    if(!active){key=event.key;active=true;begin();}
  });
  button.addEventListener('keyup',event=>{if(event.key===key){event.preventDefault();stop();}});
  button.addEventListener('blur',stop);
  button.addEventListener('click',event=>{
    if(event.detail!==0||button.disabled)return;
    if(active)stop();else{active=true;begin();}
  });
  return stop;
}
function finishOpening(){
  if(!chestSession)return;
  clearTimeout(chestSession.timer);
  chestSession=null;
  releaseChestHold();
  ceremony?.close();
  draw();
}
async function cancelOpening(){
  const opening=chestSession;
  if(!opening)return;
  if(opening.committed){finishOpening();return;}
  opening.cancelled=true;
  releaseChestHold();
  if(busy)return;
  finishOpening();
  await update('act',{action:'cancel_chest',x:opening.box.x,y:opening.box.y});
}
function chestWarning(current){
  const attackers=current.mobs.filter(m=>Math.abs(m.x-current.hero.x)+Math.abs(m.y-current.hero.y)<=5);
  return attackers.some(m=>m.windup)?'守卫已蓄力 · 松手并离开':
    attackers.some(m=>m.alert>0)?'听见脚步 · 守卫正在靠近':'留意周围脚步';
}
function beginOpening(item){
  if(chestSession||!state||busy)return;
  const box=state.chests.find(c=>c.x===item.x&&c.y===item.y&&!c.opened);
  if(!box)return;
  stopHold();
  ceremony??=chestShow();
  const opening={box,step:state.chestProgress?.[box.id]||0,max:box.openSteps||4,
    committed:false,held:false,timer:null};
  chestSession=opening;
  ceremony.begin(box);
  ceremony.step(opening.step/opening.max,'按住撬锁开始。松手可以停下。',!!guardOrder());
  draw();
}
function schedulePry(opening,delay) {
  clearTimeout(opening.timer);
  opening.timer=setTimeout(()=>pry(opening),delay);
}
async function pry(opening){
  if(chestSession!==opening||!opening.held||opening.committed)return;
  if(opening.cancelled||!landscape.matches||paused){await cancelOpening();return;}
  if(busy){schedulePry(opening,45);return;}
  const {box}=opening;
  const result=await update('act',{action:'cinematic_step',x:box.x,y:box.y});
  if(chestSession!==opening)return;
  if(!result){finishOpening();return;}
  if(result.chestInterrupted||result.phase==='DOWNED'||terminal.has(result.phase)){
    ceremony.interrupt();
    finishOpening();
    $('feedback').textContent=result.notice;
    return;
  }
  if(result.receipt){
    opening.committed=true;
    releaseChestHold();
    ceremony.reveal(result.receipt);
    return;
  }
  if(opening.cancelled){await cancelOpening();return;}
  opening.step=result.chestProgress?.[box.id]??opening.step+1;
  ceremony.step(opening.step/opening.max,chestWarning(result),!!guardOrder());
  if(opening.held)schedulePry(opening,550);
}
const releaseChestHold=bindHold($('chest-hold'),()=>{
  const opening=chestSession;
  if(!opening||opening.committed)return;
  opening.held=true;
  ceremony.hold(true);
  schedulePry(opening,550);
},()=>{
  const opening=chestSession;
  if(!opening||opening.committed)return;
  opening.held=false;
  clearTimeout(opening.timer);
  ceremony.hold(false);
});
function closeGather(){
  if(!gather)return;
  const frame=gather.frame;
  gather=null;
  cancelAnimationFrame(frame);
  releaseGatherHold();
  $('gather-panel').hidden=true;
}
function openGather(item){
  closeGather();
  gather={item,frame:null};
  $('gather-progress').value=0;
  $('gather-panel').hidden=false;
  $('gather-hold').focus({preventScroll:true});
}
const releaseGatherHold=bindHold($('gather-hold'),()=>{
  const run=gather;
  if(!run)return;
  const from=performance.now();
  const advance=now=>{
    if(gather!==run)return;
    const progress=Math.min(1,(now-from)/1650);
    $('gather-progress').value=progress;
    if(progress<1)run.frame=requestAnimationFrame(advance);
    else{
      closeGather();
      update('act',{action:run.item.action,x:run.item.x,y:run.item.y});
    }
  };
  run.frame=requestAnimationFrame(advance);
},()=>{
  if(!gather)return;
  cancelAnimationFrame(gather.frame);
  $('gather-progress').value=0;
});
$('gather-cancel').onclick=closeGather;

function clearExtraction(){
  if(extraction){
    clearTimeout(extraction.timer);
    cancelAnimationFrame(extraction.frame);
    extraction=null;
  }
  $('extract-clock').hidden=true;
}
function syncExtraction(){
  if(kind!=='relic'||game.hidden||paused||document.hidden||!landscape.matches||state?.phase!=='EXTRACT'){
    clearExtraction();return;
  }
  if(extraction)return;
  const countdown={deadline:performance.now()+1000,timer:null,frame:null};
  extraction=countdown;
  $('extract-clock').hidden=false;
  const steps=state.extractionSteps||3;
  $('extract-progress').max=steps;
  function drawCountdown(now){
    if(extraction!==countdown)return;
    const fraction=Math.max(0,Math.min(1,1-(countdown.deadline-now)/1000));
    const remaining=Math.max(1,steps-state.extraction);
    $('extract-time').textContent=`撤离 ${remaining} 秒`;
    $('extract-progress').value=state.extraction+fraction;
    countdown.frame=requestAnimationFrame(drawCountdown);
  }
  function schedule(){
    countdown.timer=setTimeout(tick,Math.max(0,countdown.deadline-performance.now()));
  }
  async function tick(){
    if(extraction!==countdown)return;
    if(busy){countdown.timer=setTimeout(tick,40);return;}
    const before=state.extraction;
    const result=await update('act',{action:'extract_tick'});
    if(extraction!==countdown)return;
    if(!result||result.phase!=='EXTRACT'||result.extraction<=before){
      clearExtraction();return;
    }
    countdown.deadline=Math.max(performance.now()+50,countdown.deadline+1000);
    schedule();
  }
  drawCountdown(performance.now());
  schedule();
}
$('chest-stop').onclick=cancelOpening;
$('chest-continue').onclick=finishOpening;
$('chest-cover').onclick=async()=>{
  const opening=chestSession;
  const action=guardOrder();
  if(!opening||opening.committed||!action||busy)return;
  const result=await update('act',{action:'partner',actor:action.actor,id:action.id});
  if(result&&chestSession===opening&&opening.cancelled){await cancelOpening();return;}
  if(result&&chestSession===opening&&!opening.committed)
    ceremony.step(opening.step/opening.max,'队友已就位',false);
};
let crewPage=0, optionPage=0;
function paginate(list,pager,page,onChange){
  const rows=[...list.children];
  const limit=window.innerHeight<=340?2:window.innerHeight<480?3:5;
  const count=Math.max(1,Math.ceil(rows.length/limit));
  const current=Math.max(0,Math.min(page,count-1));
  rows.forEach((row,index)=>{row.hidden=Math.floor(index/limit)!==current;});
  pager.hidden=count<=1;
  pager.replaceChildren();
  if(count>1){
    const button=(label,delta)=>{
      const element=document.createElement('button');
      element.type='button';element.textContent=label;
      element.disabled=current+delta<0||current+delta>=count;
      element.onclick=()=>onChange(current+delta);
      return element;
    };
    const countLabel=document.createElement('span');
    countLabel.textContent=`${current+1} / ${count}`;
    pager.append(button('上一页',-1),countLabel,button('下一页',1));
  }
  return current;
}
function refreshOptionPages(){
  optionPage=paginate($('option-list'),$('option-pages'),optionPage,page=>{
    optionPage=page;refreshOptionPages();
  });
}
function dialogPage(kicker,title,desc) {
  stopHold();
  $('options-kicker').textContent=kicker;
  $('options-title').textContent=title;
  $('options-desc').textContent=desc;
  const list=$('option-list');
  list.replaceChildren();
  $('option-footer').replaceChildren();
  optionPage=0;
  queueMicrotask(refreshOptionPages);
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
  if(nativeMode){request('team').catch(error=>{$('feedback').textContent=error.message;});return;}
  const list=dialogPage('本地协作','试玩队友',
    state.actorEvent || '你可以手动指挥你邀请进来的队友。');
  const actions=state.actions.filter(a=>a.section==='partner');
  if(!actions.length)option(list,'当前没有可执行的队友动作','先推进一个游戏回合。',false);
  for(const action of actions)option(list,`${action.actor?action.actor+' · ':''}${action.label}`,'',action.enabled,()=>{
    options.close();perform(action);
  });
}
function lootRow(list,item){
  const row=document.createElement('div');row.className='result-loot';
  const model=document.createElement('img');
  model.src=lootAsset(item);model.alt='';
  const name=document.createElement('b');name.textContent=item.name;
  const value=document.createElement('span');value.textContent=`估值 ${item.value}`;
  row.append(model,name,value);
  list.append(row);
}
function showReceipt(receipt){
  const list=dialogPage('宝箱回执',receipt.name,receipt.inBag?'已放进背包。':'宝物留在原地。');
  lootRow(list,receipt);
  option($('option-footer'),'继续探索','',true,()=>options.close());
}
function showResult(){
  const success=state.phase==='SUCCESS',items=(success?state.secured:state.lost)||[];
  const list=dialogPage('探险结算',success?'成功撤离':'未能撤离',
    success?`带回 ${items.length} 件 · 总估值 ${items.reduce((sum,item)=>sum+item.value,0)}`
      :`本次损失 ${items.length} 件宝物。`);
  for(const item of items)lootRow(list,item);
  const footer=$('option-footer');
  option(footer,'回到营地','',true,()=>{
    leave();
    if(!nativeMode)setup('relic');
  });
  option(footer,'继续查看地图','',true,()=>options.close());
}
function pointerPoint(e,element) {
  return window.LuluWorldJoystick.pointerLocal(e.clientX,e.clientY,element.getBoundingClientRect(),false);
}
function hitMap(e){
  if(!state||busy || chestSession || !landscape.matches || (kind==='island'&&!overview) || (kind==='relic'&&terminal.has(state.phase)))return;
  const point=renderer.pick(...pointerPoint(e,e.currentTarget));
  if(!point || (kind==='relic'&&state.tiles[point[1]*state.w+point[0]]<0))return;
  selected=point;
  draw();
  update('view');
}
function syncAim(){
  if(!state||busy||!landscape.matches||kind!=='island'||overview||options.open)return;
  const aim=renderer.aim();
  if(!aim&&recipe)return;
  if(String(aim)===String(selected))return;
  selected=aim;
  update('view');
}
function toggleOverview(){
  overview=!overview;
  selected=null;
  $('crosshair').hidden=overview;
  $('overview').textContent=overview?'回到地面':'地图';
  render();
  if(!overview)syncAim();
}
function paintLook(){
  if(redraw)return;
  redraw=requestAnimationFrame(()=>{
    redraw=null;
    draw();
    const compass=['南','东','北','西'];
    const heading=compass[((Math.round(camera.yaw/Math.PI*2)%4)+4)%4];
    $('map-name').textContent=`方块小岛 · ${state?.swimming?'海湾 · 游泳':'岸林'} · ${heading}`;
  });
}
function turnLook(dx,dy){
  camera.yaw-=dx;
  camera.pitch=Math.max(-1.09,Math.min(.80,camera.pitch-dy));
  paintLook();
}
function stopHold(){
  clearTimeout(heldTimer);heldTimer=null;
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
  function repeat(){
    if(directionHeld!==next)return;
    move(...next);
    heldTimer=setTimeout(repeat,kind==='island'?(state.swimming?270:185):405);
  }
  heldTimer=setTimeout(repeat,kind==='island'?(state.swimming?270:185):405);
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
$('lobby-back').onclick=closeLobby;
$('orientation-back').onclick=()=>{ if(!game.hidden)leave();else closeLobby(); };
$('equipment').onclick=()=>equipmentDialog.showModal();
landscape.addEventListener('change',orient);
$('leave').onclick=leave;
$('tools').onclick=()=>showTools();
$('partner').onclick=showPartner;
$('overview').onclick=toggleOverview;
islandCanvas.addEventListener('pointerdown',e=>{
  if(!state||overview||lookDrag||!landscape.matches||e.button!==0)return;
  lookDrag={id:e.pointerId,x:e.clientX,y:e.clientY};
  islandCanvas.setPointerCapture(e.pointerId);
});
islandCanvas.addEventListener('pointermove',e=>{
  if(lookDrag?.id!==e.pointerId)return;
  turnLook((e.clientX-lookDrag.x)*.006,(e.clientY-lookDrag.y)*.006);
  lookDrag={id:e.pointerId,x:e.clientX,y:e.clientY};
});
function finishLook(e) {
  if(lookDrag?.id!==e.pointerId)return;
  lookDrag=null;
  draw();
  syncAim();
}
for(const type of ['pointerup','pointercancel','lostpointercapture'])
  islandCanvas.addEventListener(type,finishLook);
for(const canvas of [islandCanvas,relicCanvas])canvas.addEventListener('click',hitMap);
pad.addEventListener('pointerdown',e=>{
  if(pressId!==null||chestSession||!landscape.matches)return;
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
  if(game.hidden||options.open||chestSession||!landscape.matches||e.ctrlKey||e.altKey||e.metaKey)return;
  if(e.key in keyDirs && !e.repeat){e.preventDefault();switchDirection(keyDirs[e.key]);}
  if(kind==='island'&&!overview&&['q','e','r','f'].includes(e.key)){
    e.preventDefault();
    turnLook(e.key==='q'?-.16:e.key==='e'?.16:0,e.key==='r'?-.13:e.key==='f'?.13:0);
    draw();
    syncAim();
  }
  if(e.key===' '&&e.target===document.body){e.preventDefault();$('primary').click();}
});
window.addEventListener('keyup',e=>{if(String(keyDirs[e.key])===String(directionHeld))stopHold();});
window.addEventListener('blur',stopHold);
window.addEventListener('resize',()=>{
  orient();
  if(options.open)refreshOptionPages();
  if(crewPicker.open)drawCrewPicker();
});
document.addEventListener('visibilitychange',()=>{
  paused=document.hidden;
  if(paused){stopHold();closeGather();if(chestSession)cancelOpening();}
  syncExtraction();
});

if(nativeMode){
  $('lobby-preview').hidden=true;
  $('crew-title').textContent='邀请角色';
  $('crew-status').textContent='角色答应后入队，最多两位同行。';
  $('resume').textContent='继续上次进度';
  $('partner').textContent='语音与队友';
  window.luluArcadeBack=()=>{
    if(crewPicker.open)crewPicker.close();
    else if(equipmentDialog.open)equipmentDialog.close();
    else if(options.open)options.close();
    else if(chestSession)cancelOpening();
    else if(gather)closeGather();
    else if(!game.hidden)leave();
    else closeLobby();
    return true;
  };
  window.luluArcadePing=(x,y)=>{ if(state&&!chestSession){selected=[x,y];update('view');} };
  window.addEventListener('lulu:world',()=>{if(state&&!chestSession)update('view');});
  window.addEventListener('lulu:pause',()=>{
    paused=true;stopHold();closeGather();if(chestSession)cancelOpening();syncExtraction();
  });
  window.addEventListener('lulu:resume',()=>{paused=false;syncExtraction();});
  setup(new URLSearchParams(location.search).get('scene')==='relic'?'relic':'island');
}
