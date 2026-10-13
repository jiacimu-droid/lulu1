/**
 * The upstream project targets desktop Chrome. In Termux Chromium requires
 * sandbox and shared-memory launch flags. Patch the installed dependency ONLY
 * after verifying the exact upstream source marker (fail closed on changes).
 */
import fs from "node:fs";
const target = new URL("./node_modules/@the-thing/xiaohongshu-mcp/src/utils/browser.js",import.meta.url);
let code=fs.readFileSync(target,"utf8");
const signature="    const launchArgs = [\n";
const flags="    const launchArgs = [\n        '--no-sandbox',\n        '--disable-setuid-sandbox',\n        '--disable-dev-shm-usage',\n        '--disable-gpu',\n";
if(code.includes(flags)){
  console.log("[lulu-xhs] Chromium flags already present");
  process.exit(0);
}
if(!code.includes(signature)) {
  console.error("Upstream browser code changed. Refusing to modify unknown code.");
  process.exit(1);
}
code=code.replace(signature,flags);
fs.writeFileSync(target,code,"utf8");
console.log("[lulu-xhs] Applied Termux Chromium flags to the pinned upstream version");
