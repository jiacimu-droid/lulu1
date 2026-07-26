import { createServer } from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const root=fileURLToPath(new URL('.',import.meta.url));
const port=Number(process.env.PORT||4173);
const mime={'.html':'text/html; charset=utf-8','.css':'text/css; charset=utf-8','.js':'text/javascript; charset=utf-8','.svg':'image/svg+xml','.png':'image/png','.jpg':'image/jpeg'};

createServer(async(req,res)=>{
  try{
    const raw=decodeURIComponent((req.url||'/').split('?')[0]);
    const relative=raw==='/'?'index.html':raw.replace(/^\/+/, '');
    const candidate=normalize(join(root,relative));
    if(!candidate.startsWith(root)){res.writeHead(403);res.end('Forbidden');return;}
    const info=await stat(candidate);
    const file=info.isDirectory()?join(candidate,'index.html'):candidate;
    const body=await readFile(file);
    res.writeHead(200,{'content-type':mime[extname(file)]||'application/octet-stream','cache-control':'no-store'});
    res.end(body);
  }catch(error){
    res.writeHead(404,{'content-type':'text/plain; charset=utf-8'});
    res.end('预览文件不存在');
  }
}).listen(port,()=>console.log(`Lulu preview: http://localhost:${port}`));
