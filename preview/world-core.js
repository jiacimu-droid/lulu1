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
    return {version:1,kind,seed,width:WIDTH,height:HEIGHT,terrain,props,player:{x:5,y:6},companion:{x:6,y:6,following:kind!=='meet'},inventory:{wood:0,stone:0,relic:0},hp:12,turns:0,holding:false,finished:false,notes:[]};
  }
  function valid(state){
    const point=p=>p&&Number.isInteger(p.x)&&Number.isInteger(p.y)&&p.x>=0&&p.y>=0&&p.x<WIDTH&&p.y<HEIGHT;
    const count=n=>Number.isSafeInteger(n)&&n>=0;
    return !!(state&&state.version===1&&Object.prototype.hasOwnProperty.call(labels,state.kind)&&
      state.width===WIDTH&&state.height===HEIGHT&&
      Array.isArray(state.terrain)&&state.terrain.length===WIDTH*HEIGHT&&
      state.terrain.every(t=>typeof t==='string'&&['border','water','wall','stone','floor','grass','path'].includes(t))&&
      Array.isArray(state.props)&&state.props.length<=WIDTH*HEIGHT&&
      state.props.every(p=>point(p)&&typeof p.id==='string'&&typeof p.kind==='string'&&typeof p.solid==='boolean'&&typeof p.used==='boolean')&&
      new Set(state.props.map(p=>p.id)).size===state.props.length&&
      point(state.player)&&point(state.companion)&&
      !['border','water','wall'].includes(state.terrain[state.player.y*WIDTH+state.player.x])&&
      !['border','water','wall'].includes(state.terrain[state.companion.y*WIDTH+state.companion.x])&&
      !state.props.some(p=>occupies(p,state.player.x,state.player.y)||occupies(p,state.companion.x,state.companion.y))&&
      typeof state.companion.following==='boolean'&&
      state.inventory&&count(state.inventory.wood)&&count(state.inventory.stone)&&count(state.inventory.relic)&&
      count(state.turns)&&count(state.hp)&&typeof state.finished==='boolean'&&typeof state.holding==='boolean'&&
      Array.isArray(state.notes));
  }
  // Visual props with a wide sprite must block their WHOLE physical footprint.
  // Anchor-only collision made players and auto-paths walk through buildings.
  function footprint(p){
    if(p.kind==='house'||p.kind==='shop')return {left:p.x-1,right:p.x+1,top:p.y-2,bottom:p.y};
    if(p.kind==='well')return {left:p.x,right:p.x+1,top:p.y-1,bottom:p.y};
    return {left:p.x,right:p.x,top:p.y,bottom:p.y};
  }
  function occupies(p,x,y){
    if(p.used||!p.solid)return false;
    const b=footprint(p);
    return x>=b.left&&x<=b.right&&y>=b.top&&y<=b.bottom;
  }
  function distance(p,x,y){
    const b=footprint(p);
    return Math.max(b.left-x,0,x-b.right)+Math.max(b.top-y,0,y-b.bottom);
  }
  function inBounds(s,x,y){return x>0&&y>0&&x<s.width-1&&y<s.height-1}
  function blocked(s,x,y){if(!inBounds(s,x,y))return true;const t=s.terrain[y*s.width+x];return ['border','water','wall'].includes(t)||s.props.some(p=>occupies(p,x,y))}
  function nearest(s,range=1){return s.props.filter(p=>!p.used&&distance(p,s.player.x,s.player.y)<=range).sort((a,b)=>distance(a,s.player.x,s.player.y)-distance(b,s.player.x,s.player.y))[0]||null}
  function at(s,x,y){return s.props.find(p=>!p.used&&(occupies(p,x,y)||(p.x===x&&p.y===y)))||null}
  function approaches(s,p){
    const b=footprint(p),out=[];
    for(let y=b.top-1;y<=b.bottom+1;y++)for(let x=b.left-1;x<=b.right+1;x++)
      if(distance(p,x,y)===1&&!blocked(s,x,y))out.push({x,y});
    return out;
  }
  function walk(s,dx,dy){if(s.finished)return {ok:false,message:'本局已结算，请重新开始'};if(!Number.isInteger(dx)||!Number.isInteger(dy)||Math.abs(dx)+Math.abs(dy)!==1)return {ok:false,message:'无效方向'};const x=s.player.x+dx,y=s.player.y+dy;if(blocked(s,x,y))return {ok:false,message:'前面有障碍，试试绕开'};const old={...s.player};s.player={x,y};s.turns++;if(s.companion.following)s.companion={...old,following:true};return {ok:true,message:''}}
  function record(s,message){s.notes=[message,...s.notes].slice(0,8);return {ok:true,message}}
  function use(s,id){if(s.finished)return {ok:false,message:'本局已结算，请重新开始'};const p=s.props.find(o=>o.id===id&&!o.used);if(!p)return {ok:false,message:'这里没有可互动目标'};if(distance(p,s.player.x,s.player.y)>1)return {ok:false,message:'先走近目标，再互动'};
    if(p.kind==='friend'){s.holding=!s.holding;s.companion={x:p.x,y:p.y,following:s.holding};return record(s,s.holding?'牵手成功 · 演示同伴会跟着走':'已松开手 · 你可以独自探索')}
    if(s.kind==='meet'){if(p.kind==='house'||p.kind==='shop')return record(s,`来到了${p.kind==='house'?'咖啡店':'书店'}门口；内部地图仍在制作`);return record(s,'看见了街边的小景物')}
    if(s.kind==='island'){
      if(p.kind==='tree'){p.used=true;s.inventory.wood++;return record(s,'采到 1 份木材，树木已真实消失（试玩存档）')}
      if(p.kind==='ore'){p.used=true;s.inventory.stone++;return record(s,'采得 1 份矿石')}
      if(p.kind==='workbench'){if(s.inventory.wood<2)return {ok:false,message:'工作台需要至少 2 木材才能试建'};const built={...p,kind:'house'};if(occupies(built,s.player.x,s.player.y)||occupies(built,s.companion.x,s.companion.y))
        return {ok:false,message:'施工区域被人物占用，请先走到工作台南侧再建造'};
        if(approaches(s,built).length===0)return {ok:false,message:'施工位置无可用入口'};
        s.inventory.wood-=2;p.kind='house';return record(s,'在工作台位置搭起了小屋模型（独立试玩）')}
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
  return Object.freeze({labels,createWorld,valid,walk,blocked,nearest,at,approaches,footprint,distance,use,route});
});
