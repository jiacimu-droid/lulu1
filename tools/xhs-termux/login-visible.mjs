/**
 * Interactive graphical login for the on-phone Termux Chromium + Termux:X11.
 * Uses a normal visible browser window, keeps its profile locally, exports
 * ONLY its Xiaohongshu cookies for Lulu's read-only MCP, then closes Chromium.
 * No remote debugging server, cloud service, or cookie paste is needed.
 */
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import readline from "node:readline/promises";
import { launchBrowser } from "@the-thing/xiaohongshu-mcp/src/utils/browser.js";

const base = path.join(os.homedir(), ".lulu", "xhs-termux");
const profile = path.join(base, "visible-chrome-profile");
const cookiePath = path.join(base, "cookies.json");
const loginUrl = "https://www.xiaohongshu.com/explore";
const sleep = ms => new Promise(r => setTimeout(r,ms));
const marker = ".main-container .user .link-wrapper .channel";
const risks = url => {
  try {
    const u=new URL(url);
    if (!u.pathname.includes("/website-login/error")) return "";
    return `小红书页面提示访问限制 ${u.searchParams.get("error_code")||""}：${u.searchParams.get("error_msg")||"请查看图形浏览器里的提示"}`;
  } catch {return "";}
};

if (!process.env.DISPLAY) {
  console.error("缺少 DISPLAY；请先运行 termux-x11 :1 &，并用 DISPLAY=:1 运行本脚本。");
  process.exit(2);
}
fs.mkdirSync(base,{recursive:true,mode:0o700});
fs.mkdirSync(profile,{recursive:true,mode:0o700});

let browser;
try {
  console.log("正在打开真正有界面的 Chromium（请切换到 Termux:X11 查看）…");
  browser=await launchBrowser({
    headless:false,
    userDataDir:profile,
    defaultViewport:null,
    args:["--window-size=1080,760","--disable-dev-shm-usage","--no-sandbox"]
  });
  const pages=await browser.pages();
  const page=pages[0] || await browser.newPage();
  await page.goto(loginUrl,{waitUntil:"domcontentloaded",timeout:60000});
  await sleep(1500);
  console.log("浏览器当前地址：" + page.url());
  const risk=risks(page.url());
  if (risk) {
    console.error("⚠ "+risk);
    console.log("浏览器里的限制若依旧存在，图形化登录也不能保证有效；不要反复请求。");
    process.exitCode=1;
  } else {
    console.log("请进入 Termux:X11 中的 Chromium，手动在小红书网页登录。");
    console.log("可以使用网页允许的短信/扫码方式；账号、验证码不要输入到 Termux 终端里。");
    const rl=readline.createInterface({input:process.stdin,output:process.stdout});
    await rl.question("网页确认登录成功后，切回 Termux 并按回车保存登录态 > ");
    rl.close();
    const active=page.isClosed()?((await browser.pages()).find(p=>!p.isClosed())):page;
    if (!active) throw new Error("浏览器页面已关闭");
    await active.goto(loginUrl,{waitUntil:"domcontentloaded",timeout:45000}).catch(()=>{});
    await sleep(1700);
    const err=risks(active.url());
    if (err) throw new Error(err);
    const cookies=await active.cookies("https://www.xiaohongshu.com");
    const cookieSession=cookies.find(c=>c.name==="web_session" && c.value?.length>=8);
    const hasLoginMarker=!!(await active.$(marker).catch(()=>null));
    if (!cookieSession && !hasLoginMarker) {
      console.error("尚未检测到小红书网页的已登录会话，未保存 Cookie。");
      console.log("请确认 Termux:X11 里显示的是已登录状态，而不仅是登录界面。");
      process.exitCode=1;
    } else if (!cookies.length) {
      console.error("浏览器未返回可保存的 Cookie。");
      process.exitCode=1;
    } else {
      const temp=cookiePath+".tmp";
      fs.writeFileSync(temp,JSON.stringify(cookies,null,2),{mode:0o600});
      fs.renameSync(temp,cookiePath);
      fs.chmodSync(cookiePath,0o600);
      console.log("✅ 已保存小红书登录 Cookie 到 Termux 私有目录。");
      console.log("现在可以回露露机检查小红书登录状态；切勿分享 cookies.json。");
    }
  }
} catch(e) {
  console.error("图形浏览器登录尚未成功："+e.message);
  process.exitCode=1;
} finally {
  await browser?.close().catch(()=>{});
}
