const grades = ['#b9bbb2','#b8c9a1','#83bde0','#b3a7e0','#e3b875','#e69082','#f0eace'];
const palette = ['#34464b','#45565d','#354b46','#54524b'];
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
function pawn(c,x,y,s,coat,hood, down) {
  ellipse(c,'#13232a9a',x,y+s*.22,s*.28,s*.11);
  if(down){ellipse(c,coat,x,y,s*.26,s*.12);return;}
  poly(c,coat,[[x-s*.14,y-s*.18],[x+s*.14,y-s*.18],[x+s*.24,y+s*.20],[x,y+s*.26],[x-s*.25,y+s*.16]]);
  poly(c,'#111b2739',[[x,y-s*.16],[x+s*.14,y-s*.18],[x+s*.24,y+s*.20],[x,y+s*.26]]);
  ellipse(c,hood,x,y-s*.21,s*.157,s*.15);
  ellipse(c,'#e6be9b',x,y-s*.18,s*.091,s*.077);
  rect(c,'#bcaa82',x-s*.18,y+s*.02,s*.33,s*.043);
}
function chest(c,x,y,s,tone,open) {
  ellipse(c,'#111c24a8',x,y+s*.19,s*.35,s*.13);
  if(!open) glow(c,x,y,s*.85,tone+'39');
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
function guard(c,x,y,s,m) {
  ellipse(c,'#19232bb2',x,y+s*.22,s*.31,s*.11);
  poly(c,m.windup?'#a86758':'#67565f',[[x,y-s*.36],[x+s*.25,y-s*.12],[x+s*.18,y+s*.23],[x-s*.23,y+s*.18],[x-s*.27,y-s*.09]]);
  ellipse(c,'#302a32',x,y-s*.13,s*.15,s*.12);
  for(const offset of [-.048,.048]) rect(c,m.windup?'#ffd18b':'#e5a88c',x+offset*s,y-s*.166,s*.031,s*.028);
  rect(c,'#1c2731',x-s*.22,y-s*.44,s*.44,s*.055);
  rect(c,'#db9276',x-s*.22,y-s*.44,s*.44*Math.min(m.hp/12,1),s*.055);
}

export function relicMap(canvas) {
  const c=canvas.getContext('2d',{alpha:false});
  let view;
  return {
    paint(s, selected) {
      const width=canvas.clientWidth, height=canvas.clientHeight;
      if(!width || !height)return;
      const dpr=Math.min(devicePixelRatio||1,1.75);
      canvas.width=Math.round(width*dpr);canvas.height=Math.round(height*dpr);
      c.setTransform(dpr,0,0,dpr,0,0);
      const size=clamp(Math.min(width/10,height/6.3),34,75);
      const hero=s.hero, ox=width/2-(hero.x+.5)*size, oy=height*.46-(hero.y+.5)*size;
      view={size,ox,oy,w:s.w,h:s.h};
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
        const tx=ox+x*size, ty=oy+y*size,n=tileHash(x,y);
        if(tx+size<0||ty+size<0||tx>width||ty>height)continue;
        rect(c,palette[tile]??palette[0],tx,ty,size+.4,size+.4);
        if(tile===1){
          rect(c,'#273941',tx,ty+size*.78,size,size*.22);
          rect(c,'#73828165',tx,ty+size*.025,size,size*.045);
          if(n>.61){
            rect(c,'#63716d',tx+size*.14,ty+size*.09,size*.11,size*.62);
            rect(c,'#34474a',tx+size*.24,ty+size*.09,size*.07,size*.66);
            rect(c,'#81867a',tx+size*.11,ty+size*.065,size*.20,size*.055);
          }
          for(let row=0;row<3;row++) {
            rect(c,'#273a4575',tx,ty+row*size*.25,size,1);
            rect(c,'#2a3b457b',tx+size*(row===1?.60:.33),ty+row*size*.25,1,size*.22);
          }
        } else {
          rect(c,'#1c30336e',tx,ty,size,1);
          rect(c,'#1c30336e',tx,ty,1,size);
          for(let j=0;j<3;j++) {
            const a=tileHash(x+j*22,y), b=tileHash(y+j*34,x);
            rect(c,n>.5?'#abc0a023':'#172b334b',tx+a*size*.9,ty+b*size*.92,2+n*4,1+n*2);
          }
          if(tile===3) poly(c,'#7e5e5079',[[tx+5,ty+size*.60],[tx+size*.66,ty+5],[tx+size*.63,ty+size*.51],[tx+size*.87,ty+size*.84]]);
          if(tile===2) ellipse(c,'#77928148',tx+size*.53,ty+size*.55,size*.31,size*.19);
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
      for(const box of s.chests)chest(c,...centre(box.x,box.y),size,grades[box.grade]||grades[0],box.opened);
      for(const drop of s.drops) gem(c,...centre(drop.x,drop.y),size*.13,grades[drop.loot.grade]||grades[0]);
      for(const foe of s.mobs) guard(c,...centre(foe.x,foe.y),size,foe);
      for(const mate of s.allies) pawn(c,...centre(mate.x,mate.y),size,'#65a6a9','#40606d',mate.hp===0);
      const [x,y]=centre(hero.x,hero.y);
      glow(c,x,y,size*.94,'#fff5c92f');
      pawn(c,x,y,size,'#cea87b','#eacb98',hero.hp===0);
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
}
