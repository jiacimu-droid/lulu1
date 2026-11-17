const { test }=require('node:test');
const assert=require('node:assert/strict');
const Core=require('../world-core.js');
test('all scenes use same world dimensions, rendering inputs and rules facade',()=>{
 const states=['meet','island','relic'].map(k=>Core.createWorld(k,40));
 states.forEach(s=>{assert.equal(Core.valid(s),true);assert.equal(s.terrain.length,s.width*s.height);assert.equal(s.player.x,5)});
 assert.notDeepEqual(states[0].terrain,states[2].terrain);
});
test('walking cannot cross walls, trees or water, nor mutate turns on failure',()=>{
 let s=Core.createWorld('island',42);s.player={x:1,y:1};
 assert.equal(Core.walk(s,-1,0).ok,false);assert.equal(s.turns,0);
 s.player={x:15,y:12};s.terrain[12*s.width+16]='water';
 assert.equal(Core.walk(s,1,0).ok,false);assert.equal(s.turns,0);
});
test('walking uses same camera-space logical positions regardless of scene',()=>{
 for(const kind of ['meet','island','relic']){const s=Core.createWorld(kind,42);const before=s.turns;assert.equal(Core.walk(s,0,1).ok,true);assert.equal(s.turns,before+1)}
});
test('interaction requires actual adjacent target and does not fabricate model reply',()=>{
 const s=Core.createWorld('island');assert.equal(Core.use(s,'tree-a').ok,false);assert.equal(s.inventory.wood,0);
 s.player={x:11,y:7};assert.equal(Core.use(s,'tree-a').ok,true);assert.equal(s.inventory.wood,1);assert.equal(s.props.find(p=>p.id==='tree-a').used,true);
 assert.equal(Core.use(s,'tree-a').ok,false);assert.equal(s.inventory.wood,1);
});
test('meeting handholding only toggles upon actual interaction',()=>{
 const s=Core.createWorld('meet');s.player={x:5,y:7};assert.equal(s.holding,false);assert.equal(s.companion.following,false);assert.equal(Core.use(s,'friend').ok,true);assert.equal(s.holding,true);
 const before={...s.player};const oldFollow={...s.companion};Core.walk(s,0,1);assert.deepEqual(s.companion,{...before,following:true});assert.notDeepEqual(s.companion,oldFollow)
});
test('relic cannot extract loot before opening a physical chest',()=>{
 const s=Core.createWorld('relic');s.player={x:4,y:12};assert.equal(Core.use(s,'exit').ok,false);assert.equal(s.finished,false);
 s.player={x:11,y:6};assert.equal(Core.use(s,'chest-a').ok,true);assert.equal(s.inventory.relic,1);
 s.player={x:4,y:12};assert.equal(Core.use(s,'exit').ok,true);assert.equal(s.finished,true)
});
test('valid data snapshot remains same world across serialization',()=>{
 const world=Core.createWorld('meet');world.player={x:8,y:7};const persisted=JSON.parse(JSON.stringify(world));assert.equal(Core.valid(persisted),true);assert.deepEqual(persisted.player,world.player)
});
test('route never cuts through an obstacle',()=>{
 const s=Core.createWorld('island');const r=Core.route(s,7,8);assert.ok(r.length>0);for(const p of r) assert.equal(Core.blocked(s,p.x,p.y),false)
});

test('corrupt persisted island/relic snapshots are rejected before any render or action',()=>{
 for(const kind of ['island','relic']){
  const original=Core.createWorld(kind,42);
  const mutate=(fn)=>{const saved=JSON.parse(JSON.stringify(original));fn(saved);assert.equal(Core.valid(saved),false)};
  mutate(s=>{delete s.inventory});
  mutate(s=>{s.inventory.wood=-2});
  mutate(s=>{s.props[0].x=999});
  mutate(s=>{s.props[0].used='false'});
  mutate(s=>{s.terrain[0]=null});
  mutate(s=>{s.companion=null});
  mutate(s=>{s.player.y=999});
  mutate(s=>{s.props.push({...s.props[0]})});
  assert.equal(Core.valid(JSON.parse(JSON.stringify(original))),true);
 }
});

test('relic extraction seals the authoritative preview snapshot against direct actions and reload exploits',()=>{
 const s=Core.createWorld('relic',42);
 s.player={x:11,y:6};
 assert.equal(Core.use(s,'chest-a').ok,true);
 s.player={x:4,y:12};
 assert.equal(Core.use(s,'exit').ok,true);
 const restored=JSON.parse(JSON.stringify(s));
 assert.equal(Core.valid(restored),true);
 const before=JSON.stringify(restored);
 assert.equal(Core.walk(restored,0,1).ok,false);
 restored.player={x:19,y:9};
 const beforeInteraction=JSON.stringify(restored);
 assert.equal(Core.use(restored,'chest-b').ok,false);
 assert.equal(JSON.stringify(restored),beforeInteraction);
 assert.equal(restored.inventory.relic,1);
 assert.equal(restored.finished,true);
 assert.equal(s.finished,true);
 assert.equal(before.includes('"finished":true'),true);
});

test('persisted worlds reject blocked player spawns and occupied solid props before gameplay',()=>{
 for(const kind of ['island','relic']){
  const base=Core.createWorld(kind,42);
  const clone=()=>JSON.parse(JSON.stringify(base));
  const wall=clone();wall.terrain[wall.player.y*wall.width+wall.player.x]='wall';
  assert.equal(Core.valid(wall),false,'player cannot reload inside a wall');
  const flooded=clone();flooded.terrain[flooded.companion.y*flooded.width+flooded.companion.x]='water';
  assert.equal(Core.valid(flooded),false,'companion cannot reload in water');
  const occupied=clone();occupied.props.push({id:'occupied-spawn',kind:'tree',x:occupied.player.x,y:occupied.player.y,solid:true,used:false});
  assert.equal(Core.valid(occupied),false,'solid live prop cannot overlap player');
  assert.equal(Core.valid(base),true,'unmodified world still resumes');
 }
});

test('town buildings block their entire visible footprint, not just the anchor',()=>{
 const scene=Core.createWorld('meet',813);
 const house=scene.props.find(p=>p.id==='cafe');
 assert.deepEqual(Core.footprint(house),{left:10,right:12,top:2,bottom:4});
 for(let y=2;y<=4;y++)for(let x=10;x<=12;x++)
   assert.equal(Core.blocked(scene,x,y),true,'building footprint '+x+','+y);
 assert.equal(Core.blocked(scene,11,5),false,'front-door approach stays open');
 assert.equal(Core.at(scene,10,3)?.id,'cafe');
 const route=Core.route(scene,11,5);
 assert.ok(route.length>0,'door remains reachable');
 assert.ok(route.every(p=>!Core.blocked(scene,p.x,p.y)),'route never cuts a house');
 scene.player={x:11,y:5};
 assert.equal(Core.nearest(scene,1)?.id,'cafe');
 assert.equal(Core.use(scene,'cafe').ok,true,'interaction is from outside');
 scene.player={x:10,y:3};
 assert.equal(Core.valid(scene),false,'old saved state inside house is rejected');
});
test('building a shelter cannot trap player or companion inside its new footprint',()=>{
 const scene=Core.createWorld('island',813);
 scene.inventory.wood=2;
 scene.player={x:3,y:11};
 assert.equal(Core.use(scene,'bench').ok,false);
 assert.equal(scene.inventory.wood,2);
 assert.equal(scene.props.find(p=>p.id==='bench').kind,'workbench');
 scene.player={x:4,y:12};
 assert.equal(Core.use(scene,'bench').ok,true);
 assert.equal(scene.inventory.wood,0);
 assert.equal(Core.blocked(scene,4,10),true);
 assert.equal(Core.blocked(scene,4,12),false);
 assert.equal(Core.valid(scene),true,'constructed house preserves valid save');
});

test('island harvests physical trees into a shelter that survives reload without duplicating wood',()=>{
 const s=Core.createWorld('island',813);
 for(const id of ['tree-a','tree-b']){
  const tree=s.props.find(p=>p.id===id);
  const options=Core.approaches(s,tree);
  const candidates=options.map(p=>Core.route(s,p.x,p.y)).filter(route=>route.length);
  assert.ok(candidates.length,'tree must be reachable: '+id);
  const route=candidates.sort((a,b)=>a.length-b.length)[0];
  for(const step of route){
   const dx=step.x-s.player.x,dy=step.y-s.player.y;
   assert.equal(Core.walk(s,dx,dy).ok,true,'real walk to '+id);
  }
  assert.equal(Core.use(s,id).ok,true,'harvest actual tree '+id);
 }
 assert.equal(s.inventory.wood,2,'wood comes only from two removed trees');
 const bench=s.props.find(p=>p.id==='bench');
 const approaches=Core.approaches(s,{...bench,kind:'house'});
 const paths=approaches.map(p=>Core.route(s,p.x,p.y)).filter(route=>route.length);
 assert.ok(paths.length,'shelter entrance must be reachable');
 for(const step of paths.sort((a,b)=>a.length-b.length)[0]){
  assert.equal(Core.walk(s,step.x-s.player.x,step.y-s.player.y).ok,true);
 }
 assert.equal(Core.use(s,'bench').ok,true,'construct at the real workbench');
 assert.equal(s.inventory.wood,0,'construction consumes the harvested wood');
 assert.equal(bench.kind,'house');
 const saved=JSON.parse(JSON.stringify(s));
 assert.equal(Core.valid(saved),true,'completed shelter can be restored');
 assert.equal(saved.props.find(p=>p.id==='bench').kind,'house');
 for(const id of ['tree-a','tree-b'])assert.equal(Core.use(saved,id).ok,false,'no duplicate harvest after reload');
 assert.equal(saved.inventory.wood,0);
});
