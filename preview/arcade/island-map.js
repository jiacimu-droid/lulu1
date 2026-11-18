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
varying vec3 vColor;
void main() {
  vec3 p = aPosition - uCenter;
  gl_Position = vec4(dot(p, uRight) / uSpan.x, dot(p, uUp) / uSpan.y, -dot(p, uDepth) / 90.0, 1.0);
  vColor = aColor;
}`,
  fragment: `precision mediump float;
varying vec3 vColor;
uniform float uDusk;
void main() {
  vec3 color = vColor * mix(vec3(1.0), vec3(0.53, 0.63, 0.82), uDusk);
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
  polygon(points, hex, shade = 1) {
    const c = color(hex, shade);
    this.faces.push({points, c});
    for (let i = 1; i < points.length - 1; i++)
      for (const vertex of [points[0], points[i], points[i + 1]]) this.data.push(...vertex, ...c);
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
  if (tile === 8) return -.32;
  if (x < 9 && z < 8) return .48;
  const edge = Math.min(x, z, w - 1 - x, h - 1 - z);
  return .28 + Math.min(3, Math.max(0, edge - 1)) * .09
    + Math.max(0, 1 - Math.hypot((x - 18) / 9, (z - 11) / 7)) * 1.4;
}

function pine(m, x, y, z, rough) {
  const height = .76 + rough * .61;
  m.box(x, y, z, .15, height * .9, .16, 0x7d533d);
  m.peak(x, y + .25, z, .87, height, 0x396650);
  m.peak(x, y + .49, z, .72, height, 0x53835d);
  m.peak(x, y + .73, z, .54, height, 0x83a873);
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
    m.box(x,y+.36,z,.69,.13,.64,0xca975e);
    m.box(x-.12,y+.49,z,.25,.035,.18,0xb5c3b3);
  } else if (['lantern','crystal_lantern','campfire'].includes(kind)) {
    m.box(x,y,z,.50,.14,.50,0x8b7c61);
    if (kind !== 'campfire') m.box(x,y+.14,z,.13,.5,.13,0x7e5741);
    m.peak(x,y+(kind==='campfire'?.14:.63),z,.35,.37,kind==='crystal_lantern'?0x9ee4e2:0xffcc72);
  } else if (kind === 'wall' || kind === 'door') {
    m.box(x,y,z,.98,kind==='wall'?.84:.66,.93,0xbf8e5e,0xd9ae79);
    if (kind === 'door') m.box(x,y,z+.47,.54,.60,.03,0x765345);
  } else if (kind === 'pet_house') {
    m.box(x,y,z,.65,.44,.58,0xd6ad80);
    m.peak(x,y+.44,z,.78,.36,0xa26b54);
  } else if (kind === 'bed') {
    m.box(x,y,z,.76,.17,.52,0x956449);
    m.box(x+.06,y+.17,z,.60,.07,.49,0x91b8b1);
    m.box(x-.26,y+.17,z,.19,.10,.48,0xf4e4cc);
  } else if (kind === 'garden') {
    m.box(x,y,z,.84,.12,.8,0x815944);
    for (const dx of [-.24,0,.24]) m.peak(x+dx,y+.12,z,.19,.23,0x9cb970);
  } else {
    for (let i=-2;i<=2;i++) m.box(x,y,z+i*.18,.96,.11,.17,0xc99d6d);
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

function staticMesh(s) {
  const m = new Mesh();
  const w = s.world;
  const crystals = new Set(s.crystalTiles);
  m.box(w.width/2,-.64,w.height/2,190,.04,190,0x659d9d);
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
        m.box(x,-.56,z,1,.012,1,nearLand?0x80baaa:0x72a8a4);
        if(n>.48) m.box(x+.12,-.54,z+.11,.45,.014,.028,nearLand?0xb6d8c3:0x90bbbb);
      }
      continue;
    }
    const y = ground(x,z,tile,w.width,w.height);
    const edge = x===1 || z===1 || x===w.width-2 || z===w.height-2;
    const path = (z===5 || x===5 || tile===7) && ![1,2,3].includes(tile);
    const top = edge ? 0xd5c295 : path ? 0xb5aa7e : [0x86a368,0x8eaa71,0x94ad72,0x82a36e][Math.floor(n*4)];
    m.box(x,-.63,z,1,y+.58,1,edge?0xa19073:0x85765d);
    m.box(x,y-.053,z,1,.053,1,top);
    if (tile === 1) pine(m,x,y,z,n);
    if (tile === 2) {
      m.box(x-.09,y,z+.03,.55,.38,.63,0x8e9792,0xb5b5a3);
      m.box(x+.19,y,z-.16,.41,.60,.36,crystals.has(i)?0x68a9b0:0x909b95);
      if (crystals.has(i)) m.peak(x+.19,y+.60,z-.16,.34,.31,0xa2d5de);
    }
    if (tile === 3) building(m,'wall',x,y,z);
    if (tile === 4 && !s.structures[i]) building(m,'floor',x,y,z);
    if (tile === 0 && !path && !edge && !s.structures[i] && n>.76) {
      m.peak(x-.3,y,z+.27,.13,.15,0x527e55);
      m.peak(x-.20,y,z+.28,.09,.19,n>.95?0xe8d19f:0xaac385);
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
    m.peak(x,ground(x,z,3,w.width,w.height)+.72,z,3.04,1.15,p.style==='pine'?0x4d7568:0xad6d4e);
  }
  // A sculpted sea stack and its spillway are just beyond the collision board.
  for (const [x,z,w,d,h] of [[12.8,-2.2,2.8,1.7,.98],[12.6,-2.55,2.25,1.31,1.76],
    [12.28,-2.67,1.44,1.08,2.28],[12.25,-2.72,1.34,1.0,2.40]]) {
    m.box(x,-.55,z,w,h,d,0x7e8880,h>2.3?0x8fac78:0x9fa494);
  }
  m.polygon([[11.94,1.87,-2.22],[12.38,1.87,-2.22],[12.51,.42,-1.35],[11.98,.42,-1.35]],0xa2cdc7);
  m.polygon([[12.19,1.87,-2.22],[12.39,1.87,-2.22],[12.53,.42,-1.35],[12.32,.42,-1.35]],0xd1e5d8);
  m.polygon([[11.98,.41,-1.34],[12.53,.41,-1.34],[12.65,-.50,-1.23],[12.00,-.50,-1.23]],0x9bc6c0);
  for (const [x,z,w] of [[12.18,-1.26,.71],[12.49,-1.04,.89],[12.04,-.83,.48],[12.52,-.68,.28]])
    m.box(x,-.505,z,w,.012,.037,0xc4ddd2);
  pine(m,12.69,1.83,-2.7,.22);
  pine(m,11.92,1.83,-2.92,.37);
  // Detached silhouettes frame the playable coast and are never selectable.
  for (const [x,z,h] of [[-2,3,.32],[-3,17,.46],[29,5,.50],[29,18,.76],[10,23,.39],[15.4,-3,.53]]) {
    m.box(x,-.53,z,1.25,h,1.48,0x8f9487);
    m.box(x-.20,-.53,z+.29,.45,h+.17,.63,0xa4a298);
  }
  return m;
}

function sceneMesh(s, selection) {
  const w = s.world;
  const m = new Mesh();
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
  highlight(w.x,w.y,0xe8ead3);
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
    m.box(mob.x,y,mob.y,.56,.38,.57,0x8e676b);
    m.peak(mob.x,y+.38,mob.y,.56,.24,0xba8a79);
  }
  for (const a of w.friends) if (a.hp>0) person(m,a.x,hi(a.x,a.y),a.y,0x5b9eac);
  person(m,w.x,hi(w.x,w.y),w.y,0xd88158,1.12);
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
  gl.clearColor(.37,.57,.59,1);
  const uniforms = Object.fromEntries(['Center','Right','Up','Depth','Span','Dusk'].map(name =>
    [name,gl.getUniformLocation(program,'u'+name)]));
  const position = gl.getAttribLocation(program,'aPosition'), tint = gl.getAttribLocation(program,'aColor');
  gl.enableVertexAttribArray(position);
  gl.enableVertexAttribArray(tint);
  const terrain = gl.createBuffer(), actors = gl.createBuffer();
  let count = 0, current;
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
    gl.viewport(0,0,canvas.width,canvas.height);
    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
    draw(terrain,count);
    draw(actors,upload(actors,dynamic));
  };
}

function softwarePainter(canvas) {
  const ctx=canvas.getContext('2d',{alpha:false});
  if(!ctx) throw new Error('浏览器未能启动三维画布。');
  return (mesh,dynamic,view,dusk) => {
    const {width,height,depth}=view;
    ctx.setTransform(canvas.width/width,0,0,canvas.height/height,0,0);
    ctx.fillStyle=dusk?'#4c737a':'#659d9d';
    ctx.fillRect(0,0,width,height);
    const visible=[];
    for(const {points,c} of [...mesh.faces,...dynamic.faces]) {
      // The open sea already fills the canvas. Its large floor needs no sort.
      if(points.some(([x,,z])=>x < -45 || x > 65 || z < -45 || z > 65))continue;
      const projected=points.map(point=>project(view,...point));
      if(projected.every(([x])=>x<0)||projected.every(([x])=>x>width)||
         projected.every(([,y])=>y<0)||projected.every(([,y])=>y>height))continue;
      visible.push({projected,c,z:points.reduce((sum,p)=>sum+dot(p,depth),0)/points.length});
    }
    visible.sort((a,b)=>a.z-b.z);
    for(const {projected,c} of visible){
      ctx.fillStyle=`rgb(${c.map((v,i)=>Math.round(v*255*(1-dusk*[.47,.37,.18][i]))).join(',')})`;
      ctx.beginPath();
      projected.forEach(([x,y],i)=>i?ctx.lineTo(x,y):ctx.moveTo(x,y));
      ctx.closePath();
      ctx.fill();
    }
  };
}

function project(view,x,y,z) {
  const v=[x-view.center[0],y-view.center[1],z-view.center[2]];
  return [(dot(v,view.right)/view.span[0]+1)*view.width/2,
    (1-dot(v,view.up)/view.span[1])*view.height/2];
}

export function islandMap(canvas) {
  const gl=canvas.getContext('webgl',{alpha:false,antialias:true});
  const render=gl?webglPainter(gl,canvas):softwarePainter(canvas);
  let terrain,lastTerrain,view,state;
  return {
    paint(s,selected,angle,overview) {
      const w=s.world,width=canvas.clientWidth,height=canvas.clientHeight;
      if(!width||!height)return;
      state=s;
      const dpr=Math.min(devicePixelRatio||1,1.65);
      const pixelWidth=Math.round(width*dpr),pixelHeight=Math.round(height*dpr);
      if(canvas.width!==pixelWidth||canvas.height!==pixelHeight){
        canvas.width=pixelWidth;canvas.height=pixelHeight;
      }
      const key=JSON.stringify([w.seed,w.tiles,s.structures,s.houseProject?.phase]);
      if(key!==lastTerrain) {
        terrain=staticMesh(s);
        lastTerrain=key;
      }
      const a=.57+angle*Math.PI/2,elevation=.79,c=Math.cos(a),n=Math.sin(a);
      const right=[c,0,-n],up=[-n*Math.sin(elevation),Math.cos(elevation),-c*Math.sin(elevation)];
      const depth=[n*Math.cos(elevation),Math.sin(elevation),c*Math.cos(elevation)];
      const aspect=width/height,sy=overview?Math.max(13.7,18.4/aspect):Math.max(4.45,4.15/aspect);
      const span=[sy*aspect,sy];
      const center=overview?[13.5,.42,10.5]:[w.x,.65,w.y];
      view={right,up,depth,span,center,width,height};
      render(terrain,sceneMesh(s,selected),view,(w.turns%32)>=23?.42:0);
    },
    pick(px,py) {
      if(!view || !state) return null;
      const w=state.world;
      let selected=null, min=Infinity;
      for(let z=0;z<w.height;z++) for(let x=0;x<w.width;x++) {
        const tile=w.tiles[z*w.width+x], y=ground(x,z,tile,w.width,w.height);
        for(const level of [y,...(tile===1?[y+.7,y+1.35]:tile===2?[y+.4]:[])]) {
          const [sx,sy]=project(view,x,level,z);
          const d=(sx-px)**2+(sy-py)**2;
          if(d<min){min=d;selected=[x,z];}
        }
      }
      return min < (view.height/view.span[1] * .44)**2 ? selected : null;
    },
    direction(dx,dy,angle) {
      const directions=[[dx,dy],[dy,-dx],[-dx,-dy],[-dy,dx]];
      return directions[angle % 4];
    }
  };
}
