const assert=require('node:assert/strict');
const {test}=require('node:test');
const base=new URL('/arcade/rules',process.env.LULU_ARCADE_URL||'http://127.0.0.1:4173');
const directions=[[1,0],[0,1],[-1,0],[0,-1]];
async function send(verb, params) {
  const r=await fetch(base,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({verb,...params})});
  const data=await r.json();
  assert.ok(!data.error,data.error);
  return data;
}
function adjacent(a,b){ return Math.abs(a.x-b.x)+Math.abs(a.y-b.y)<=1; }
function closest(start,width,height,walk,goal){
  const queue=[{p:start,step:null}], seen=new Set([start.y*width+start.x]);
  for(let i=0;i<queue.length;i++){
    const {p,step}=queue[i], found=goal(p);
    if(found)return {step,found};
    for(const [dx,dy] of directions){
      const x=p.x+dx,y=p.y+dy,k=y*width+x;
      if(x<0||y<0||x>=width||y>=height||seen.has(k)||!walk(x,y))continue;
      seen.add(k);queue.push({p:{x,y},step:step||[dx,dy]});
    }
  }
  return null;
}
async function island(){
  let state=await send('start',{kind:'island',seed:4317});
  const act=async(action,args={})=>{state=await send('act',{session:state.session,action,...args});};
  const view=()=>send('view',{session:state.session});
  const source=state;
  await act('gather',{x:27,y:15});
  assert.equal(state.world.turns,source.world.turns);
  for(const [tile,count,resource] of [[1,4,'wood'],[2,2,'stone']]){
    for(let iteration=0;state.world[resource]<count;iteration++){
      assert.ok(iteration<32,'reach material');
      const w=state.world,crystals=new Set(state.crystalTiles);
      const at=(x,y)=>w.tiles[y*w.width+x];
      const path=closest(w,w.width,w.height,(x,y)=>[0,4,7].includes(at(x,y))&&!state.animals.some(a=>a.x===x&&a.y===y)&&!w.mobs.some(a=>a.x===x&&a.y===y&&a.hp>0),
        p=>directions.map(([dx,dy])=>({x:p.x+dx,y:p.y+dy})).find(({x,y})=>x>0&&x<w.width-1&&y>0&&y<w.height-1&&at(x,y)===tile&&!crystals.has(y*w.width+x)));
      assert.ok(path);
      if(path.step)await act('move',{dx:path.step[0],dy:path.step[1]});
      else await act('gather',path.found);
    }
  }
  const w=state.world;
  const target=directions.map(([dx,dy])=>({x:w.x+dx,y:w.y+dy})).find(({x,y})=>x>0&&x<w.width-1&&y>0&&y<w.height-1&&w.tiles[y*w.width+x]===0&&!state.supplyCaches.includes(y*w.width+x)&&!state.animals.some(a=>a.x===x&&a.y===y)&&!w.mobs.some(a=>a.x===x&&a.y===y&&a.hp>0));
  assert.ok(target,'buildable adjacent ground');
  const selection=await send('view',{session:state.session,...target,build:'workbench'});
  assert.equal(selection.actions.find(a=>a.action==='workbench').enabled,true);
  await act('workbench',target);
  assert.equal(state.structures[target.y*w.width+target.x],'workbench');
  assert.equal(state.world.wood,w.wood-4);
  assert.equal(state.world.stone,w.stone-2);
  assert.equal((await view()).structures[target.y*w.width+target.x],'workbench');
  const before=state.world.turns;
  await act('move',{dx:target.x-w.x,dy:target.y-w.y});
  assert.equal(state.world.turns,before);
}
async function partner(){
  const start=await send('start',{kind:'island',seed:4317,partner:true});
  const guard=start.actions.find(a=>a.id==='guard_player');
  assert.ok(guard);
  const next=await send('act',{session:start.session,action:'partner',id:guard.id});
  assert.equal(next.guardedBy,'试玩队友');
  assert.match(next.actorEvent,/保护|护在|抵挡|挡/);
}
async function relic(seed){
  let state=await send('start',{kind:'relic',difficulty:'EASY',weapon:'spear',profession:'MEDIC',seed});
  assert.equal(state.equipped,'spear');
  assert.equal(state.profession,'MEDIC');
  assert.ok(state.tiles.includes(-1));
  const act=async(action,args={})=>{state=await send('act',{session:state.session,action,...args});};
  let receipt;
  for(let iteration=0;iteration<130;iteration++){
    const s=state;
    if(s.phase==='SUCCESS'){
      if(!receipt)return false;
      assert.ok(s.secured.some(x=>x.id===receipt.id));
      const next=await send('view',{session:s.session});
      assert.deepEqual(next.secured,s.secured);
      const rejected=await send('act',{session:s.session,action:'wait'});
      assert.equal(rejected.turns,s.turns);
      return true;
    }
    if(s.phase!=='EXPLORE'&&s.phase!=='EXTRACT') return false;
    const {hero}=s;
    if(s.phase==='EXTRACT'){await act('wait');continue;}
    if(hero.hp<=5&&hero.kits>0){await act('heal');continue;}
    if(hero.hp<=6 && s.actions.some(a=>a.action==='profession_skill'&&a.enabled)){
      await act('profession_skill');continue;
    }
    const threat=s.mobs.find(m=>adjacent(hero,m));
    if(threat){
      await act('attack',threat);
      continue;
    }
    if(receipt && s.exitX>=0 && adjacent(hero,{x:s.exitX,y:s.exitY})){await act('extract');continue;}
    const chest=!receipt&&s.chests.find(c=>!c.opened&&adjacent(hero,c));
    if(chest){
      await act('open',chest);
      if(state.receipt){ assert.equal(state.receipt.inBag,true);receipt=state.receipt; }
      continue;
    }
    const walk=(x,y)=>[0,3].includes(s.tiles[y*s.w+x])&&!s.mobs.some(m=>m.x===x&&m.y===y)
      &&!s.sites.some(p=>p.kind==='trap'&&!p.spent&&p.x===x&&p.y===y);
    const realTarget=receipt
      ? p=>s.exitX>=0 && adjacent(p,{x:s.exitX,y:s.exitY})
      : p=>s.chests.some(c=>!c.opened&&adjacent(p,c));
    const explored=closest(hero,s.w,s.h,walk,realTarget);
    const next=explored?.step||closest(hero,s.w,s.h,walk,p=>directions.some(([dx,dy])=>{
      const x=p.x+dx,y=p.y+dy;
      return x>0&&x<s.w-1&&y>0&&y<s.h-1&&s.tiles[y*s.w+x]===-1;
    }))?.step;
    if(!next)return false;
    await act('sneak',{x:hero.x+next[0],y:hero.y+next[1]});
  }
  return false;
}
test('island gathers resources, builds a physical workbench and restores the same session',island);
test('a manually commanded teammate uses the official guard action',partner);
test('relic chest receipt becomes secured loot only after extraction',async()=>{
  assert.equal(await relic(4317),true);
});
