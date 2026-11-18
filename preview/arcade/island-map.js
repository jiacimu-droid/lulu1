// This mesh renders the existing Kotlin tile board. Decorative elevations and
// shoreline geometry leave walkability and every map object to BlockIslandRules.
const glSource = {
  vertex: `attribute vec3 aPosition;
attribute vec3 aColor;
uniform vec3 uCenter;
uniform vec3 uRight;
uniform vec3 uUp;
uniform vec3 uDepth;
uniform vec2 uSpan;
uniform mediump float uPerspective;
varying vec3 vColor;
varying vec3 vWorld;
varying float vDepth;
void main() {
  vec3 p = aPosition - uCenter;
  float z = dot(p, uDepth);
  vec2 uv = vec2(dot(p, uRight), dot(p, uUp)) / uSpan;
  if(uPerspective > 0.5) {
    gl_Position = vec4(uv, z * 1.00142 - 0.10007, z);
  } else {
    gl_Position = vec4(uv, -z / 90.0, 1.0);
  }
  vColor = aColor;
  vDepth = z;
  vWorld = aPosition;
}`,
  fragment: `precision mediump float;
varying vec3 vColor;
varying vec3 vWorld;
varying float vDepth;
uniform float uDusk;
uniform mediump float uPerspective;
void main() {
  vec3 color = vColor * mix(vec3(1.0), vec3(0.48, 0.61, 0.79), uDusk);
  if(uPerspective > 0.5) {
    float grain = fract(sin(dot(floor(vWorld * 18.0), vec3(13.13, 4.7, 32.73))) * 442.231);
    float texture = fract(sin(dot(floor(vWorld * 4.0), vec3(24.13, 9.7, 12.73))) * 124.41);
    color *= mix(.85 + .16 * grain + .09 * texture, 1.04, smoothstep(0.0, 16.0, vDepth));
    vec3 distanceColor = mix(vec3(0.68,0.79,0.78),vec3(0.29,0.45,0.56),uDusk);
    color = mix(color, distanceColor, smoothstep(9.0, 37.0, vDepth) * 0.72);
  }
  gl_FragColor = vec4(color, 1.0);
}`
};

const dot = (a, b) => a.reduce((sum, value, i) => sum + value * b[i], 0);
const color = (hex, factor = 1) => [16, 8, 0].map(shift => Math.min(1, (hex >> shift & 255) / 255 * factor));
const noise = (x, y) => {
  const value = Math.sin(x * 91.733 + y * 37.27) * 47589.73;
  return value - Math.floor(value);
};

class Mesh {
  data = [];
  faces = [];
  constructor(software = true) { this.software=software; }
  polygon(points, hex, shade = 1) {
    const c = color(hex, shade);
    if(this.software)this.faces.push({points, c});
    for (let i = 1; i < points.length - 1; i++)
      for (const vertex of [points[0], points[i], points[i + 1]]) this.data.push(...vertex, ...c);
  }
  asset(source, x, y, z, scale = 1, turn = 0, tone = 1) {
    const sine=Math.sin(turn),cosine=Math.cos(turn);
    for(let i=0;i<source.length;i+=6) {
      const sx=source[i],sz=source[i+2];
      this.data.push(x+(sx*cosine-sz*sine)*scale,y+source[i+1]*scale,z+(sx*sine+sz*cosine)*scale,
        source[i+3]*tone/255,source[i+4]*tone/255,source[i+5]*tone/255);
    }
  }
  box(x, h, z, width, height, depth, hex, top = hex) {
    const a = x - width / 2, b = x + width / 2, c = z - depth / 2, d = z + depth / 2, t = h + height;
    this.polygon([[a,t,c],[b,t,c],[b,t,d],[a,t,d]], top, 1.07);
    this.polygon([[a,h,c],[a,h,d],[a,t,d],[a,t,c]], hex, .84);
    this.polygon([[b,h,c],[b,h,d],[b,t,d],[b,t,c]], hex, .68);
    this.polygon([[a,h,c],[b,h,c],[b,t,c],[a,t,c]], hex, .89);
    this.polygon([[a,h,d],[b,h,d],[b,t,d],[a,t,d]], hex, .76);
  }
  peak(x, y, z, width, height, hex) {
    const p = width / 2;
    const corners = [[x-p,y,z-p],[x+p,y,z-p],[x+p,y,z+p],[x-p,y,z+p]];
    for (let i = 0; i < 4; i++)
      this.polygon([corners[i], corners[(i+1)%4], [x,y+height,z]], hex, [1.06,.76,.90,.86][i]);
  }
}

function ground(x, z, tile, w, h) {
  if (tile === 8) return -.30;
  if (w===35&&h===34) {
    if(x>=7&&x<=13&&z>=11&&z<=17)return .38;
    const surge=Math.max(0,1-Math.hypot((x-26)/8.7,(z-9)/7.5))*3.42;
    const hollow=Math.max(0,1-Math.hypot((x-25)/8,(z-24)/6.2))*.65;
    return Math.round((.27+surge+hollow)*5)/5;
  }
  if (x < 9 && z < 8) return .48;
  const edge = Math.min(x, z, w - 1 - x, h - 1 - z);
  return .28 + Math.min(3, Math.max(0, edge - 1)) * .09
    + Math.max(0, 1 - Math.hypot((x - 18) / 9, (z - 11) / 7)) * 1.4;
}

function pine(m, x, y, z, rough) {
  const height = 1.08 + rough * .62;
  m.box(x,y,z,.26,height,.25,0x846047,0xaa855d);
  m.box(x+.02,y+.34,z,.14,.12,.13,0x674a38);
  m.box(x,y+height*.69,z,.87,.32,.89,0x426b4d,0x6f9760);
  m.box(x-.05,y+height*.84,z+.02,.72,.40,.76,0x527a51,0x7ea269);
  m.box(x+.04,y+height*1.02,z-.04,.53,.32,.57,0x60885a,0x8caf74);
  m.box(x-.04,y+height*1.16,z+.03,.33,.19,.31,0x7c9a62);
  for (const [dx,dz] of [[-.28,-.33],[.30,.21],[.20,-.28]])
    m.box(x+dx,y+height*.68,z+dz,.18,.18,.17,0x3e654e);
}
function crate(m, x, y, z, hex = 0xb98354) {
  m.box(x,y,z,.54,.38,.48,hex);
  m.box(x,y+.26,z,.57,.08,.50,0xe7b77a);
  m.box(x,y+.13,z+.248,.10,.18,.021,0xe4c089);
}
function building(m, kind, x, y, z) {
  if (kind === 'workbench') {
    for (const dx of [-.23,.23]) for (const dz of [-.21,.21])
      m.box(x+dx,y,z+dz,.09,.37,.09,0x75523f);
    for(let k=-2;k<=2;k++)m.box(x+k*.144,y+.37,z,.139,.14,.66,k%2===0?0xbb8757:0xae7751,0xd1a56d);
    m.box(x-.12,y+.51,z,.23,.025,.13,0xaebcb0);
    m.box(x+.12,y+.52,z-.10,.15,.031,.08,0x725943);
    for(const dx of [-.32,.32])m.box(x+dx,y+.34,z,.037,.14,.63,0x65645a);
  } else if (['lantern','crystal_lantern','campfire'].includes(kind)) {
    m.box(x,y,z,.50,.14,.50,0x8b7c61);
    if (kind !== 'campfire') m.box(x,y+.14,z,.13,.5,.13,0x7e5741);
    m.peak(x,y+(kind==='campfire'?.14:.63),z,.35,.37,kind==='crystal_lantern'?0x9ee4e2:0xffcc72);
  } else if (kind === 'wall') {
    m.box(x,y,z,.98,.16,.95,0x7e8276,0x9d9d8a);
    for(let k=0;k<7;k++)m.box(x,y+.16+k*.205,z,.97,.197,.94,k%3===0?0x9e6f4d:0xb08559,0xc19a69);
    m.box(x,y+1.59,z,1.02,.13,1,0x72533a);
  } else if (kind==='door') {
    // The doorway is an actual passage. The panel is folded against the hinge.
    for(const dx of [-.39,.39])m.box(x+dx,y,z,.15,1.73,.18,0x8b6446,0xc29962);
    m.box(x,y+1.56,z,.92,.19,.25,0xb38558,0xe0b77b);
    m.box(x-.32,y,z+.24,.057,1.46,.46,0xa77851,0xbf955f);
    m.box(x-.278,y+.70,z+.42,.025,.07,.058,0xe5bd7d);
    m.box(x,y,z,.95,.08,1,0xc99863);
  } else if (kind === 'pet_house') {
    m.box(x,y,z,.65,.44,.58,0xd6ad80);
    m.peak(x,y+.44,z,.78,.36,0xa26b54);
  } else if (kind === 'bed') {
    m.box(x,y,z,.76,.17,.52,0x956449);
    m.box(x+.06,y+.17,z,.60,.07,.49,0x91b8b1);
    m.box(x-.26,y+.17,z,.19,.10,.48,0xf4e4cc);
  } else if (kind === 'garden') {
    m.box(x,y,z,.90,.105,.83,0x6c4836,0x89604a);
    for (const side of [-1,1]) {
      m.box(x+side*.43,y,z,.062,.13,.84,0x9d734e);
      m.box(x,y,z+side*.41,.86,.13,.065,0x9d734e);
    }
    for(const dx of [-.22,0,.22])m.box(x+dx,y+.10,z,.109,.049,.72,0x795340,0x9f7154);
  } else {
    for (let i=-2;i<=2;i++) m.box(x,y,z+i*.18,.96,.11,.17,0xc99d6d);
  }
}

function reefCrawler(m,x,y,z,hero,phase){
  const body=0x613856,crest=0x9c5578,claw=0x402f49;
  const axis=Math.abs(hero.x-x)>Math.abs(hero.y-z);
  const direction=axis?Math.sign(hero.x-x):Math.sign(hero.y-z);
  const front=(side,depth)=>axis?[x+direction*depth,z+side]:[x+side,z+direction*depth];
  const segment=(side,h,depth,width,height,length,tone)=>{
    const [px,pz]=front(side,depth);
    m.box(px,y+h,pz,axis?length:width,height,axis?width:length,tone);
  };
  segment(0,.36,-.08,.64,.42,.70,body);
  segment(0,.73,-.11,.49,.14,.50,crest);
  segment(0,.48,.39,.45,.34,.31,0x754462);
  for(const side of [-1,1]){
    for(let leg=0;leg<3;leg++){
      const d=(leg-1)*.23+Math.sin(phase*.9+leg*1.9+side)*.04;
      segment(side*.36,.34,d,.35,.11,.10,crest);
      segment(side*.51,.10,d,.11,.29,.13,claw);
      segment(side*.57,.05,d+.04,.20,.08,.14,claw);
    }
    segment(side*.12,.72,.56,.095,.082,.04,0xff986c);
    segment(side*.12,.75,.582,.036,.027,.02,0xffe6ac);
    segment(side*.12,.36,.44,.11,.15,.22,0x4b2d43);
    const [sx,sz]=front(side*.20,.19);
    m.peak(sx,y+.72,sz,.15,.34,0xc57883);
  }
  const [mx,mz]=front(0,-.03);
  m.peak(mx,y+.87,mz,.115,.17,0xdf886e);
  if(Math.abs(x-hero.x)+Math.abs(z-hero.y)<=5){
    m.box(mx,y+1.29,mz,.08,.17,.08,0xe98062);
    m.box(mx,y+1.17,mz,.072,.072,.071,0xffbd85);
  }
}
function person(m, x, y, z, coat, size = 1) {
  m.box(x-.105*size,y,z,.16*size,.24*size,.22*size,0x33474f);
  m.box(x+.105*size,y,z,.16*size,.24*size,.22*size,0x33474f);
  m.box(x,y+.22*size,z,.40*size,.37*size,.26*size,coat);
  m.box(x,y+.57*size,z,.32*size,.27*size,.29*size,0xf1c395);
  m.box(x,y+.76*size,z,.35*size,.09*size,.33*size,0x553d38);
  m.box(x,y+.36*size,z+.15*size,.24*size,.25*size,.14*size,0xc29a66);
}

function staticMesh(s,models,software) {
  const m = new Mesh(software);
  const w = s.world;
  const crystals = new Set(s.crystalTiles);
  const coast=w.width===35&&w.height===34;
  const adjacentWater=(x,z)=>[[1,0],[-1,0],[0,1],[0,-1]].some(([dx,dz])=>w.tiles[(z+dz)*w.width+x+dx]===8);
  m.box(w.width/2,-.64,w.height/2,190,.04,190,0x468591);
  for (let z = -4; z < w.height + 4; z++) for (let x = -4; x < w.width + 4; x++) {
    const inside = x >= 0 && x < w.width && z >= 0 && z < w.height;
    const i = z * w.width + x;
    const tile = inside ? w.tiles[i] : 8;
    const n = noise(x,z);
    if (tile === 8) {
      const nearLand = [[1,0],[-1,0],[0,1],[0,-1]].some(([dx,dz]) => {
        const tx=x+dx, tz=z+dz;
        return tx>=0 && tx<w.width && tz>=0 && tz<w.height && w.tiles[tz*w.width+tx] !== 8;
      });
      if (nearLand || n > .63) {
        m.box(x,-.56,z,1,.012,1,nearLand?(n>.45?0x79b6a7:0x6aa9aa):0x5799a1);
        if(n>.22)for(let i=0;i<3;i++) {
          const dx=noise(x+i*2,z)-.5,dz=noise(z,x+i*3)-.5;
          m.box(x+dx*.75,-.54+i*.002,z+dz*.85,.13+n*.36,.008,.017+n*.022,nearLand?0xc1e2ce:0x8abec0);
        }
      }
      continue;
    }
    const y = ground(x,z,tile,w.width,w.height);
    const edge=coast?adjacentWater(x,z):x===1||z===1||x===w.width-2||z===w.height-2;
    const path=(coast?z===10||z===11||x===13||x===14||z===20||x===24:z===5||x===5) && ![1,2,3].includes(tile);
    const cave=coast&&x>=21&&x<=27&&z>=22&&z<=27;
    const grass=[0x718a57,0x79945e,0x86a166,0x7c975f];
    const top=edge?(n>.5?0xe1c597:0xcdb58b):path?(n>.45?0xc4aa7e:0xb9a17b):
      cave?(n>.5?0x5e6c6c:0x667470):grass[Math.floor(n*4)];
    const strata=edge?0xa79475:cave?0x647179:0x8e775f;
    m.box(x,-.62,z,1,y+.56,1,strata);
    if(y>.62&&n>.33)m.box(x,y-.28,z,1,.064,1,cave?0x899590:0x9a8a70);
    m.box(x,y-.055,z,1,.055,1,top);
    if(tile===1){
      if(models?.cedar && (Math.abs(x-10)+Math.abs(z-11)<11||n>.66))
        m.asset(models.cedar,x,y,z,.87+n*.76,n*6.3,.88+n*.15);
      else {
        pine(m,x,y,z,n);
        if(n>.52)m.box(x-.15,y+.03,z+.07,.35,.083,.16,0x77634d);
      }
    }
    if(tile===2){
      if(models)m.asset(crystals.has(i)?models.ore:models['shore-rock'],x,y,z,.82+n*.44,n*6.3,.95);
      else {
        m.box(x-.09,y,z+.03,.55,.38,.63,0x8e9792,0xb5b5a3);
        m.box(x+.19,y,z-.16,.41,.60,.36,crystals.has(i)?0x68a9b0:0x909b95);
        if(crystals.has(i))m.peak(x+.19,y+.6,z-.16,.34,.31,0xa2d5de);
      }
    }
    if(tile===3&&!(coast&&x===27&&z===8)) building(m,'wall',x,y,z);
    if (tile === 4 && !s.structures[i]) building(m,'floor',x,y,z);
    if (tile === 0 && !path && !edge && !s.structures[i] && n>.76) {
      m.peak(x-.3,y,z+.27,.13,.15,0x527e55);
      m.peak(x-.20,y,z+.28,.09,.19,n>.95?0xe8d19f:0xaac385);
    }
    if(coast&&tile===0&&!path&&n>.48&&!s.structures[i]) {
      for(let tuft=0;tuft<3;tuft++){
        const dx=noise(x+tuft*4,z)-.5,dz=noise(z+tuft*3,x)-.5;
        const bx=x+dx*.81,bz=z+dz*.79, h=.07+.23*noise(bx,bz);
        m.polygon([[bx-.04,y,bz],[bx+.025,y,bz],[bx-.022,y+h,bz+.048]],0x466942);
        m.polygon([[bx,y,bz+.04],[bx+.06,y,bz],[bx+.088,y+h*.7,bz-.03]],0xa4b572);
        if(n>.94)m.box(bx+.05,y+h*.72,bz-.036,.044,.039,.041,0xd9b47f);
      }
    }
    if (tile === 7) {
      m.box(x-.37,y,z-.34,.07,.75,.07,0x87694e);
      m.box(x-.26,y+.51,z-.34,.24,.20,.037,0xe8cc88);
    }
  }
  for (const [i, kind] of Object.entries(s.structures)) {
    const x = i % w.width, z = Math.floor(i / w.width);
    building(m,kind,x,w.tiles[i]===8 ? -.46 : ground(x,z,w.tiles[i],w.width,w.height),z);
  }
  if (s.houseProject?.phase === 'complete') {
    const p = s.houseProject, x = p.left+1, z = p.top+1;
    const base=ground(x,z,3,w.width,w.height)+1.69;
    const roof=p.style==='pine'?0x526d63:0xa76e4e;
    for(let step=0;step<8;step++){
      const dx=(step+.5)*.22;
      for(const side of [-1,1]){
        const h=base+(8-step)*.14;
        m.box(x+side*dx,h,z,.247,.143,3.2,roof,step%2?0xc18c64:roof);
        for(let length=-3;length<=3;length+=2)
          m.box(x+side*dx,h+.147,z+length*.45,.239,.018,.023,0x6e5040);
      }
    }
    m.box(x,base+1.24,z,.33,.13,3.32,0x794f3a);
    const chimney=x+.95;
    m.box(chimney,base+.42,z+.62,.36,1.05,.42,0x8c8173);
    m.box(chimney,base+1.48,z+.62,.47,.14,.53,0xb5a18b);
  }
  if(coast){
    const yAt=(x,z)=>ground(x,z,w.tiles[z*w.width+x],w.width,w.height);
    if(models){
      m.asset(models.beacon,27,yAt(27,8),8,1.20,.06,.95);
      m.asset(models.cave,24,yAt(24,23),23,1.18,0,1);
      m.asset(models.dock,6.4,-.36,13,1.4,1.57,1);
      for(const [x,z,scale] of [[5,11,1.8],[4,16,1.6],[10,21,1.7],[20,3,1.8],[30,14,1.5]])
        m.asset(models['shore-rock'],x,yAt(x,z),z,scale,noise(x,z)*6,1);
    }else{
      // The low-power path keeps the same navigable landmarks.
      for(let j=0;j<15;j++){
        const y=yAt(27,8)+j*.27;
        m.box(27,y,8,1.22-j*.026,.27,1.22-j*.026,j===3||j===4||j===9||j===10?0xa66d52:0xe0cdab);
      }
      m.box(27,yAt(27,8)+4.02,8,.63,.59,.64,0xe7b675);
      m.peak(27,yAt(27,8)+4.61,8,1.16,.64,0x8d5849);
      for(const side of [-1,1])
        for(let tier=0;tier<4;tier++)
          m.box(24+side*(.98-tier*.14),yAt(24,23)+tier*.43,23,.69,.44,.9,0x667172);
      m.box(24,yAt(24,23)+1.82,23,2.03,.46,1.01,0x71817c);
      building(m,'floor',6.5,-.36,13);
    }
    // Limestone shelves surround the playable cave passage beneath the lintel.
    for(let z=24;z<=26;z++){
      const base=yAt(24,z);
      m.box(24,base+2.00,z,2.26,.38,1.06,0x606c68,0x8c9582);
      for(const x of [23.05,24.95])m.box(x,base,z,.65,2.02,1.04,0x6c7770,0x8d9486);
    }
    for(const [x,z,h] of [[-5,16,2.3],[6,-4,3.2],[34,0,4.2],[35,25,3.7],[19,38,2.25]]){
      for(let i=0;i<6;i++)m.box(x+i*.7,-.51,z+Math.sin(i)*.55,1.22,h*(.38+Math.sin((i+1)*.45)*.6),1.82,0x788987,0x879b8e);
    }
    // Wayfinding markers near the paths stay outside the player route.
    for(const [x,z,tone] of [[11,10,0xe5bd78],[18,10,0xb4ccb6],[22,20,0x8ebec1],[25,8,0xf0ce84]]){
      const y=yAt(x,z);
      m.box(x+.44,y,z+.41,.082,.86,.083,0x7e6346);
      m.box(x+.43,y+.61,z+.43,.29,.16,.046,tone,0xcdb189);
    }
  }
  return m;
}

function sceneMesh(s, selection, overview, software) {
  const w = s.world;
  const m = new Mesh(software);
  const yAt = (x,z) => ground(x,z,w.tiles[z*w.width+x],w.width,w.height);
  const hi = (x,z) => yAt(x,z) + .018;
  function highlight(x,z,hex) {
    const y = hi(x,z);
    for (const d of [-.46,.46]) {
      m.box(x,y,z+d,.95,.026,.035,hex);
      m.box(x+d,y,z,.035,.026,.95,hex);
    }
  }
  if (selection) highlight(...selection,0xfcda91);
  if(s.nextHouse && String(s.nextHouse)!==String(selection))highlight(...s.nextHouse,0x73d7d1);
  for(const [index,crop] of Object.entries(s.gardens??{})){
    const x=Number(index)%w.width,z=Math.floor(Number(index)/w.width),age=w.turns-crop.plantedTurn;
    const y=hi(x,z)+.12;
    for(const dx of [-.22,0,.22])for(const dz of [-.20,.20]){
      const size=.10+Math.min(Math.max(age,0),14)*.008;
      m.box(x+dx,y,z+dz,.033,size*1.6,.033,0x638e43);
      m.peak(x+dx,y+.037,z+dz,size,size,0x9dba68);
      if(age>12)m.box(x+dx+.04,y+.03+size,z+dz,.072,.060,.065,0xbb6658,0xdf8565);
    }
  }
  if (overview) highlight(w.x,w.y,0xe8ead3);
  const opened = new Set(s.openedSupplies);
  for (const i of s.supplyCaches) if (!opened.has(i)) {
    const x=i%w.width,z=Math.floor(i/w.width);
    crate(m,x,hi(x,z),z,s.rareSupplyCaches.includes(i)?0xb78d54:0xa47748);
  }
  for (const i of Object.keys(s.lostPacks)) crate(m,i%w.width,hi(i%w.width,Math.floor(i/w.width)),Math.floor(i/w.width),0x7395a0);
  for (const a of s.animals) {
    const y=hi(a.x,a.y);
    m.box(a.x,y+.08,a.y,.34,.27,.50,a.befriended?0xe4c28b:0xf1e4c8);
    m.box(a.x,y+.23,a.y+.24,.26,.24,.22,0xeee2c6);
  }
  for (const mob of w.mobs) if (mob.hp>0) {
    const y=hi(mob.x,mob.y);
    reefCrawler(m,mob.x,y,mob.y,w,w.turns+mob.id);
  }
  for (const a of w.friends) if (a.hp>0) person(m,a.x,hi(a.x,a.y),a.y,0x5b9eac);
  if (overview) person(m,w.x,hi(w.x,w.y),w.y,0xd88158,1.12);
  return m;
}

function webglPainter(gl, canvas) {
  const program = gl.createProgram();
  for (const [type, text] of [[gl.VERTEX_SHADER, glSource.vertex], [gl.FRAGMENT_SHADER,glSource.fragment]]) {
    const shader = gl.createShader(type);
    gl.shaderSource(shader,text);
    gl.compileShader(shader);
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(shader));
    gl.attachShader(program,shader);
    gl.deleteShader(shader);
  }
  gl.linkProgram(program);
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(program));
  gl.useProgram(program);
  gl.enable(gl.DEPTH_TEST);
  gl.clearColor(0,0,0,0);
  const uniforms = Object.fromEntries(['Center','Right','Up','Depth','Span','Dusk','Perspective'].map(name =>
    [name,gl.getUniformLocation(program,'u'+name)]));
  const position = gl.getAttribLocation(program,'aPosition'), tint = gl.getAttribLocation(program,'aColor');
  gl.enableVertexAttribArray(position);
  gl.enableVertexAttribArray(tint);
  const terrain = gl.createBuffer(), actors = gl.createBuffer();
  let count = 0, current, lastDynamic, actorCount = 0;
  const upload = (buffer,mesh) => {
    gl.bindBuffer(gl.ARRAY_BUFFER,buffer);
    gl.bufferData(gl.ARRAY_BUFFER,new Float32Array(mesh.data),gl.STATIC_DRAW);
    return mesh.data.length / 6;
  };
  const draw = (buffer, length) => {
    gl.bindBuffer(gl.ARRAY_BUFFER,buffer);
    gl.vertexAttribPointer(position,3,gl.FLOAT,false,24,0);
    gl.vertexAttribPointer(tint,3,gl.FLOAT,false,24,12);
    gl.drawArrays(gl.TRIANGLES,0,length);
  };
  return (mesh, dynamic, view, dusk) => {
    if(mesh!==current) {
      count=upload(terrain,mesh);
      current=mesh;
    }
    const { center, right, up, depth, span } = view;
      for(const [name,value] of Object.entries({Center:center,Right:right,Up:up,Depth:depth}))
        gl.uniform3fv(uniforms[name],value);
      gl.uniform2fv(uniforms.Span,span);
    gl.uniform1f(uniforms.Dusk,dusk);
    gl.uniform1f(uniforms.Perspective,view.firstPerson ? 1 : 0);
    gl.viewport(0,0,canvas.width,canvas.height);
    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
    draw(terrain,count);
    if (lastDynamic !== dynamic) {
      actorCount = upload(actors,dynamic);
      lastDynamic = dynamic;
    }
    draw(actors,actorCount);
  };
}

const subtract = (a,b) => a.map((value,i)=>value-b[i]);
const bounds = (value, min, max) => Math.max(min, Math.min(max, value));

function clipNear(points) {
  const clipped = [];
  for (let i=0; i<points.length; i++) {
    const a=points[i], b=points[(i+1)%points.length];
    if (a[2]>=.05) clipped.push(a);
    if ((a[2]>=.05)!==(b[2]>=.05)) {
      const amount=(.05-a[2])/(b[2]-a[2]);
      clipped.push([a[0]+(b[0]-a[0])*amount,a[1]+(b[1]-a[1])*amount,.05]);
    }
  }
  return clipped;
}

function project(view, [x,y,z]) {
  const divisor=view.firstPerson?z:1;
  return [(x/view.span[0]/divisor+1)*view.width/2,(1-y/view.span[1]/divisor)*view.height/2];
}

function softwarePainter(canvas) {
  const ctx=canvas.getContext('2d',{alpha:true});
  if(!ctx) throw new Error('浏览器未能启动三维画布。');
  return (mesh,dynamic,view,dusk) => {
    const {width,height,depth,center,right,up,firstPerson}=view;
    ctx.setTransform(canvas.width/width,0,0,canvas.height/height,0,0);
    ctx.clearRect(0,0,width,height);
    const visible=[];
    for (const source of [mesh.faces,dynamic.faces]) for(const {points,c} of source) {
      const ocean=points.some(([x,,z])=>x < -45 || x > 65 || z < -45 || z > 65);
      if(ocean&&!firstPerson)continue;
      if(firstPerson&&!ocean&&points.every(([x,,z])=>Math.abs(x-center[0])+Math.abs(z-center[2])>43))continue;
      let space=points.map(point=>{
        const p=subtract(point,center);
        return [dot(p,right),dot(p,up),dot(p,depth)];
      });
      if(firstPerson)space=clipNear(space);
      if(space.length<3)continue;
      const projected=space.map(p=>project(view,p));
      if(projected.every(([x])=>x<0)||projected.every(([x])=>x>width)||
         projected.every(([,y])=>y<0)||projected.every(([,y])=>y>height))continue;
      const average=space.reduce((sum,p)=>sum+p[2],0)/space.length;
      visible.push({projected,c,z:ocean?-1e5:firstPerson?-average:average,average});
    }
    visible.sort((a,b)=>a.z-b.z);
    for(const {projected,c,average} of visible){
      const haze=firstPerson?bounds((average-9)/35,0,.56):0;
      const rgb=c.map((v,i)=>Math.round(255*(v*(1-dusk*[.52,.39,.21][i])*(1-haze)+haze*[.66,.78,.75][i])));
      ctx.fillStyle=`rgb(${rgb.join(',')})`;
      ctx.beginPath();
      projected.forEach(([x,y],i)=>i?ctx.lineTo(x,y):ctx.moveTo(x,y));
      ctx.closePath();
      ctx.fill();
    }
  };
}

function tileHeight(world,x,z) {
  return ground(x,z,world.tiles[z*world.width+x],world.width,world.height);
}

function playerEye(s) {
  const w=s.world;
  return [w.x,s.swimming?-.08:tileHeight(w,w.x,w.y)+.97,w.y];
}

function terrainHit(view,s,px,py) {
  const w=s.world;
  const u=(2*px/view.width-1)*view.span[0];
  const v=(1-2*py/view.height)*view.span[1];
  const ray=view.depth.map((value,i)=>value+view.right[i]*u+view.up[i]*v);
  const [cx,cy,cz]=view.center;
  for(let d=.1;d<=4;d+=.075){
    const xWorld=cx+ray[0]*d,y=cy+ray[1]*d,zWorld=cz+ray[2]*d;
    const x=Math.round(xWorld),z=Math.round(zWorld);
    if (x<0||z<0||x>=w.width||z>=w.height||(x===w.x&&z===w.y))continue;
    const i=z*w.width+x;
    const tile=w.tiles[i], base=tileHeight(w,x,z);
    if(y<=base+.05)return [x,z];
    const solidHeight=tile===1?2.3:tile===2?.72:tile===3?1.73:
      s.structures[i]?.includes('lantern')?.9:s.structures[i]?.5:
      s.supplyCaches.includes(i)&&!s.openedSupplies.includes(i)?.4:
      w.mobs.some(m=>m.x===x&&m.y===z&&m.hp>0)?1.25:.0;
    if(solidHeight>0 && y<=base+solidHeight &&
      Math.abs(xWorld-x)<.44 && Math.abs(zWorld-z)<.44)return [x,z];
  }
  return null;
}

export async function islandMap(canvas) {
  const gl=canvas.getContext('webgl',{alpha:true,antialias:true});
  const render=gl?webglPainter(gl,canvas):softwarePainter(canvas);
  let models;
  if(gl){
    try {
      const r=await fetch(new URL('./art/island-meshes.json',import.meta.url));
      if(r.ok)models=await r.json();
    } catch { /* The same map remains playable with built-in meshes. */ }
  }
  let terrain,lastTerrain,view,state,actorKey,actors;
  let visual,segment,renderHandle,lastFrame=0;
  let viewport;
  const paintFrame=(now)=>{
    renderHandle=null;
    if(!viewport||!state||!canvas.clientWidth)return;
    if(!gl&&segment&&now-lastFrame<25){renderHandle=requestAnimationFrame(paintFrame);return;}
    lastFrame=now;
    const { selected, camera, overview, width, height }=viewport;
    const w=state.world, aspect=width/height;
    if(segment){
      const t=bounds((now-segment.start)/segment.duration,0,1);
      const eased=t*t*(3-2*t);
      visual=segment.from.map((value,i)=>value+(segment.to[i]-value)*(i===1?eased:t));
      if(t>=1)segment=null;
    }
    const a=overview?.57:camera.yaw, e=overview?.79:camera.pitch;
    const n=Math.sin(a),c=Math.cos(a),se=Math.sin(e),ce=Math.cos(e);
    const right=overview?[c,0,-n]:[-c,0,n];
    const up=[-n*se,ce,-c*se];
    const depth=[n*ce,se,c*ce];
    const sy=overview?Math.max(w.height*.67,w.width*.70/aspect):.65;
    const center=overview?[(w.width-1)/2,.42,(w.height-1)/2]:visual;
    const span=[sy*aspect,sy];
    view={right,up,depth,span,center,width,height,firstPerson:!overview};
    render(terrain,actors,view,(w.turns%32)>=23?.58:0);
    if(segment&&!overview)renderHandle=requestAnimationFrame(paintFrame);
  };
  return {
    reset(){
      if(renderHandle!==null)cancelAnimationFrame(renderHandle);
      renderHandle=null; visual=null; segment=null; viewport=null;state=null;
    },
    paint(s,selected,camera,overview) {
      const w=s.world,width=canvas.clientWidth,height=canvas.clientHeight;
      if(!width||!height)return;
      const previous=state?.world, changed=s!==state;
      state=s;
      viewport={selected,camera,overview,width,height};
      canvas.dataset.view=overview?'map':'ground';
      canvas.dataset.dusk=(w.turns%32)>=23?'yes':'no';
      const dpr=Math.min(devicePixelRatio||1,gl?1.4:1.05);
      const pixelWidth=Math.round(width*dpr),pixelHeight=Math.round(height*dpr);
      if(canvas.width!==pixelWidth||canvas.height!==pixelHeight){
        canvas.width=pixelWidth;canvas.height=pixelHeight;
      }
      if(changed||!terrain){
        const key=JSON.stringify([w.seed,w.tiles,s.structures,s.houseProject?.phase]);
        if(key!==lastTerrain){terrain=staticMesh(s,models,!gl);lastTerrain=key;}
      }
      const next=playerEye(s);
      if(!visual)visual=next;
      if(previous && (previous.x!==w.x||previous.y!==w.y))
        segment={from:visual,to:next,start:performance.now(),duration:s.swimming?286:205};
      const nextActorKey=JSON.stringify([s.world.turns,s.world.x,s.world.y,w.mobs,w.friends,s.animals,
        selected,overview,s.openedSupplies,s.lostPacks,s.rareSupplyCaches]);
      if(actorKey!==nextActorKey) {
        actors=sceneMesh(s,selected,overview,!gl);
        actorKey=nextActorKey;
      }
      if(renderHandle==null){
        // A synchronous frame updates map picking before any submitted action.
        paintFrame(performance.now());
        if(!segment)renderHandle=null;
      }
    },
    pick(px,py) {
      if (!view||!state)return null;
      if(view.firstPerson){
        // Aim from the committed player square while the visual camera eases.
        return terrainHit({...view,center:playerEye(state)},state,px,py);
      }
      const w=state.world;
      let selected=null,min=Infinity;
      for(let z=0;z<w.height;z++)for(let x=0;x<w.width;x++){
        const delta=subtract([x,tileHeight(w,x,z),z],view.center);
        const [sx,sy]=project(view,[dot(delta,view.right),dot(delta,view.up),dot(delta,view.depth)]);
        const distance=(sx-px)**2+(sy-py)**2;
        if(distance<min){min=distance;selected=[x,z];}
      }
      return min < (view.height/view.span[1] * .44)**2 ? selected : null;
    },
    direction(dx,dy,camera) {
      const n=Math.sin(camera.yaw),c=Math.cos(camera.yaw);
      const x=-dy*n-dx*c,z=-dy*c+dx*n;
      return Math.abs(x)>Math.abs(z)?[Math.sign(x),0]:[0,Math.sign(z)];
    },
    aim() {
      return view?.firstPerson ? this.pick(view.width/2,view.height/2) : null;
    }
  };
}
