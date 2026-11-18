import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';

let child;
let pending = [];
const script = fileURLToPath(new URL('./run-rules.sh', import.meta.url));

function startRules() {
  const process = spawn('bash', [script], { stdio: ['pipe', 'pipe', 'inherit'] });
  child = process;
  const stop = () => {
    if (child !== process) return;
    child = undefined;
    for (const task of pending) task.reject(new Error('游戏引擎暂时不可用，请重新开始。'));
    pending = [];
  };
  process.once('error', stop);
  process.once('close', stop);
  process.stdin.on('error', stop);
  createInterface({ input: process.stdout }).on('line', line => {
    if (child === process) pending.shift()?.resolve(line);
  });
  return process;
}

export async function arcadeRequest(req, res) {
  const send = (status, value) => {
    res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
    res.end(value);
  };
  const fail = (status, error) => send(status, JSON.stringify({ error }));
  if (req.method !== 'POST' || !req.headers['content-type']?.startsWith('application/json') ||
      req.headers['sec-fetch-site'] === 'cross-site') return fail(405, '请求方式无效。');
  if (pending.length >= 32) return fail(503, '游戏引擎正忙，请再试一次。');

  let raw = '';
  try {
    for await (const chunk of req) {
      raw += chunk;
      if (raw.length > 4096) return fail(413, '请求过大。');
    }
    const input = JSON.parse(raw);
    if (!input || !['start', 'act', 'view'].includes(input.verb)) return fail(400, '请求无效。');
    if (pending.length >= 32) return fail(503, '游戏引擎正忙，请再试一次。');
    const engine = child ?? startRules();
    const result = await new Promise((resolve, reject) => {
      pending.push({ resolve, reject });
      engine.stdin.write(JSON.stringify(input) + '\n');
    });
    send(200, result);
  } catch (error) {
    if (error instanceof SyntaxError) fail(400, '请求无效。');
    else fail(503, error.message);
  }
}
