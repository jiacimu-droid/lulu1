#!/usr/bin/env python3
import hashlib, json, os, re
from datetime import datetime, timezone
from pathlib import Path

root = Path(__file__).resolve().parents[1]
allowed = {'.kt','.kts','.java','.xml','.py','.js','.css','.html','.json','.md','.yml','.yaml'}
files=[]
symbols={}
fingerprint=hashlib.sha256()
pattern=re.compile(r'^\s*(?:data\s+|sealed\s+|enum\s+)?(?:class|interface|object|fun)\s+([A-Za-z_][A-Za-z0-9_]*)',re.M)
for base in ('app','scripts','preview'):
    folder=root/base
    if not folder.exists(): continue
    for path in sorted(folder.rglob('*')):
        if not path.is_file() or path.suffix.lower() not in allowed: continue
        if any(part in {'build','.gradle','node_modules'} for part in path.parts): continue
        rel=path.relative_to(root).as_posix(); raw=path.read_bytes(); text=raw.decode('utf-8','replace')
        fingerprint.update(rel.encode()); fingerprint.update(raw)
        found=[]
        for match in pattern.finditer(text):
            line=text.count('\n',0,match.start())+1
            found.append({'name':match.group(1),'line':line})
            symbols.setdefault(match.group(1),[]).append({'path':rel,'line':line})
        files.append({'path':rel,'lines':text.count('\n')+1,'sha256':hashlib.sha256(raw).hexdigest(),'symbols':found})
summary={'files':len(files),'lines':sum(x['lines'] for x in files),'symbols':sum(len(x['symbols']) for x in files)}
data={'schemaVersion':1,'generatedAt':datetime.now(timezone.utc).isoformat(),'commit':os.getenv('INDEX_COMMIT','local'),'branch':os.getenv('INDEX_BRANCH','local'),'sourceFingerprint':fingerprint.hexdigest(),'summary':summary,'symbolIndex':symbols,'files':files}
out=root/'docs'/'architecture'; out.mkdir(parents=True,exist_ok=True)
(out/'code-index.json').write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
md=['# Lulu1 代码索引','',f"- 基准提交：`{data['commit']}`",f"- 分支：`{data['branch']}`",f"- 已索引文件：{summary['files']}",f"- 已索引代码/文本行：{summary['lines']}",f"- 已发现符号：{summary['symbols']}",'','| 文件 | 行数 | 符号数 |','|---|---:|---:|']
md += [f"| `{x['path']}` | {x['lines']} | {len(x['symbols'])} |" for x in files]
(out/'CODE_INDEX.md').write_text('\n'.join(md)+'\n',encoding='utf-8')
