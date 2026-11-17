/* Lulu shared visual-world prototype engine. Browser-only fixture until an Android bridge is validated.
 * ORIGINAL: scenery and rules are local sample data, never the Android game's authoritative state. */
(function (root, factory) {
  const core = factory();
  if (typeof module === 'object' && module.exports) module.exports = core;
  root.LuluWorldCore = core;
})(typeof globalThis === 'object' ? globalThis : this, function () {
  'use strict';
  const WIDTH = 25, HEIGHT = 17;
  const labels = Object.freeze({meet:'见面 · 南岸小镇',island:'方块小岛',relic:'迷雾摸金'});
  function hash(seed){let t=(seed>>>0)||42;return function(){t=(t+0x6d2b79f5)>>>0;let x=t;x=Math.imul(x^(x>>>15),x|1);x^=x+Math.imul(x^(x>>>7),x|61);return ((x^(x>>>14))>>>0)/4294967296}}
  function prop(id,kind,x,y,solid=true){return {id,kind,x,y,solid,used:false}}
  function createWorld(kind, seed=1207){
    if(!Object.prototype.hasOwnProperty.call(labels,kind)) throw new Error('Unknown world');
    const random=hash(seed+(kind==='meet'?3:kind==='island'?11:29));
    const terrain=Array.from({length:WIDTH*HEIGHT},(_,i)=>{
      const x=i%WIDTH,y=Math.floor(i/WIDTH);
      if(x===0||x===WIDTH-1||y===0||y===HEIGHT-1)return 'border';
      if(kind==='relic')return random()<.075?'stone':'floor';
      if(kind==='island' && ((x>16&&y>9)||(x>19&&y>6)))return 'water';
      return 'grass';
    });
    // Walkable plaza and corridor persist irrespective of decorative seed.
    for(let y=2;y<14;y++) for(let x=2;x<8;x++)terrain[y*WIDTH+x]=kind==='relic'?'floor':kind==='meet'?'path':'grass';
    const props=[];
    if(kind==='meet'){
      props.push(prop('cafe','house',11,4),prop('bookshop','shop',18,5),prop('tree-east','tree',21,3),prop('tree-west','tree',3,12),prop('bench','bench',9,11,false),prop('friend','friend',6,7,false),prop('fountain','well',15,11));
      for(let x=3;x<22;x++)terrain[9*WIDTH+x]='path';
      for(let y=3;y<15;y++)terrain[y*WIDTH+7]='path';
    } else if(kind==='island'){
      props.push(prop('camp','camp',6,5,false),prop('tree-a','tree',12,7),prop('tree-b','tree',17,4),prop('tree-c','tree',20,8),prop('ore','ore',9,12),prop('bench','workbench',4,11),prop('friend','friend',7,6,false));
      for(let x=12;x<18;x++)terrain[12*WIDTH+x]='path';
    } else {
      for(let x=8;x<22;x++){terrain[4*WIDTH+x]='wall';terrain[12*WIDTH+x]='wall'}
      for(let y=4;y<13;y++){terrain[y*WIDTH+21]='wall'}
      for(let x of [11,16,19]){terrain[4*WIDTH+x]='floor'}
      for(let x of [9,14,18]){terrain[12*WIDTH+x]='floor'}
      props.push(prop('chest-a','chest',11,7),prop('chest-b','chest',19,10),prop('guardian','monster',15,8),prop('exit','exit',4,13,false),prop('friend','friend',7,6,false));
    }
    for(const p of props)terrain[p.y*WIDTH+p.x]=kind==='relic'?'floor':(kind==='meet'?'path':'grass');
    return {version:1,kind,seed,width:WIDTH,height:HEIGHT,terrain,props,player:{x:5,y:6},companion:{x:6,y:6,following:true},inventory:{wood:0,stone:0,relic:0},hp:12,turns:0,holding:false,finished:false,notes:[]};
  }
  function valid(state){return !!state&&state.version===1&&Object.prototype.hasOwnProperty.call(labels,state.kind)&&state.width===WIDTH&&state.height===HEIGHT&&Array.isArray(state.terrain)&&state.terrain.length===WIDTH*HEIGHT&&Array.isArray(state.props)&&Number.isInteger(state.player?.x)&&Number.isInteger(state.player?.y)}
  function inBounds(s,x,y){return x>0&&y>0&&x<s.width-1&&y<s.height-1}
  function blocked(s,x,y){if(!inBounds(s,x,y))return true;const t=s.terrain[y*s.width+x];return ['border','water','wall'].includes(t)||s.props.some(p=>p.solid&&!p.used&&p.x===x&&p.y===y)}
  function nearest(s,range=1){return s.props.filter(p=>!p.used&&Math.abs(p.x-s.player.x)+Math.abs(p.y-s.player.y)<=range).sort((a,b)=>Math.abs(a.x-s.player.x)+Math.abs(a.y-s.player.y)-Math.abs(b.x-s.player.x)-Math.abs(b.y-s.player.y))[0]||null}
  function walk(s,dx,dy){if(!Number.isInteger(dx)||!Number.isInteger(dy)||Math.abs(dx)+Math.abs(dy)!==1)return {ok:false,message:'无效方向'};const x=s.player.x+dx,y=s.player.y+dy;if(blocked(s,x,y))return {ok:false,message:'前面有障碍，试试绕开'};const old={...s.player};s.player={x,y};s.turns++;if(s.companion.following)s.companion={...old,following:true};return {ok:true,message:''}}
  function record(s,message){s.notes=[message,...s.notes].slice(0,8);return {ok:true,message}}
  function use(s,id){const p=s.props.find(o=>o.id===id&&!o.used);if(!p)return {ok:false,message:'这里没有可互动目标'};if(Math.abs(p.x-s.player.x)+Math.abs(p.y-s.player.y)>1)return {ok:false,message:'先走近目标，再互动'};
    if(p.kind==='friend'){s.holding=!s.holding;s.companion.following=s.holding;return record(s,s.holding?'牵手成功 · 演示同伴会跟着走':'已松开手 · 你可以独自探索')}
    if(s.kind==='meet'){if(p.kind==='house'||p.kind==='shop')return record(s,`来到了${p.kind==='house'?'咖啡店':'书店'}门口；内部地图仍在制作`);return record(s,'看见了街边的小景物')}
    if(s.kind==='island'){
      if(p.kind==='tree'){p.used=true;s.inventory.wood++;return record(s,'采到 1 份木材，树木已真实消失（试玩存档）')}
      if(p.kind==='ore'){p.used=true;s.inventory.stone++;return record(s,'采得 1 份矿石')}
      if(p.kind==='workbench'){if(s.inventory.wood<2)return {ok:false,message:'工作台需要至少 2 木材才能试建'};s.inventory.wood-=2;p.kind='house';return record(s,'在工作台位置搭起了小屋模型（独立试玩）')}
      return record(s,'查看了营地')
    }
    if(s.kind==='relic'){
      if(p.kind==='monster'){s.hp=Math.max(0,s.hp-1);p.used=true;return record(s,`击退了遗迹守卫，生命 ${s.hp}/12`)}
      if(p.kind==='chest'){p.used=true;s.inventory.relic++;return record(s,`打开宝箱，获得宝物 ×1 · 当前 ${s.inventory.relic} 件`)}
      if(p.kind==='exit'){if(s.inventory.relic<1)return {ok:false,message:'至少找到一件宝物才撤离'};s.finished=true;return record(s,`撤离成功 · 带出 ${s.inventory.relic} 件宝物（试玩，不写入 App）`)}
    }
    return {ok:false,message:'当前没有对应动作'};
  }
  function route(s,x,y){if(blocked(s,x,y))return [];const start=s.player.y*s.width+s.player.x,end=y*s.width+x,queue=[start],parent=new Map([[start,-1]]);for(let i=0;i<queue.length&&queue.length<450;i++){const at=queue[i];if(at===end)break;const ox=at%s.width,oy=(at/s.width)|0;for(const [dx,dy] of [[0,-1],[1,0],[0,1],[-1,0]]){const nx=ox+dx,ny=oy+dy,ni=ny*s.width+nx;if(!parent.has(ni)&&!blocked(s,nx,ny)){parent.set(ni,at);queue.push(ni)}}}if(!parent.has(end))return [];let out=[];for(let at=end;at!==start;at=parent.get(at))out.push({x:at%s.width,y:(at/s.width)|0});return out.reverse()}
  return Object.freeze({labels,createWorld,valid,walk,blocked,nearest,use,route});
});
