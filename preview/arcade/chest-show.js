const lookup = id => document.getElementById(id);
const asset = name => new URL('./art/'+name, import.meta.url).href;
export const chestFrames = Array.from({length:9}, (_,i) => asset(`chest-${i}.webp`));
const names = ['普通','精良','稀有','珍贵','史诗','传说','传世秘宝'];
const hues = ['#dfd6bd','#a8d4ab','#95cce8','#c9a6ef','#f5cd7c','#ffb09c','#f9e0b1'];
const reduced = matchMedia('(prefers-reduced-motion: reduce)');
const clamp = (n,a,b) => Math.max(a,Math.min(b,n));

export function chestShow() {
  const scene = lookup('chest-scene'), game = lookup('game'), model = lookup('chest-model');
  const loot = lookup('chest-loot'), halo = lookup('chest-halo'), fx = lookup('chest-fx');
  const imageFrames = new Map();
  let animation, audio, lastFrame, currentGrade=0;

  function preload(src) {
    if (!imageFrames.has(src)) {
      const img=new Image();
      img.src=src;
      imageFrames.set(src,img);
    }
    return imageFrames.get(src);
  }
  for(const frame of chestFrames)preload(frame);
  for(const kind of ['token','jewel','crown'])preload(asset(`loot-${kind}.webp`));

  function note(freq,delay=0,duration=.14,strength=.075,type='sine') {
    if (!audio || audio.state!=='running')return;
    const now=audio.currentTime+delay;
    const wave=audio.createOscillator(), vol=audio.createGain();
    wave.type=type;
    wave.frequency.setValueAtTime(freq,now);
    vol.gain.setValueAtTime(.0001,now);
    vol.gain.exponentialRampToValueAtTime(strength,now+.012);
    vol.gain.exponentialRampToValueAtTime(.0001,now+duration);
    wave.connect(vol).connect(audio.destination);
    wave.start(now);
    wave.stop(now+duration);
  }
  function frame(i) {
    if(lastFrame===i)return;
    if(preload(chestFrames[i]).complete){lastFrame=i;model.src=chestFrames[i];}
  }
  function colour(grade) {
    currentGrade=clamp(Number(grade)||0,0,6);
    scene.style.setProperty('--gem',hues[currentGrade]);
    lookup('chest-quality').textContent=names[currentGrade]+'宝箱';
  }
  function stopAnimation() {
    if(animation)cancelAnimationFrame(animation);
    animation=null;
  }
  function burst(amount,time) {
    const c=fx.getContext('2d');
    const w=fx.clientWidth,h=fx.clientHeight;
    if(!w||!h||!c)return;
    const dpr=Math.min(2,devicePixelRatio||1);
    if(fx.width!==Math.ceil(w*dpr)||fx.height!==Math.ceil(h*dpr)){fx.width=Math.ceil(w*dpr);fx.height=Math.ceil(h*dpr);}
    c.setTransform(dpr,0,0,dpr,0,0);
    c.clearRect(0,0,w,h);
    if(amount<=0||reduced.matches)return;
    const cx=w*.51,cy=h*.55;
    c.globalCompositeOperation='screen';
    for(let n=0;n<5;n++) {
      const phase=(n-2)*.14+Math.sin(time*1.7+n)*.07;
      const g=c.createLinearGradient(cx,cy,cx+phase*w,cy-h*.53);
      g.addColorStop(0,hues[currentGrade]+'af');g.addColorStop(1,'transparent');
      c.beginPath();
      c.moveTo(cx-w*.10,cy);c.lineTo(cx+phase*w-w*.05,cy-h*.53);
      c.lineTo(cx+phase*w+w*.06,cy-h*.53);c.lineTo(cx+w*.09,cy);c.closePath();
      c.globalAlpha=amount*.29;c.fillStyle=g;c.fill();
    }
    for(let i=0;i<(currentGrade>=3?42:25);i++){
      const t=(time*(.24+(i%7)*.03)+(i*.61803399)%1)%1;
      const x=cx+Math.sin(i*2.9)*(.06+t*.37)*w,y=cy-h*(.013+t*.53);
      const r=1+(i%5)*.33;
      c.globalAlpha=Math.sin(t*Math.PI)*amount*.87;
      c.fillStyle=i%4===0?'white':hues[currentGrade];
      c.save();c.translate(x,y);c.rotate(time*.6+i*.71);c.fillRect(-r*.65,-r*1.5,r*1.3,r*3);c.restore();
    }
    c.globalAlpha=1;
    c.globalCompositeOperation='source-over';
  }
  return {
    begin(box) {
      try {
        const engine=window.AudioContext||window.webkitAudioContext;
        if(engine){audio??=new engine();audio.resume().catch(()=>{});}
      } catch { /* Visual feedback works with sound off. */ }
      stopAnimation();lastFrame=undefined;
      scene.hidden=false;game.dataset.chest='yes';scene.dataset.phase='opening';
      lookup('chest-message').hidden=true;lookup('chest-continue').hidden=true;lookup('chest-stop').hidden=false;
      lookup('chest-cover').hidden=true;
      loot.hidden=true;
      colour(Number.isInteger(box.grade)&&box.grade>=0?box.grade:box.tier==='PRECIOUS'?4:box.tier==='RARE'?2:0);
      this.step(0);
      note(210,0,.11,.055,'triangle');
    },
    step(ratio, threat='', guard=false) {
      const percent=clamp(ratio,0,1);
      lookup('chest-meter').value=percent;
      lookup('chest-stage').textContent=percent<=0?'扣住锁环':percent<.45?'转动铜锁':percent<.76?'撬开封条':'箱缝透光';
      lookup('chest-threat').textContent=threat||'留意来路 · 随时可以停手';
      lookup('chest-cover').hidden=!guard;
      frame(percent>.67?2:percent>.19?1:0);
      halo.style.opacity=String(Math.max(0,percent-.30)*.52);
      burst(percent*.27,percent*3);
      if(percent>0) {
        note(260+percent*420,0,.10,.046,'triangle');
        note(520+percent*360,.025,.10,.025);
      }
    },
    reveal(receipt) {
      stopAnimation();
      if(receipt.grade>=0)colour(receipt.grade);
      scene.dataset.phase='reward';
      lookup('chest-stop').hidden=true;
      lookup('chest-cover').hidden=true;
      lookup('chest-meter').value=1;
      lookup('chest-stage').textContent='箱盖打开';
      lookup('chest-threat').textContent='带着宝物安全撤离';
      lookup('chest-rank').textContent=names[currentGrade];
      lookup('chest-name').textContent=receipt.name;
      lookup('chest-value').textContent=`估值 ${receipt.value} · ${receipt.inBag?'已放入背包':'背包已满，宝物落地'}`;
      const kind=receipt.name==='晨星之核'||currentGrade>=4?'crown':currentGrade>=2?'jewel':'token';
      loot.src=asset(`loot-${kind}.webp`);loot.alt=receipt.name;
      const revealTime=reduced.matches?170:870;
      const start=performance.now();
      const animate=now=>{
        const elapsed=now-start,t=clamp(elapsed/revealTime,0,1);
        frame(clamp(2+Math.floor(t*7),2,8));
        halo.style.opacity=String(.24+Math.sin(Math.PI*t)*.62);
        const display=clamp((t-.47)*2.3,0,1);
        if(display>0){
          loot.hidden=false;
          loot.style.opacity=String(display);
          loot.style.transform=`translateY(${(1-display)*18}px) scale(${.64+display*.36})`;
        }
        if(t>.63)lookup('chest-message').hidden=false;
        burst(.86,elapsed/1000);
        if(t<1)animation=requestAnimationFrame(animate);
        else {
          lookup('chest-continue').hidden=false;
          animation=null;
        }
      };
      animation=requestAnimationFrame(animate);
      [349,523,659,784].slice(0,currentGrade>=3?4:3).forEach((f,j)=>note(f,.09+j*.083,.44,.048));
      if(navigator.vibrate)navigator.vibrate(currentGrade>=3?[20,35,30]:16);
    },
    interrupt() {
      note(125,0,.20,.095,'triangle');
      if(navigator.vibrate)navigator.vibrate([45,20,25]);
      this.close();
    },
    close(){
      stopAnimation();scene.hidden=true;delete game.dataset.chest;
      burst(0,0);
    }
  };
}
