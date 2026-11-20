const lookup = id => document.getElementById(id);
const asset = name => new URL('./art/' + name, import.meta.url).href;
export const chestFrames = Array.from({length:9}, (_,i) => asset(`chest-${i}.webp`));
export const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');
const clamp = (n,a=0,b=1) => Math.max(a,Math.min(b,n));
const mix = (a,b,t) => a+(b-a)*t;
const ease = t => t*t*(3-2*t);
const appraisal = new Intl.NumberFormat('zh-CN');
export const lootValue = item => appraisal.format(item.value);

// Rank changes both the colour and the shape of the light.
const grades = [
  {name:'普通', light:['#d7caba'], beams:0, sparks:5, halo:0, height:.19},
  {name:'精良', light:['#9de5b5'], beams:1, sparks:8, halo:0, height:.27},
  {name:'稀有', light:['#82cfff','#b6e2ff'], beams:2, sparks:12, halo:1, height:.35},
  {name:'珍贵', light:['#c2a0ff','#ecbdff'], beams:3, sparks:16, halo:1, height:.51},
  {name:'史诗', light:['#ffce72','#fff0ba'], beams:5, sparks:22, halo:2, height:.59},
  {name:'传说', light:['#ff7a77','#ffc597'], beams:5, sparks:29, halo:2, height:.68},
  {name:'传世秘宝', light:['#ffd8ff','#8ef3e6','#adbcff','#fff0b2','#ffa8d5'], beams:7, sparks:36, halo:3, height:.75}
];
const palette = grade => grades[clamp(Number(grade)||0,0,6)];
export function gradeOf(item) {
  if (item.displayGrade >= 0) return item.displayGrade;
  if (item.grade >= 0) return item.grade;
  if (item.name==='晨星之核') return 6;
  if (item.name==='封印之心') return 5;
  if (item.tier==='PRECIOUS') return item.value>=460?4:3;
  return item.tier==='RARE'?2:item.value>=100?1:0;
}

let audio;
let strikeBuffer;
let lastImpact = -Infinity;
export function primeGameSound() {
  try {
    const engine=window.AudioContext||window.webkitAudioContext;
    if(engine){audio??=new engine();if(audio.state==='suspended')audio.resume().catch(()=>{});}
  } catch { /* Audio is optional on this device. */ }
}

function note(freq,delay,duration,strength,type='sine') {
  if(audio?.state!=='running')return;
  const now=audio.currentTime+delay;
  const wave=audio.createOscillator(), vol=audio.createGain();
  wave.type=type;
  wave.frequency.value=freq;
  vol.gain.setValueAtTime(.0001,now);
  vol.gain.exponentialRampToValueAtTime(strength,now+.012);
  vol.gain.exponentialRampToValueAtTime(.0001,now+duration);
  wave.connect(vol).connect(audio.destination);
  wave.start(now);
  wave.stop(now+duration);
}

export function playImpact(hit) {
  if(audio?.state!=='running' || audio.currentTime-lastImpact<.075)return;
  lastImpact=audio.currentTime;
  // Low body, a short band-pass crack, and a higher sweep, rendered into one voice.
  if(!strikeBuffer){
    const rate=audio.sampleRate;
    strikeBuffer=audio.createBuffer(1,Math.ceil(rate*.24),rate);
    const pcm=strikeBuffer.getChannelData(0);
    let noise=71, fast=0, slow=0;
    const smoothing=1-Math.exp(-8200/rate), body=1-Math.exp(-1800/rate);
    for(let i=0;i<pcm.length;i++){
      const t=i/rate;
      noise=(Math.imul(noise,1664525)+1013904223)>>>0;
      const white=noise/2147483648-1;
      fast+=smoothing*(white-fast);slow+=body*(white-slow);
      const low=Math.sin(2*Math.PI*(104*t-185*t*t))*.62*Math.exp(-t*24);
      const crack=(fast-slow)*.77*Math.exp(-t*68);
      const wind=(white-fast)*.22*Math.exp(-t*27);
      pcm[i]=(low+crack+wind)*Math.min(1,i/40);
    }
  }
  const source=audio.createBufferSource(), gain=audio.createGain();
  source.buffer=strikeBuffer;
  source.playbackRate.value=({block:1.72,bow:1.32,enemy:.78,sweep:.91,spear:1.09,chest:.94})[hit.style]||1;
  gain.gain.value=hit.style==='block'?.25:hit.style==='chest'?.43:hit.defeated||hit.target==='player'?.47:.36;
  source.connect(gain).connect(audio.destination);
  source.start();
  if(hit.style==='block')note(980,0,.11,.07,'triangle');
}

const treasureSilhouettes = {
  '晨星之核':'star','封印之心':'star','哨兵之核':'star',
  '永夜结晶':'crystal','幽蓝宝石':'crystal','暗影碎片':'crystal','深海珍珠':'pearl',
  '星穹冠冕':'crown','星河沙漏':'hourglass','冰魄竖琴':'harp','黎明权杖':'sceptre',
  '黄金天球仪':'orbit','王室密匣':'case','琥珀音匣':'case','锈蚀铜杯':'cup',
  '秘仪短杖':'sceptre','旅人哨笛':'sceptre','暮光戒指':'ring',
  '月银胸针':'pendant','月蚀吊坠':'pendant','猫眼胸章':'pendant',
  '石刻铭牌':'tablet','破损星图':'tablet','银线书签':'tablet','祭司秘纹':'tablet',
  '铜制罗盘':'orbit','旧日怀表':'orbit',
};
const artCache=new Map();
export function lootAsset(item) {
  const grade=gradeOf(item), key=item.name+':'+grade;
  if(artCache.has(key))return artCache.get(key);
  const canvas=document.createElement('canvas');
  canvas.width=canvas.height=256;
  const c=canvas.getContext('2d');
  const kind=treasureSilhouettes[item.name]||'medallion';
  const {light}=palette(grade), tint=light[0];
  c.translate(128,128);
  const metal=c.createLinearGradient(-65,-83,71,75);
  metal.addColorStop(0,grade>=4?'#fff4cd':'#edf4ee');
  metal.addColorStop(.24,grade>=4?'#bd8351':'#a4aaa8');
  metal.addColorStop(.43,'#e7cf9d');metal.addColorStop(.62,'#513c35');metal.addColorStop(.88,'#f0d2a2');
  const path=(points,fill,edge)=>{
    c.beginPath();points.forEach(([x,y],i)=>i?c.lineTo(x,y):c.moveTo(x,y));c.closePath();
    if(fill){c.fillStyle=fill;c.fill();}
    if(edge){c.strokeStyle=edge;c.lineWidth=2;c.stroke();}
  };
  const ellipse=(x,y,rx,ry,fill,stroke,w=2)=>{
    c.beginPath();c.ellipse(x,y,rx,ry,0,0,Math.PI*2);
    if(fill){c.fillStyle=fill;c.fill();}
    if(stroke){c.strokeStyle=stroke;c.lineWidth=w;c.stroke();}
  };
  const facet=(x,y,s,col=tint)=>{
    const points=[[x,y-s],[x+s*.75,y-s*.3],[x+s*.56,y+s*.57],[x,y+s],[x-s*.65,y+s*.40],[x-s*.75,y-s*.28]];
    path(points,col,'#ffefcf');
    const centre=[x-s*.09,y-s*.11];
    ['#ffffff9c','#fafafa38','#26346478','#26173485','#4a488944','#ffffff2c'].forEach((colour,i)=>
      path([points[i],points[(i+1)%6],centre],colour));
    path([[x,y-s],[x+s*.12,y-s*.21],[x+s*.40,y-s*.24],[x+s*.12,y+s*.02]],'#fff8e9bd');
  };
  const stroke=(points,colour,width)=>{
    c.beginPath();points.forEach(([x,y],i)=>i?c.lineTo(x,y):c.moveTo(x,y));
    c.strokeStyle=colour;c.lineWidth=width;c.lineJoin='round';c.stroke();
  };
  if(kind==='star'||kind==='orbit'){
    const star=kind==='star';
    for(const tilt of [-.68,.64]){
      c.save();c.rotate(tilt);ellipse(0,0,77,33,null,metal,4);c.restore();
    }
    ellipse(0,0,star?67:58,star?77:66,null,metal,star?5:3);
    if(star){
      const frame=Array.from({length:16},(_,i)=>{
        const a=i*Math.PI/8-Math.PI/2,r=i%2?39:i%4===0?95:71;
        return [Math.cos(a)*r,Math.sin(a)*r];
      });
      path(frame,metal,'#efd6a8');
      for(let i=0;i<8;i++){
        const a=i*Math.PI/4;
        facet(Math.cos(a)*66,Math.sin(a)*66,i%2?7:10,light[i%light.length]);
      }
    } else {
      for(let i=0;i<12;i++){
        const a=i*Math.PI/6;ellipse(Math.cos(a)*50,Math.sin(a)*57,2,2,'#f4d9ad');
      }
    }
    facet(0,0,star?45:27,light[grade===6?1:0]);
  } else if(kind==='crown'){
    path([[-79,-31],[-46,-12],[-43,-63],[-17,-29],[0,-85],[20,-28],[46,-62],[50,-11],[79,-33],[64,45],[-63,45]],metal,'#e4bf86');
    ellipse(0,46,65,15,'#624238',metal,6);
    for(let i=-2;i<=2;i++)facet(i*24,(Math.abs(i)-2)*9+17,i===0?26:12);
    ellipse(0,50,54,6,null,'#f6df9f',2);
  } else if(kind==='crystal'||kind==='pearl'||kind==='pendant'||kind==='ring'){
    const ring=kind==='ring';
    ellipse(0,ring?22:0,55,ring?42:60,null,metal,ring?13:5);
    if(kind==='pearl'){
      const frost=c.createRadialGradient(-16,-17,2,0,0,50);
      frost.addColorStop(0,'#fffdf0');frost.addColorStop(.5,tint);frost.addColorStop(1,'#54476c');
      ellipse(0,0,44,46,frost,metal);
    } else {
      facet(0,ring?-32:0,ring?30:49);
      [-1,1].forEach(sign=>facet(sign*43,24,11,light[1]||tint));
      if(kind==='pendant')ellipse(0,-75,11,13,null,metal,5);
    }
  } else if(kind==='hourglass'){
    path([[-52,-65],[52,-65],[24,-27],[9,0],[26,30],[52,65],[-52,65],[-26,30],[-9,0],[-27,-31]],tint+'47','#fff0bb');
    path([[-32,56],[0,21],[32,56]],tint);
    for(const x of [-56,56])stroke([[x,-64],[x*.86,-9],[x*.86,23],[x,64]],metal,7);
    for(const y of [-66,67])ellipse(0,y,64,9,metal,'#ecd7a1');
    facet(0,-11,13);
  } else if(kind==='harp'){
    path([[-55,67],[-51,-65],[-24,-45],[32,-16],[55,-28],[77,-51],[69,-1],[52,43],[36,72]],metal);
    path([[-35,43],[-34,-38],[43,-3],[31,35]],'#252739');
    for(let i=0;i<5;i++)stroke([[-26+i*13,-31+i*6],[-26+i*13,43-i*2]],'#ffedba',1.8);
    facet(-47,38,15);facet(47,44,12);
  } else if(kind==='sceptre'){
    c.rotate(.48);
    stroke([[0,78],[0,-29]],metal,15);
    ellipse(0,-45,30,30,null,metal,6);facet(0,-45,26);
    for(const y of [-12,19,58,78])ellipse(0,y,11,4,metal);
  } else if(kind==='case'||kind==='tablet'||kind==='cup'){
    const points=kind==='case'?[[-67,-30],[-38,-53],[65,-43],[75,-9],[57,44],[-66,39]]:
      kind==='cup'?[[-58,-54],[58,-54],[27,14],[12,20],[12,43],[44,62],[-44,62],[-12,43],[-12,20],[-27,14]]:
        [[-59,-55],[46,-63],[62,-39],[49,52],[-35,63],[-59,46]];
    path(points,metal,'#d6bb87');
    ellipse(0,kind==='cup'?-50:0,kind==='cup'?50:34,kind==='cup'?9:25,'#504038',metal,3);
    facet(0,0,19);
    if(kind==='tablet')for(const y of [-36,33,43])stroke([[-25,y],[19,y-5]],'#413938',3);
    if(kind==='case')for(const x of [-48,50])stroke([[x,-30],[x,36]],'#fff4c5',3);
  } else {
    ellipse(0,2,65,61,metal,'#e8c091',3);
    ellipse(0,1,52,48,'#4d4547',metal,4);
    for(let i=0;i<12;i++){
      const a=i*Math.PI/6;ellipse(Math.cos(a)*44,Math.sin(a)*42,2.1,2.1,'#e8d29e');
    }
    facet(0,0,31);
  }
  const specular=grade>=4?[[60,-44],[-43,35],[18,-74]]:[[39,-28]];
  for(const [x,y] of specular){
    stroke([[x-7,y],[x+7,y]],'#fff8e5',1.3);
    stroke([[x,y-6],[x,y+6]],'#fff8e5',1.3);
  }
  const url=canvas.toDataURL();
  if(artCache.size>=64)artCache.delete(artCache.keys().next().value);
  artCache.set(key,url);
  return url;
}

export function chestShow() {
  const scene=lookup('chest-scene'), game=lookup('game');
  const model=lookup('chest-model'), nextModel=lookup('chest-model-next'), loot=lookup('chest-loot');
  const meter=lookup('chest-meter'), stage=lookup('chest-stage'), threat=lookup('chest-threat');
  const quality=lookup('chest-quality'), visual=model.closest('.chest-visual'), map=lookup('map-2d');
  const holdButton=lookup('chest-hold'), message=lookup('chest-message'), next=lookup('chest-continue');
  const fx=lookup('chest-fx'), ctx=fx.getContext('2d');
  const frames=chestFrames.map(src=>{const img=new Image();img.src=src;return img;});
  const prepared=Promise.allSettled(frames.map(img=>img.decode()));
  let animation=0, motion, saved=0, shown=0, rank=0, reward, began=0;
  let promotion=null, shellGrade=0, revealStart=0, revealFrom=0;
  const promotionMs=470;

  function colour(grade,confirmed=false) {
    grade=clamp(Number(grade)||0,0,6);
    const previous=rank;
    rank=grade;
    scene.style.setProperty('--gem',palette(rank).light[0]);
    quality.textContent=palette(rank).name+(rank>shellGrade?'辉光':'宝箱');
    if(confirmed&&grade>previous){
      promotion={from:previous,at:performance.now(),lid:shown*2.7};
      playImpact({style:'chest'});
      const root=392*2**(rank/5);
      note(root,.024,.22,.091,'triangle');
      note(root*1.5,.069,.27,.066);
      if(rank>=4)note(root*2,.12,.30,.052);
    }
  }

  function drawFrames(position){
    const lower=Math.floor(position), upper=Math.min(lower+1,8);
    if(frames[lower].naturalWidth&&model.src!==chestFrames[lower])model.src=chestFrames[lower];
    if(frames[upper].naturalWidth&&nextModel.src!==chestFrames[upper])nextModel.src=chestFrames[upper];
    nextModel.style.opacity=frames[upper].naturalWidth?String(position-lower):'0';
  }

  function drawLight(lid,seconds,release,pulse,age){
    if(!ctx)return;
    const w=fx.clientWidth,h=fx.clientHeight;
    if(!w||!h)return;
    const dpr=Math.min(1.75,devicePixelRatio||1), width=Math.round(w*dpr),height=Math.round(h*dpr);
    if(fx.width!==width||fx.height!==height){fx.width=width;fx.height=height;}
    ctx.setTransform(dpr,0,0,dpr,0,0);ctx.clearRect(0,0,w,h);
    const profile=palette(rank), [primary]=profile.light;
    const x=w*.49, y=h*(.621-lid*.040);
    const seam=clamp(.04+shown*.68+release*.38+pulse*.38);
    const radius=Math.min(w,h)*(.22+shown*.13+release*.12+pulse*.21);
    const glow=ctx.createRadialGradient(x,y,0,x,y,radius);
    glow.addColorStop(0,primary+'c4');glow.addColorStop(.40,primary+'53');glow.addColorStop(1,primary+'00');
    ctx.globalAlpha=seam*.9;
    ctx.fillStyle=glow;ctx.fillRect(x-radius,y-radius,radius*2,radius*2);
    const slit=ctx.createLinearGradient(x-w*.16,y,x+w*.14,y);
    slit.addColorStop(0,primary+'00');
    slit.addColorStop(.46,pulse>.72?'#fff7e7':primary);
    slit.addColorStop(1,primary+'00');
    ctx.globalAlpha=seam*.63;ctx.strokeStyle=slit;
    ctx.beginPath();ctx.moveTo(x-w*.16,y-h*.027);ctx.lineTo(x+w*.14,y+h*.036);
    ctx.lineWidth=2+seam*8+pulse*6;ctx.stroke();
    ctx.globalAlpha=seam;ctx.lineWidth=.7+release*1.6;ctx.stroke();
    if(reducedMotion.matches){ctx.globalAlpha=1;return;}
    ctx.globalCompositeOperation='screen';
    for(let i=0;i<profile.beams;i++){
      const color=profile.light[i%profile.light.length];
      const spread=(i-(profile.beams-1)/2)*.075, height=profile.height*(.55+i%3*.18);
      const endX=x+w*spread*(.64+release*.53+pulse*.43),endY=y-h*height*(1+pulse*.44);
      const beam=ctx.createLinearGradient(x,y,endX,endY);
      beam.addColorStop(0,color+'bc');beam.addColorStop(.30,color+'53');beam.addColorStop(1,color+'00');
      ctx.fillStyle=beam;ctx.globalAlpha=seam*(.39+release*.44+pulse*.35);
      ctx.beginPath();ctx.moveTo(x+w*spread*.30-w*.015,y);
      ctx.lineTo(endX-w*.025,endY);ctx.lineTo(endX+w*.032,endY);
      ctx.lineTo(x+w*spread*.30+w*.017,y);ctx.fill();
    }
    for(let i=0;i<profile.sparks;i++){
      const t=(seconds*(.38+(i%5)*.08)+i*.618034)%1;
      const dx=Math.sin(i*2.9)*(.07+t*.29)*w,dy=t*h*profile.height;
      const r=1+(i%4)*.41;
      ctx.globalAlpha=Math.sin(t*Math.PI)*seam*(.50+release*.50);
      ctx.fillStyle=profile.light[i%profile.light.length];
      ctx.save();ctx.translate(x+dx,y-dy);ctx.rotate(i*.55+t*1.4);
      ctx.fillRect(-r*.6,-r,r*1.2,r*2);ctx.restore();
    }
    for(let i=0;i<profile.halo;i++){
      const rx=w*(.08+i*.032+release*.035),ry=rx*.39;
      ctx.globalAlpha=release*(.50-i*.10);
      ctx.strokeStyle=profile.light[i%profile.light.length];ctx.lineWidth=1;
      ctx.beginPath();ctx.ellipse(x,y-h*.14,rx,ry,-.23+i*.2,0,Math.PI*2);ctx.stroke();
    }
    if(age<1)drawPromotion(x,y,w,h,profile,age);
    ctx.globalAlpha=1;ctx.globalCompositeOperation='source-over';
  }

  function drawPromotion(x,y,w,h,profile,age){
    const travel=1-(1-age)**2.8;
    const fade=(1-age)**2, scale=Math.min(w,h);
    const rays=rank>=5?18:rank>=3?13:9;
    ctx.lineCap='round';
    for(let i=0;i<rays;i++){
      const angle=-Math.PI*(.07+.88*i/(rays-1));
      const distance=scale*(.07+travel*(.29+i%4*.065));
      const dx=Math.cos(angle),dy=Math.sin(angle);
      const px=x+dx*distance,py=y+dy*distance+scale*.12*age*age;
      ctx.beginPath();
      ctx.moveTo(px,py);
      ctx.lineTo(px+dx*scale*.058*(1-age),py+dy*scale*.058*(1-age));
      ctx.strokeStyle=i%4===0?'#fff4db':profile.light[i%profile.light.length];
      ctx.globalAlpha=fade*(.66+i%3*.14);
      ctx.lineWidth=1.3+i%3*.65;
      ctx.stroke();
    }
    const rx=scale*(.16+travel*.29), ry=rx*.27;
    ctx.beginPath();ctx.ellipse(x,y,rx,ry,0,0,Math.PI*2);
    ctx.strokeStyle=profile.light[0];ctx.lineWidth=2.2;
    ctx.globalAlpha=fade*.75;ctx.stroke();
    ctx.lineCap='butt';
  }

  function paint(now){
    animation=0;
    const elapsed=promotion?Math.max(0,now-promotion.at):promotionMs;
    const age=clamp(elapsed/promotionMs);
    const pulse=reducedMotion.matches?0:(1-clamp(elapsed/285))**2;
    if(age===1)promotion=null;
    let lid, release=0;
    if(reward){
      const quick=reducedMotion.matches;
      const p=clamp((now-revealStart)/(quick?180:1740));
      shown=mix(revealFrom,1,ease(clamp(p/.20)));
      lid=mix(2.7*revealFrom,8,ease(clamp((p-.09)/.64)));
      release=Math.sin(Math.PI*clamp((p-.07)/.93));
      const visible=ease(clamp((p-.45)/.46));
      loot.hidden=visible===0;
      loot.style.opacity=String(visible);
      loot.style.transform=`translateY(${(1-visible)*31}px) scale(${.53+visible*.62})`;
      message.hidden=p<.65;next.hidden=p<.97;
      model.parentElement.style.opacity=String(1-.66*visible);
      model.parentElement.style.transform=`translateY(${visible*10}%) scale(${1-visible*.12})`;
      stage.textContent=p<.22?'锁扣松开':p<.62?'箱盖开启':'鉴定完成';
    } else {
      if(motion){
        const p=clamp((now-motion.start)/motion.duration);
        shown=p===1?motion.to:mix(motion.from,motion.to,motion.linear?p:ease(p));
        if(p===1)motion=null;
      }
      lid=promotion&&elapsed<43&&!reducedMotion.matches?promotion.lid:shown*2.7;
      stage.textContent=promotion?`${palette(promotion.from).name} → ${palette(rank).name}`:
        shown<.06?'铜锁未开':shown<.43?'锁舌松动':shown<.72?'封条剥落':'箱缝透光';
    }
    meter.value=shown;
    const fill=`${shown*100}%`;
    holdButton.style.setProperty('--hold',fill);
    scene.style.setProperty('--chest-fill',fill);
    scene.style.setProperty('--chest-spark',String(Math.min(1,pulse*1.45)));
    const kick=rank>=5?9:rank>=3?7:5;
    const x=Math.cos(elapsed*.075)*pulse*kick, y=Math.sin(elapsed*.104)*pulse*kick*.57;
    visual.style.translate=pulse?`${x}px ${y}px`:'';
    map.style.translate=pulse?`${x*.56}px ${y*.56}px`:'';
    drawFrames(lid);
    drawLight(lid/8,Math.min((now-began)/1000,reward?8:12),release,pulse,age);
    if ((reward&&now-revealStart<(reducedMotion.matches?180:1740)) ||
        (!reward&&motion)||promotion) {
      animation=requestAnimationFrame(paint);
    }
  }

  function redraw(){
    if(!animation)animation=requestAnimationFrame(paint);
  }

  function settle(){
    motion={start:performance.now(),from:shown,to:saved,duration:140};
    redraw();
  }

  return {
    prepare:()=>prepared,
    begin(box,progress,warning,guard) {
      primeGameSound();
      if(animation)cancelAnimationFrame(animation);
      animation=0;motion=null;reward=null;promotion=null;
      shown=saved=progress;began=performance.now();
      model.parentElement.style.opacity='1';
      model.parentElement.style.transform='';
      scene.hidden=false;game.dataset.chest='yes';scene.dataset.phase='opening';
      message.hidden=next.hidden=loot.hidden=true;
      lookup('chest-stop').hidden=holdButton.hidden=false;
      holdButton.textContent='按住撬锁';
      shellGrade=box.shellGrade??box.grade;
      colour(box.glowGrade??shellGrade);
      threat.textContent=warning;lookup('chest-cover').hidden=!guard;
      redraw();
      note(212,0,.13,.063,'triangle');
    },
    hold(active,warning) {
      holdButton.textContent=active?'正在撬锁':'按住撬锁';
      if(!active){
        if(!reward)threat.textContent=warning;
        settle();
      }
    },
    charge(nextFraction,duration) {
      // Leave a small gap until Kotlin confirms this turn. Releasing rolls the
      // unfinished part of the gesture back to the last actual checkpoint.
      motion={start:performance.now(),from:shown,to:mix(saved,nextFraction,.975),duration,linear:true};
      redraw();
    },
    step(ratio,grade,warning,guard) {
      const advanced=ratio>saved, upgraded=advanced&&grade>rank;
      saved=ratio;
      colour(grade,advanced);
      threat.textContent=warning;
      lookup('chest-cover').hidden=!guard;
      redraw();
      if(advanced){
        settle();
        if(!upgraded){
          note(235+saved*230,0,.12,.068,'triangle');
          note(810+saved*340,.028,.09,.037);
        }
      }
    },
    reveal(receipt) {
      motion=null;reward=receipt;revealStart=performance.now();revealFrom=shown;
      colour(gradeOf(receipt),true);
      scene.dataset.phase='reward';
      lookup('chest-stop').hidden=lookup('chest-cover').hidden=holdButton.hidden=true;
      threat.textContent='带着宝物安全撤离';
      lookup('chest-rank').textContent=palette(rank).name;
      lookup('chest-name').textContent=receipt.name;
      lookup('chest-value').textContent='◇ '+lootValue(receipt);
      lookup('chest-location').textContent=receipt.inBag?'已放入背包':'背包已满 · 宝物落在箱旁';
      loot.src=lootAsset(receipt);loot.alt=receipt.name;
      redraw();
      const notes=rank===6?[392,587,784,1047,1319]:rank>=4?[330,494,659,880]:[294,440,587];
      notes.forEach((f,i)=>note(f,.16+i*.115,.39,.067-i*.009));
    },
    close() {
      if(animation)cancelAnimationFrame(animation);
      animation=0;motion=reward=promotion=null;
      visual.style.translate=map.style.translate='';
      scene.hidden=true;delete game.dataset.chest;
    }
  };
}
