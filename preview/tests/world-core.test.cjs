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
