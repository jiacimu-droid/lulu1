import { createServer } from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { arcadeRequest } from './arcade/gateway.mjs';

const root=fileURLToPath(new URL('.',import.meta.url));
const lobbyArt=new Map([
  ['/arcade/art/island.png', new URL('../app/src/main/res/drawable-nodpi/game_island_lobby_art.png', import.meta.url)],
  ['/arcade/art/relic.webp', new URL('../app/src/main/res/drawable-nodpi/game_relic_camp.webp', import.meta.url)],
]);
const port=Number(process.env.PORT||4173);
const mime={'.html':'text/html; charset=utf-8','.css':'text/css; charset=utf-8','.js':'text/javascript; charset=utf-8','.svg':'image/svg+xml','.png':'image/png','.jpg':'image/jpeg','.webp':'image/webp'};

createServer(async(req,res)=>{
  try{
    const raw=decodeURIComponent((req.url||'/').split('?')[0]);
    if(raw==='/arcade'){
      res.writeHead(308,{location:'/arcade/'});
      res.end();
      return;
    }
    if(raw==='/arcade/rules') return arcadeRequest(req,res);
    const art=lobbyArt.get(raw);
    if(art){
      const body=await readFile(art);
      res.writeHead(200,{'content-type':mime[extname(raw)],'cache-control':'no-store'});
      res.end(body);
      return;
    }
    const relative=raw==='/'?'index.html':raw.replace(/^\/+/, '');
    const candidate=normalize(join(root,relative));
    if(!candidate.startsWith(root)){res.writeHead(403);res.end('Forbidden');return;}
    if (['.kt','.sh'].includes(extname(candidate))) { res.writeHead(404); res.end(); return; }
    const info=await stat(candidate);
    const file=info.isDirectory()?join(candidate,'index.html'):candidate;
    const body=await readFile(file);
    res.writeHead(200,{'content-type':mime[extname(file)]||'application/octet-stream','cache-control':'no-store'});
    res.end(body);
  }catch(error){
    res.writeHead(404,{'content-type':'text/plain; charset=utf-8'});
    res.end('预览文件不存在');
  }
}).listen(port,process.env.HOST,()=>console.log(`Lulu preview: http://localhost:${port}`));
