/**
 * Android/Termux browser adapter for the pinned upstream @the-thing/xiaohongshu-mcp.
 * The published npm source is not guaranteed to be byte-identical to GitHub.
 * Replace ONLY the installed browser-launch module after checking it is
 * plausibly the expected module; retain a backup and keep login data untouched.
 */
import fs from "node:fs";
import { fileURLToPath } from "node:url";

const path = fileURLToPath(new URL("./node_modules/@the-thing/xiaohongshu-mcp/src/utils/browser.js", import.meta.url));
const backup = path + ".lulu-original";
const stamp = "// Lulu Termux read-only Chromium launch adapter";
const original = fs.existsSync(backup) ? fs.readFileSync(backup, "utf8") : fs.readFileSync(path, "utf8");
if (!original.includes("launchBrowser") || !original.includes("puppeteer")) {
  console.error("[lulu-xhs] npm installed an unexpected browser module. Refusing to modify it.");
  console.error("Path: " + path);
  process.exit(1);
}
const replacement = `${stamp}
import puppeteer from "puppeteer-core";
import fs from "node:fs";

export function resolveUserDataDir() {
  return process.env.XHS_USER_DATA_DIR || undefined;
}
export async function launchBrowser(options = {}) {
  const executablePath = process.env.PUPPETEER_EXECUTABLE_PATH ||
    ["/data/data/com.termux/files/usr/bin/chromium-browser",
     "/data/data/com.termux/files/usr/bin/chromium",
     "/usr/bin/chromium"].find(p => fs.existsSync(p));
  if (!executablePath || !fs.existsSync(executablePath)) {
    throw new Error("Chromium executable not found. Install chromium in Termux.");
  }
  const requiredFlags = [
    "--no-sandbox",
    "--disable-setuid-sandbox",
    "--disable-dev-shm-usage",
    "--disable-gpu",
    "--disable-blink-features=AutomationControlled",
    "--no-first-run",
    "--no-default-browser-check",
    "--disable-infobars",
    "--lang=zh-CN",
  ];
  if (process.env.XHS_PROXY) requiredFlags.push("--proxy-server=" + process.env.XHS_PROXY);
  const args = [...new Set([...requiredFlags, ...(options.args || [])])];
  const userDataDir = resolveUserDataDir();
  return puppeteer.launch({
    executablePath,
    headless: process.env.XHS_HEADLESS !== "false",
    ...(userDataDir ? {userDataDir} : {}),
    defaultViewport: {width:1366,height:900},
    ...options,
    args
  });
}
`;
if (fs.readFileSync(path,"utf8").startsWith(stamp)) {
  console.log("[lulu-xhs] Android browser adapter already installed");
  process.exit(0);
}
if (!fs.existsSync(backup)) fs.writeFileSync(backup, original, {mode:0o600});
fs.writeFileSync(path, replacement);
console.log("[lulu-xhs] Applied Termux Chromium adapter; original module backed up");
