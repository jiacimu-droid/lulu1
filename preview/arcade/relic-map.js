import { chestFrames, reducedMotion } from './chest-show.js';

const grades = ['#b9bbb2','#b8c9a1','#83bde0','#b3a7e0','#e3b875','#e69082','#f0eace'];
const palette = ['#3a4147','#48515a','#354b46','#65564a'];
const clamp = (n, a, b) => Math.min(Math.max(n,a),b);
const tileHash = (x,y) => ((x*2767 ^ y*1859) >>> 0) % 29 / 29;
const rect = (c, fill, x,y,w,h) => { c.fillStyle=fill; c.fillRect(x,y,w,h); };
function poly(c, fill, points) {
  c.beginPath();
  points.forEach(([x,y],i)=>i?c.lineTo(x,y):c.moveTo(x,y));
  c.closePath();c.fillStyle=fill;c.fill();
}
function ellipse(c, fill, x,y,rx,ry) {
  c.beginPath(); c.ellipse(x,y,rx,ry,0,0,Math.PI*2);
  c.fillStyle=fill; c.fill();
}
function glow(c, x,y,r, color) {
  const g=c.createRadialGradient(x,y,0,x,y,r);
  g.addColorStop(0,color);g.addColorStop(1,'transparent');
  rect(c,g,x-r,y-r,r*2,r*2);
}
function gem(c,x,y,k, tone) {
  poly(c,tone,[[x,y-k],[x+k*.68,y-k*.22],[x+k*.45,y+k*.57],[x,y+k],[x-k*.61,y+k*.17],[x-k*.68,y-k*.24]]);
  poly(c,'#fffae792',[[x,y-k],[x+k*.68,y-k*.22],[x,y-k*.02],[x-k*.68,y-k*.24]]);
  poly(c,'#14283175',[[x,y-k*.02],[x+k*.68,y-k*.22],[x+k*.45,y+k*.57],[x,y+k]]);
}
function pawn(c,x,y,s,coat,hood,down,flash=false) {
  if(flash)coat=hood="#fff6eb";
  ellipse(c,'#13232a9a',x,y+s*.22,s*.28,s*.11);
  if(down){ellipse(c,coat,x,y,s*.26,s*.12);return;}
  poly(c,coat,[[x-s*.14,y-s*.18],[x+s*.14,y-s*.18],[x+s*.24,y+s*.20],[x,y+s*.26],[x-s*.25,y+s*.16]]);
  poly(c,'#111b2739',[[x,y-s*.16],[x+s*.14,y-s*.18],[x+s*.24,y+s*.20],[x,y+s*.26]]);
  ellipse(c,hood,x,y-s*.21,s*.157,s*.15);
  ellipse(c,flash?'#ffffff':'#e6be9b',x,y-s*.18,s*.091,s*.077);
  rect(c,flash?'#fff6eb':'#bcaa82',x-s*.18,y+s*.02,s*.33,s*.043);
}
function chest(c,x,y,s,tone,open,model) {
  ellipse(c,'#111c24a8',x,y+s*.19,s*.35,s*.13);
  if(!open) glow(c,x,y,s*.85,tone+'39');
  if(model?.complete&&model.naturalWidth){
    c.drawImage(model,x-s*.63,y-s*.58,s*1.25,s*1.14);
    if(!open)gem(c,x+s*.026,y+s*.068,s*.045,tone);
    return;
  }
  poly(c,'#443829',[[x-s*.30,y],[x+s*.30,y],[x+s*.27,y+s*.19],[x-s*.27,y+s*.19]]);
  poly(c,open?'#4c534e':'#776043',[[x-s*.33,y],[x-s*.22,y-s*(open?.25:.16)],[x+s*.24,y-s*(open?.25:.16)],[x+s*.32,y]]);
  rect(c,tone,x-s*.32,y-s*.025,s*.64,s*.042);
  if(!open) gem(c,x,y+s*.055,s*.08,tone);
}
function gate(c,x,y,s) {
  glow(c,x,y,s*1.2,'#7fbcb365');
  c.beginPath(); c.moveTo(x-s*.35,y+s*.25);c.lineTo(x-s*.35,y-s*.08);
  c.arc(x,y-s*.08,s*.35,Math.PI,0);c.lineTo(x+s*.35,y+s*.25);c.closePath();
  c.fillStyle='#213c41';c.fill();c.strokeStyle='#9cb5aa';c.lineWidth=s*.072;c.stroke();
  for(let i=-2;i<=2;i++) rect(c,'#81d0c4a3',x+i*s*.099,y-s*.19,s*.021,s*.37);
}
function guard(c,x,y,s,m,flash=false) {
  ellipse(c,'#19232bb2',x,y+s*.22,s*.31,s*.11);
  poly(c,flash?'#fff5e2':m.windup?'#a86758':'#67565f',[[x,y-s*.36],[x+s*.25,y-s*.12],[x+s*.18,y+s*.23],[x-s*.23,y+s*.18],[x-s*.27,y-s*.09]]);
  ellipse(c,flash?'#fff5e2':'#302a32',x,y-s*.13,s*.15,s*.12);
  for(const offset of [-.048,.048]) rect(c,m.windup?'#ffd18b':'#e5a88c',x+offset*s,y-s*.166,s*.031,s*.028);
  rect(c,'#1c2731',x-s*.22,y-s*.44,s*.44,s*.055);
  rect(c,'#db9276',x-s*.22,y-s*.44,s*.44*Math.min(m.hp/12,1),s*.055);
}

function strikeVector(hit) {
  const dx=hit.x-(hit.sourceX??hit.x),dy=hit.y-(hit.sourceY??hit.y);
  const length=Math.hypot(dx,dy);
  return length?[dx/length,dy/length]:[0,0];
}

function drawContact(c,hit,at,size,elapsed,reduced) {
  const [x,y]=at, [dx,dy]=strikeVector(hit);
  const age=clamp(elapsed/350,0,1);
  const color=hit.style==='block'?'#b2e7f6':hit.style==='enemy'?'#ff9c94':'#ffe4a8';
  if(!reduced){
    const angle=Math.atan2(dy,dx);
    const arc=hit.style==='blade'||hit.style==='sweep'||hit.style==='enemy';
    const slash=clamp(1-elapsed/145,0,1);
    if(slash>0){
      c.save();c.translate(x,y);c.rotate(angle);c.globalAlpha=slash*.88;
      c.strokeStyle=color;c.lineWidth=size*.05;c.lineCap='round';
      c.beginPath();
      if(arc){
        c.ellipse(-size*.26,0,size*.46,size*.48,0,-1.13,.98);
      } else if(hit.style==='block'){
        c.arc(0,0,size*.26,-1.25,1.25);
      } else {
        c.moveTo(-size*(hit.style==='bow'?1.12:.67),size*.03);c.lineTo(size*.18,0);
      }
      c.stroke();c.restore();
    }
    c.globalAlpha=(1-age)*.82;
    const count=hit.defeated?12:hit.damage>=3?9:6;
    for(let i=0;i<count;i++){
      const direction=angle+(i-(count-1)/2)*.33;
      const rayX=Math.cos(direction),rayY=Math.sin(direction);
      const distance=size*(.07+age*(.26+i%4*.13));
      const px=x+rayX*distance,py=y+rayY*distance+age*age*size*.12;
      c.strokeStyle=i%3===0?'#fff6df':color;
      c.lineWidth=1.15+i%2;
      c.beginPath();c.moveTo(px,py);
      c.lineTo(px+rayX*size*.095*(1-age),py+rayY*size*.095*(1-age));c.stroke();
    }
  }
  c.globalAlpha=clamp((1-age)*1.5,0,1);
  c.textAlign='center';c.font='800 '+Math.max(13,Math.min(25,size*.42))+'px system-ui';
  c.lineWidth=3;c.strokeStyle='#151820';c.fillStyle=color;
  const label=hit.style==='block'?'格挡':'−'+hit.damage;
  const rise=reduced?0:age*size*.36;
  c.strokeText(label,x,y-size*.58-rise);c.fillText(label,x,y-size*.58-rise);
  c.globalAlpha=1;
}

function stoneTile(c,tile,x,y,s,col,row) {
  const n=tileHash(col,row), warm=col>=16||row<=3, moss=tile===2||row>=11;
  if(tile===1){
    rect(c,'#1d252e',x,y,s,s);
    const cap=c.createLinearGradient(x,y,x,y+s*.81);
    cap.addColorStop(0,warm?'#756458':'#5d6870');
    cap.addColorStop(.8,warm?'#514641':'#3d4953');
    rect(c,cap,x+1,y,s-2,s*.82);
    rect(c,'#a0a6a129',x+1,y,s-2,2);
    const courses=4;
    for(let r=0;r<courses;r++){
      const line=y+1+r*s*.20, offset=(r%2)*s*.25;
      rect(c,'#141b23a0',x,line+s*.18,s,1);
      for(let k=0;k<3;k++){
        const joint=offset+k*s*.36;
        if(joint<s-1)rect(c,'#19232b93',x+joint,line,1,s*.19);
        if(joint+2<s-1)rect(c,'#d9cbb82c',x+joint+2,line+1,Math.min(s*.34,s-joint-2),1);
      }
    }
    rect(c,'#222930',x,y+s*.80,s,s*.20);
    if(n>.52) {
      rect(c,'#657d5c5f',x+s*.64,y+s*.59,s*.26,s*.19);
      rect(c,'#7f93623c',x+s*.73,y+s*.55,s*.16,s*.21);
    }
    return;
  }
  const slab=warm ? ['#4e4746','#51494a','#544b49'] : moss ? ['#343f43','#3d4844','#454a42'] : ['#414952','#434a51','#3c4650'];
  rect(c,tile===3?'#625348':slab[Math.floor(n*3)],x,y,s+.3,s+.3);
  for(let j=0;j<3;j++){
    const fy=y+s*j/3;
    rect(c,'#19243190',x,fy+s*.315,s,1);
    rect(c,'#d3c7ab24',x,fy+1,s,1);
    rect(c,'#17223268',x+(j%2?.35:.65)*s,fy,1,s*.33);
  }
  for(let j=0;j<7;j++){
    const dx=tileHash(col+j*13,row+.1),dy=tileHash(row+j*27,col+.7);
    rect(c,j%3?'#171c252a':'#d4c6aa26',x+dx*s*.87,y+dy*s*.9,1+dx*3,1+dy*2);
  }
  if(tile===3){
    poly(c,'#8c746055',[[x+3,y+s*.44],[x+s*.69,y+2],[x+s*.73,y+s*.37],[x+s*.92,y+s*.70]]);
    c.strokeStyle='#251e288d';c.beginPath();c.moveTo(x+s*.14,y+s*.69);
    c.lineTo(x+s*.35,y+s*.61);c.lineTo(x+s*.46,y+s*.26);c.lineTo(x+s*.83,y+s*.16);c.stroke();
  }
}
function lantern(c,x,y,s,tone){
  glow(c,x,y,s*1.06,tone+'61');
  ellipse(c,'#22232b91',x,y+s*.13,s*.21,s*.077);
  rect(c,'#473a38',x-s*.12,y+s*.10,s*.24,s*.08);
  poly(c,'#a48b57',[[x-s*.14,y-s*.14],[x+s*.14,y-s*.14],[x+s*.09,y+s*.14],[x-s*.09,y+s*.14]]);
  poly(c,tone,[[x-s*.06,y-s*.11],[x+s*.06,y-s*.11],[x+s*.05,y+s*.08],[x-s*.05,y+s*.08]]);
  rect(c,'#ffe2accc',x-s*.019,y-s*.117,s*.038,s*.16);
}

export function relicMap(canvas) {
  const c=canvas.getContext('2d',{alpha:false});
  let view, frame=0, focus=null, impact=null, lastArgs;
  const boxes=[0,8].map(index=>{
    const image=new Image();image.src=chestFrames[index];return image;
  });
  const relayout=()=>{if(lastArgs)map.paint(...lastArgs);};
  for(const img of boxes)img.addEventListener('load',relayout);
  function animate(){
    if(!frame)frame=requestAnimationFrame(()=>{
      frame=0;
      if(lastArgs)map.paint(...lastArgs);
    });
  }
  const map={
    hit(before,hits){
      if(!hits.length)return;
      const heavy=hits.some(hit=>hit.defeated||hit.target==='player'||hit.style==='sweep');
      impact={before,hits:hits.slice(-12),start:performance.now(),pause:heavy?63:42,heavy};
    },
    clear(){
      if(frame)cancelAnimationFrame(frame);
      frame=0;focus=impact=lastArgs=null;
    },
    paint(s, selected, camera, overview, shot) {
      lastArgs=[s,selected,camera,overview,shot];
      const width=canvas.clientWidth, height=canvas.clientHeight;
      if(!width || !height)return;
      const dpr=Math.min(devicePixelRatio||1,1.75);
      if(canvas.width!==Math.round(width*dpr)||canvas.height!==Math.round(height*dpr)){canvas.width=Math.round(width*dpr);canvas.height=Math.round(height*dpr);}
      c.setTransform(dpr,0,0,dpr,0,0);
      const now=performance.now(), reduced=reducedMotion.matches;
      if(impact&&now-impact.start>375)impact=null;
      const elapsed=impact?now-impact.start:0;
      const hits=impact?.hits||[];
      // Briefly hold the previous visual pose. The committed world and its input stay live.
      const pose=impact&&!reduced&&elapsed<impact.pause?impact.before:s;
      const regular=clamp(Math.min(width/10,height/6.5),34,76);
      if(shot?.id!==focus?.box?.id){
        const previous=focus?.box;
        focus={box:shot,anchor:shot||previous,started:now};
      }
      const transition=focus?(reduced?1:clamp((now-focus.started)/310,0,1)):1;
      const settle=1-(1-transition)**3, push=focus?.anchor?(shot?settle:1-settle):0;
      const anchor=focus?.anchor||s.hero;
      const size=regular*(1+push*.37), hero=pose.hero;
      const aimX=s.hero.x+(anchor.x-s.hero.x)*push*.92;
      const aimY=s.hero.y+(anchor.y-s.hero.y)*push*.84;
      const recoil=impact&&!reduced?(1-clamp(elapsed/190,0,1))**2*(impact.heavy?5.4:2.9):0;
      const baseX=width*.50-(aimX+.5)*size, baseY=height*(.48+.07*push)-(aimY+.5)*size;
      const ox=baseX+Math.cos(elapsed*.12)*recoil, oy=baseY+Math.sin(elapsed*.18)*recoil*.64;
      view={size,ox:baseX,oy:baseY,w:s.w,h:s.h};
      if(impact||transition<1)animate();
      const air=c.createRadialGradient(width*.5,height*.45,4,width*.5,height*.45,Math.max(width,height)*.7);
      air.addColorStop(0,'#253944');air.addColorStop(.48,'#172630');air.addColorStop(1,'#101823');
      rect(c,air,0,0,width,height);
      for(let i=0;i<83;i++){
        const px=width*tileHash(i,i*19+9),py=height*tileHash(i*13,i+37);
        ellipse(c,i%5===0?'#a6b4ae22':'#91a9a01c',px,py,i%5===0?1.5:1,1);
      }
      const centre=(x,y)=>[ox+(x+.5)*size,oy+(y+.5)*size];
      for(let y=0;y<s.h;y++) for(let x=0;x<s.w;x++) {
        const tile=s.tiles[y*s.w+x];
        if(tile<0) continue;
        const tx=ox+x*size, ty=oy+y*size;
        if(tx+size<0||ty+size<0||tx>width||ty>height)continue;
        stoneTile(c,tile,tx,ty,size,x,y);
      }
      if(s.w===23) {
        for(const [x,y,tone] of [[2,2,'#ebba75'],[6,3,'#e7a881'],[3,9,'#bbd0c4'],[11,5,'#eec78a'],[17,8,'#e9ab75'],[19,11,'#c5b3ec'],[6,12,'#89c3bb']]){
          if(s.tiles[y*s.w+x]<0)continue;
          lantern(c,...centre(x,y),size*.76,tone);
        }
        for(const [x,y] of [[4,5],[12,5],[20,7],[20,10]]){
          if(s.tiles[y*s.w+x]<0)continue;
          const [cx,cy]=centre(x,y);
          ellipse(c,'#b2996550',cx,cy,size*.39,size*.40);
          ellipse(c,'#332c32a5',cx,cy,size*.32,size*.33);
          gem(c,cx,cy,size*.14,'#b6966d6d');
        }
      }
      if(s.tiles[2*s.w+2]>=0){
        const [cx,cy]=centre(2,2);
        c.beginPath();c.arc(cx,cy,size*.34,0,Math.PI*2);c.strokeStyle='#a5b9ad40';c.lineWidth=1.3;c.stroke();
        poly(c,'#c7bba268',[[cx,cy-size*.19],[cx+size*.11,cy],[cx,cy+size*.19],[cx-size*.11,cy]]);
      }
      const fog=c.createRadialGradient(width/2,height*.46,size*.48,width/2,height*.46,Math.max(width,height)*.68);
      fog.addColorStop(0,'#0b172500');fog.addColorStop(.72,'#0b17254a');fog.addColorStop(1,'#0b1725cd');
      rect(c,fog,0,0,width,height);
      for(const [x,y] of s.route){
        const [cx,cy]=centre(x,y);
        ellipse(c,'#c3d8b85f',cx,cy,size*.045,size*.045);
      }
      for(const m of s.mobs)if(m.windup){
        for(const [dx,dy] of [[0,0],[-1,0],[1,0],[0,-1],[0,1],...(m.kind>=2?[[-2,0],[2,0],[0,-2],[0,2]]:[])]){
          const x=m.x+dx,y=m.y+dy;
          if(x<0||x>=s.w||y<0||y>=s.h||![0,2,3].includes(s.tiles[y*s.w+x]))continue;
          const [cx,cy]=centre(x,y);
          rect(c,'#db79683c',cx-size/2+1,cy-size/2+1,size-2,size-2);
        }
      }
      if(s.exitX>=0) gate(c,...centre(s.exitX,s.exitY),size);
      for(const site of s.sites)if(!site.spent){
        const [x,y]=centre(site.x,site.y);
        const tone=({echo:'#b98c71',trap:'#cc8c77',fountain:'#84c9bc',lens:'#9bbfe1',seal_switch:'#c3b981',altar:'#c099c7'})[site.kind]||'#a8ab9a';
        ellipse(c,'#16282f',x,y+size*.12,size*.29,size*.11);
        gem(c,x,y,size*.18,tone);
      }
      for(const box of s.chests){
        c.globalAlpha=shot&&shot.id===box.id?Math.max(0,1-push*1.7):1;
        chest(c,...centre(box.x,box.y),size,grades[box.shellGrade??box.grade]||grades[0],box.opened,boxes[box.opened?1:0]);
      }
      c.globalAlpha=1;
      for(const drop of pose.drops) gem(c,...centre(drop.x,drop.y),size*.13,grades[drop.loot.grade]||grades[0]);
      function entity(target,tx,ty,draw){
        const hit=hits.find(event=>event.target===target&&event.damage>0);
        const [x,y]=centre(tx,ty);
        if(!hit||reduced||elapsed>=290){
          const swing=hits.find(event=>event.sourceX===tx&&event.sourceY===ty&&
            (target.startsWith('mob:')===(event.style==='enemy'||event.style==='block')));
          const lunge=swing&&!reduced?Math.sin(Math.PI*clamp(elapsed/150,0,1))*size*.17:0;
          const [dx,dy]=swing?strikeVector(swing):[0,0];
          draw(x+dx*lunge,y+dy*lunge,false);
          return;
        }
        const [dx,dy]=strikeVector(hit), t=clamp(elapsed/265,0,1);
        const bump=elapsed<(impact?.pause||0)?0:Math.sin(t*Math.PI)*(1-t);
        const strength=hit.defeated?.37:hit.target==='player'?.32:.25;
        const squash=(1-t)**3*.18, angle=Math.atan2(dy,dx);
        c.save();
        c.translate(x+size*bump*strength*dx,y+size*bump*strength*dy);
        c.rotate(angle);c.scale(1-squash,1+squash*.88);c.rotate(-angle);
        if(hit.defeated&&target.startsWith('mob:'))c.globalAlpha=1-clamp((elapsed-75)/190,0,1);
        draw(0,0,elapsed<72);
        c.restore();
      }
      const fallen=impact&&!reduced&&elapsed<265&&pose===s
        ?impact.before.mobs.filter(m=>hits.some(h=>h.defeated&&h.target==='mob:'+m.id)&&
          !s.mobs.some(current=>current.id===m.id)): [];
      for(const foe of [...pose.mobs,...fallen]) {
        const id='mob:'+foe.id, contact=hits.find(h=>h.target===id&&h.damage>0);
        const slide=contact&&!reduced?clamp((elapsed-180)/100,0,1):1;
        const tx=contact?contact.x+(foe.x-contact.x)*slide:foe.x;
        const ty=contact?contact.y+(foe.y-contact.y)*slide:foe.y;
        entity(id,tx,ty,(x,y,flash)=>guard(c,x,y,size,foe,flash));
        if(shot&&foe.alert>0&&!contact?.defeated) {
          const [fx,fy]=centre(foe.x,foe.y);
          const edgeX=clamp(fx,20,width-20),edgeY=clamp(fy,20,height-20);
          const threatening=foe.windup?'#ffd29d':'#e69f81';
          c.font='600 '+Math.max(11,Math.min(size*.27,17))+'px system-ui';c.textAlign='center';
          c.strokeStyle='#25222a';c.lineWidth=4;
          const tag=foe.windup?'蓄力':'守卫';
          c.strokeText(tag,edgeX,edgeY-size*.46);c.fillStyle=threatening;c.fillText(tag,edgeX,edgeY-size*.46);
        }
      }
      for(const mate of pose.allies){
        entity(mate.id,mate.x,mate.y,(x,y,flash)=>pawn(c,x,y,size,'#65a6a9','#40606d',mate.hp===0,flash));
      }
      const [x,y]=centre(hero.x,hero.y);
      glow(c,x,y,size*.94,'#fff5c92f');
      entity('player',hero.x,hero.y,(x,y,flash)=>pawn(c,x,y,size,'#cea87b','#eacb98',hero.hp===0,flash));
      for(const hit of hits)drawContact(c,hit,centre(hit.x,hit.y),size,elapsed,reduced);
      if(selected){
        const [sx,sy]=centre(...selected),t=size*.87;
        c.strokeStyle='#ecd09b';c.lineWidth=1.5;
        c.strokeRect(sx-t/2,sy-t/2,t,t);
      }
    },
    pick(px,py) {
      if(!view)return null;
      const x=Math.floor((px-view.ox)/view.size),y=Math.floor((py-view.oy)/view.size);
      return x>=0&&y>=0&&x<view.w&&y<view.h?[x,y]:null;
    }
  };
  return map;
}
