import { islandMap } from './island-map.js';
import { relicMap } from './relic-map.js';
import { chestShow } from './chest-show.js';

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
let crewProfiles=[
  { name:'苔羽', title:'林地采集', desc:'能寻找近处资源，并能动手采集或参与建造。'},
  { name:'砾灯', title:'营地护卫', desc:'可下达近身护卫和应战指令。'},
  { name:'渡鸦', title:'遗迹探路', desc:'可前往指定区域，或者围堵附近威胁。'}
].map(role=>({...role,id:role.name}));
const roleName=id=>crewProfiles.find(role=>role.id===id)?.name||'队友';
let crew=[], chestSession=null, ceremony;

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
  if(gated){stopHold();if(crewPicker.open)crewPicker.close();if(options.open)options.close();if(chestSession)cancelOpening();}
  if(!gated)draw();
}
function showCrew() {
  if(!landscape.matches)return;
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
      for(const input of form.querySelectorAll('[name="weapon"]'))input.disabled=!lobby.weapons.includes(input.value);
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
    const data = new FormData(form);
    if(!resume)gamePreferences[kind]=[...crew];
    const next = resume
      ? await request(nativeMode?'resume':'view',{session:storage(kind,'get'), dx:0, dy:1})
      : await request('start', {difficulty:data.get('difficulty'),weapon:data.get('weapon'),profession:data.get('profession'),
          crew, map:kind==='island'?'coast':data.get('route'),seed:kind==='island'?4317:Math.floor(Math.random()*100000)+1,dx:0,dy:1});
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
    if (next.receipt&&!chestSession) showReceipt(next.receipt);
    if (kind==='relic' && terminal.has(next.phase) && next.phase!==before.phase) showResult();
    return next;
  } catch(error) {
    if(token===epoch)$('feedback').textContent=error.message;
  } finally {
    if(token===epoch){
      busy=false;
      if(verb==='act'&&kind==='island')syncAim();
    }
  }
}
function move(dx,dy) {
  if(!state || busy || options.open || chestSession || !landscape.matches || (kind==='relic' && terminal.has(state.phase)))return;
  if(kind==='island'){
    facing=renderer.direction(dx,dy,camera);
    if(overview)toggleOverview();
  } else facing=[dx,dy];
  update('act',{action:'move',dx:facing[0],dy:facing[1]},null);
}
function perform(item) {
  if (!item || !item.enabled || busy || !landscape.matches) return;
  if(kind==='relic'&&item.action==='open'){beginOpening(item);return;}
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
  const active=s.actions.find(a=>a.section==='main');
  const fight=s.actions.find(a=>a.section==='combat'&&a.action==='attack');
  const compass=['南','东','北','西'];
  const heading=compass[((Math.round(camera.yaw/Math.PI*2)%4)+4)%4];
  $('map-name').textContent=island?(overview?'方块小岛 · 全岛地图':`方块小岛 · 岸林 · ${heading}`):`迷雾摸金 · ${counters[s.difficulty]||'标准'}`;
  $('objective').textContent=complete ? (s.phase==='SUCCESS'?'撤离成功':'撤离失败') : s.objective;
  $('stats').textContent=island
    ? `生命 ${w.hp}/12　饱食 ${s.hunger}/10　木 ${w.wood}　石 ${w.stone}`
    : `生命 ${s.hero.hp}/10　负重 ${s.load}/${s.capacity}　宝物 ${s.carried}　估值 ${s.value}`;
  const message=s.notice || '';
  $('feedback').textContent=/^(走到了 \(.*|点击格子选中目标.*|前进一步)$/.test(message)?'':message;
  game.dataset.overview=overview?'yes':'no';
  $('crosshair').classList.toggle('ready',island&&!!active?.enabled);
  $('focus').textContent=complete?'点击查看本次实际带出的宝物。'
    : recipe?(active?.hint||'把准星对准要放置的地块。')
    : active?.hint || (island?'左手走路 · 拖动场景看四周':'移动探索迷雾，或点选已发现的目标。');
  $('primary').textContent=complete?'查看结算':active?(active.action==='open'?'开始开箱':active.label):'互动';
  $('primary').disabled=!complete&&!active?.enabled;
  $('primary').onclick=complete?showResult:()=>perform(active);
  $('fight').hidden=!fight||complete;
  $('fight').onclick=()=>perform(fight);
  $('tools').hidden=complete;
  $('tools').textContent=island?'建造 / 行囊':'战术';
  $('partner').hidden=complete || !(island?w.friends.length:s.allies.length);
  updateHotbar(island);
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
  for(const item of [`木材 × ${w.wood}`,`石材 × ${w.stone}`,`野果 × ${state.fruit}`,`晶石 × ${state.crystals}`,`鱼 × ${state.fish||0}`])
    lootRow(list,item);
  option(list,'查看配方和已有工具',state.tools?.length?state.tools.join('、'):'可在工作台附近制作',true,()=>showTools(true));
  const eat=state.actions.find(a=>a.action==='eat');
  option(list,'吃野果','恢复饱食',!!eat?.enabled,()=>{options.close();perform(eat);});
}
function guardOrder() {
  return state?.actions.find(a=>a.action==='partner'&&a.id==='guard_player'&&a.enabled);
}
function finishOpening(){
  if(!chestSession)return;
  clearTimeout(chestSession.timer);
  chestSession=null;
  ceremony?.close();
  draw();
}
async function cancelOpening(){
  const opening=chestSession;
  if(!opening)return;
  if(opening.committed){finishOpening();return;}
  if(busy){opening.cancelled=true;return;}
  finishOpening();
  await update('act',{action:'cancel_chest',x:opening.box.x,y:opening.box.y});
}
function beginOpening(item){
  if(chestSession||!state||busy)return;
  const box=state.chests.find(c=>c.x===item.x&&c.y===item.y&&!c.opened);
  if(!box)return;
  stopHold();
  ceremony??=chestShow();
  const opening={box,step:0,max:4+Math.floor(Math.max(0,box.grade)/2),committed:false};
  chestSession=opening;
  ceremony.begin(box);
  draw();
  const schedule=(ms)=>{opening.timer=setTimeout(pulse,ms);};
  async function pulse(){
    if(chestSession!==opening)return;
    if(opening.cancelled||!landscape.matches){await cancelOpening();return;}
    if(busy){schedule(75);return;}
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
      ceremony.reveal(result.receipt);return;
    }
    if(opening.cancelled){await cancelOpening();return;}
    opening.step++;
    const attackers=result.mobs.filter(m=>Math.abs(m.x-result.hero.x)+Math.abs(m.y-result.hero.y)<=5);
    const warning=attackers.some(m=>m.windup)?'守卫已蓄力 · 可以立刻停手':
      attackers.some(m=>m.alert>0)?'听见脚步 · 守卫正在靠近':'留意来路 · 随时可以停手';
    if(opening.step>opening.max+1){finishOpening();return;}
    ceremony.step(opening.step/opening.max,warning,!!guardOrder());
    schedule(opening.step>=opening.max-1?610:510);
  }
  ceremony.step(0,'留意来路 · 随时可以停手',!!guardOrder());
  schedule(450);
}
$('chest-stop').onclick=cancelOpening;
$('chest-continue').onclick=finishOpening;
$('chest-cover').onclick=async()=>{
  const opening=chestSession;
  const action=guardOrder();
  if(!opening||opening.committed||!action||busy)return;
  const result=await update('act',{action:'partner',actor:action.actor,id:action.id});
  if(result&&chestSession===opening&&!opening.committed)
    ceremony.step(opening.step/opening.max,'队友已就位',false);
};
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
  if(nativeMode){request('team').catch(error=>{$('feedback').textContent=error.message;});return;}
  const list=dialogPage('本地协作','试玩队友',
    state.actorEvent || '你可以手动指挥你邀请进来的队友。');
  const actions=state.actions.filter(a=>a.section==='partner');
  if(!actions.length)option(list,'当前没有可执行的队友动作','先推进一个游戏回合。',false);
  for(const action of actions)option(list,`${action.actor?action.actor+' · ':''}${action.label}`,'',action.enabled,()=>{
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
    $('map-name').textContent=`方块小岛 · 岸林 · ${heading}`;
  });
}
function turnLook(dx,dy){
  camera.yaw-=dx;
  camera.pitch=Math.max(-1.09,Math.min(.80,camera.pitch-dy));
  paintLook();
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
$('lobby-back').onclick=closeLobby;
$('orientation-back').onclick=()=>{ if(!game.hidden)leave();else closeLobby(); };
$('invite').onclick=showCrew;
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
window.addEventListener('resize',orient);

if(nativeMode){
  $('classic').hidden=false;
  $('classic').onclick=()=>request('classic');
  $('lobby-preview').hidden=true;
  $('invite').textContent='＋ 邀请角色';
  $('crew-title').textContent='邀请角色';
  $('crew-status').textContent='角色答应后入队，最多两位同行。';
  $('resume').textContent='继续上次进度';
  $('partner').textContent='语音与队友';
  window.luluArcadeBack=()=>{
    if(crewPicker.open)crewPicker.close();
    else if(options.open)options.close();
    else if(chestSession)cancelOpening();
    else if(!game.hidden)leave();
    else closeLobby();
    return true;
  };
  window.luluArcadePing=(x,y)=>{ if(state&&!chestSession){selected=[x,y];update('view');} };
  window.addEventListener('lulu:world',()=>{if(state&&!chestSession)update('view');});
  window.addEventListener('lulu:pause',()=>{stopHold();if(chestSession)cancelOpening();});
  setup(new URLSearchParams(location.search).get('scene')==='relic'?'relic':'island');
}
