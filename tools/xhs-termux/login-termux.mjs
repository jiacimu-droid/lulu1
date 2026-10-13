/**
 * Xiaohongshu login entirely in Termux: opens its own Chromium, saves the QR
 * to the Android gallery, and persists the cookies for the read-only MCP.
 * Does not call Lulu Android app and never sends credentials to our server.
 */
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import readline from "node:readline/promises";
import { launchBrowser } from "@the-thing/xiaohongshu-mcp/src/utils/browser.js";
import { loadCookies, saveCookies } from "@the-thing/xiaohongshu-mcp/src/utils/cookies.js";

const home = os.homedir();
const root = path.join(home, ".lulu", "xhs-termux");
const gallery = path.join(home, "storage", "pictures");
const outDir = fs.existsSync(gallery) ? gallery : root;
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const loginMarker = ".main-container .user .link-wrapper .channel";
const selectors = [
  ".login-container .qrcode-img", ".qrcode-container img",
  ".login-container canvas", ".login-container svg",
  ".qrcode-container canvas", ".qrcode-container svg",
  "img[class*='qrcode']", "[class*='qrcode'] img",
  "img[class*='qr-code']", "[class*='qr-code'] canvas",
  "[class*='qr-code'] svg", "[class*='qrcode'] canvas",
  "[class*='qrcode'] svg", "img[alt*='二维码']",
  "img[alt*='扫码']"
].join(", ");
const saved = name => path.join(outDir, name);

// Platform-side access restrictions are not a broken QR selector.
// Surface the real cause immediately instead of waiting for a non-existent QR.
function xhsAccessError(rawUrl) {
  try {
    const url = new URL(rawUrl);
    if (!url.pathname.includes("/website-login/error")) return "";
    const code = url.searchParams.get("error_code") || "unknown";
    const message = url.searchParams.get("error_msg") || "Xiaohongshu declined this browser session";
    return `小红书网页拒绝访问（错误码 ${code}）：${message}。请检查当前网络、VPN/代理设置；如持续出现，请稍后使用正常浏览器验证或联系平台支持。`;
  } catch { return ""; }
}

async function visible(handle) {
  if (!handle) return false;
  const box = await handle.boundingBox().catch(() => null);
  return Boolean(box && box.width >= 80 && box.height >= 80);
}

async function findQr(page) {
  for (const frame of page.frames()) {
    for (const selector of selectors.split(", ")) {
      const node = await frame.$(selector).catch(() => null);
      if (await visible(node)) return node;
      await node?.dispose().catch(() => {});
    }
    // A visual QR can also be a canvas or SVG without a predictable class.
    // Only accept candidates near login/scan UI, not random post thumbnails.
    const handle = await frame.evaluateHandle(() => {
      const elems = [...document.querySelectorAll("img,canvas,svg")];
      return elems.map(el => {
        const r = el.getBoundingClientRect();
        if (r.width < 100 || r.height < 100 ||
            r.width > 700 || r.height > 700 ||
            r.width / r.height < 0.65 || r.width / r.height > 1.5) return null;
        const style = getComputedStyle(el);
        if (style.display === "none" || style.visibility === "hidden" ||
            Number(style.opacity) === 0) return null;
        const parent = el.closest("[class*='login'],[class*='Login'],[class*='qr'],[class*='Qr'],[class*='code'],[role='dialog']");
        const metadata = [el.getAttribute("class"), el.getAttribute("alt"),
          el.getAttribute("aria-label"), el.parentElement?.getAttribute("class")].join(" ");
        const hint = /qr|扫码|二维码/i.test(metadata);
        if (!parent && !hint) return null;
        const context = (parent?.getAttribute("class") || "") +
          " " + (parent?.textContent || "").slice(0, 250);
        const score = (hint ? 4 : 0) +
          (/qr|二维码|扫码/i.test(context) ? 3 : 0) +
          (/login|登录|dialog/i.test(context) ? 1 : 0);
        return { el, score };
      }).filter(Boolean).sort((a,b) => b.score-a.score)[0]?.el || null;
    }).catch(() => null);
    const node = handle?.asElement() || null;
    if (await visible(node)) return node;
    await handle?.dispose().catch(() => {});
  }
  return null;
}

async function clickLogin(page) {
  return page.evaluate(() => {
    const visible = e => {
      const r = e.getBoundingClientRect();
      return r.width > 0 && r.height > 0;
    };
    const preferred = document.querySelector(".login-btn, .side-bar .login-btn, .user .login");
    if (preferred && visible(preferred)) { preferred.click(); return "clicked preferred login"; }
    const controls = [...document.querySelectorAll("button,a,[role='button'],div,span")];
    const candidate = controls.find(e => visible(e) &&
      /^(登录|立即登录|登 录)$/.test((e.innerText || "").trim()) &&
      e.children.length <= 2);
    if (candidate) { candidate.click(); return "clicked text login"; }
    return "no login button found";
  }).catch(e => "login click failed: " + e.message);
}
async function selectScanMode(page) {
  return page.evaluate(() => {
    const controls = [...document.querySelectorAll("button,a,[role='tab'],div,span")];
    const e = controls.find(el => {
      const r = el.getBoundingClientRect();
      return r.width > 0 && r.height > 0 &&
        /^(扫码登录|二维码登录|扫码|使用二维码登录)$/.test((el.innerText || "").trim()) &&
        el.children.length <= 2;
    });
    if (e) {e.click();return true;}
    return false;
  }).catch(() => false);
}

fs.mkdirSync(root, {recursive:true, mode:0o700});
fs.mkdirSync(outDir, {recursive:true});
process.env.PUPPETEER_EXECUTABLE_PATH ||= [
  "/data/data/com.termux/files/usr/bin/chromium-browser",
  "/data/data/com.termux/files/usr/bin/chromium"
].find(p => fs.existsSync(p));
process.env.XHS_COOKIES_PATH ||= path.join(root, "cookies.json");
if (outDir === root) {
  console.log("⚠ 未获取手机相册权限：先退出并执行 termux-setup-storage，点击允许，再重试。");
  process.exit(2);
}

console.log("[1/3] Termux 正在启动 Chromium 并打开小红书…");
let browser;
try {
  browser = await launchBrowser({headless:true});
  const page = await browser.newPage();
  await page.goto("https://www.xiaohongshu.com/explore",
    {waitUntil:"domcontentloaded", timeout:60000});
  await loadCookies(page);
  await page.reload({waitUntil:"domcontentloaded",timeout:60000});
  await delay(2000);
  const accessError = xhsAccessError(page.url());
  if (accessError) {
    const screenshot = saved("lulu-xhs-access-error.png");
    await page.screenshot({path:screenshot,type:"png"}).catch(() => {});
    throw new Error(accessError + "。诊断截图：" + screenshot);
  }
  if (await page.$(loginMarker)) {
    await saveCookies(page);
    console.log("✅ 已登录，Cookie 已保存，露露机现在可以读取小红书。");
    process.exitCode = 0;
  } else {
    console.log("尝试打开登录窗口… " + await clickLogin(page));
    await delay(1400);
    await selectScanMode(page);
    let qr = null;
    for (let i=0; i<30; i++) {
      qr = await findQr(page);
      if (qr) break;
      if (i===8 || i===18) {
        await clickLogin(page);
        await selectScanMode(page);
      }
      await delay(1000);
    }
    const delayedError = xhsAccessError(page.url());
    if (delayedError) throw new Error(delayedError);
    const imagePath = saved(qr ? "lulu-xhs-login-qr.png" : "lulu-xhs-login-page.png");
    if (qr) {
      await qr.screenshot({path:imagePath,type:"png"});
      console.log("✅ 找到登录二维码，已存入手机相册：" + imagePath);
    } else {
      await page.screenshot({path:imagePath,type:"png"});
      console.log("⚠ 没找到可识别的二维码，已将整个登录网页截图保存至：" + imagePath);
      console.log("请先在相册查看：网页是否显示登录弹窗、验证码或访问限制。");
      console.log("当前网页地址：" + page.url());
      console.log("当前没有可扫码的二维码：不必等待或按回车，可 Ctrl+C 退出并检查网页。");
      process.exitCode = 1;
    }
    if (qr) console.log("用小红书 App 的扫一扫 → 从相册选择截图进行扫码。");
    if (!qr) {
      // No code means no login session to confirm.
      // Keep diagnostic screenshot but do not fake a success or wait for input.
    } else {
    console.log("请保持此 Termux 命令运行；完成扫码后回到此处按回车。");
    const rl = readline.createInterface({input:process.stdin,output:process.stdout});
    await rl.question("扫码后按回车检查并保存 Cookie（Ctrl+C 可退出）> ");
    rl.close();
    await delay(2500);
    await page.reload({waitUntil:"domcontentloaded",timeout:40000}).catch(()=>{});
    await delay(2000);
    if (await page.$(loginMarker)) {
      await saveCookies(page);
      console.log("✅ 检测到已登录，Cookie 已保存。回露露机检查登录状态。");
    } else {
      console.log("尚未检测到登录成功；先不覆盖原来的 Cookie。");
      console.log("如果网页确实扫码成功但判定不准，请把相册截图（遮盖私人信息）发来。");
      process.exitCode=1;
    }
    }
  }
} catch(e) {
  console.error("登录未完成：" + e.message);
  process.exitCode=1;
} finally {
  await browser?.close().catch(()=>{});
}
