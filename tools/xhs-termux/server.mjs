/**
 * Lulu on-device Xiaohongshu MCP adapter.
 * Selected upstream: @the-thing/xiaohongshu-mcp 0.2.0 (ISC)
 * ONLY exposes status, scan-login, recommendations, search and note details.
 * No publish/comment/like/favorite code is imported or callable.
 */
import express from "express";
import { randomUUID } from "node:crypto";
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { ListToolsRequestSchema, CallToolRequestSchema } from "@modelcontextprotocol/sdk/types.js";
import { launchBrowser } from "@the-thing/xiaohongshu-mcp/src/utils/browser.js";
import { loadCookies, saveCookies } from "@the-thing/xiaohongshu-mcp/src/utils/cookies.js";
import { checkLoginStatus } from "@the-thing/xiaohongshu-mcp/src/xhs/status.js";
import { searchFeeds } from "@the-thing/xiaohongshu-mcp/src/xhs/search.js";
import { getRecommendFeeds } from "@the-thing/xiaohongshu-mcp/src/xhs/feeds.js";
import { getFeedDetail } from "@the-thing/xiaohongshu-mcp/src/xhs/detail.js";

const PORT = Number(process.env.LULU_XHS_PORT || "18060");
const HOST = "127.0.0.1";
const LOGIN_SEL = ".main-container .user .link-wrapper .channel";
const QR_SEL = ".login-container .qrcode-img, .qrcode-container img, img[class*='qrcode'], [class*='qrcode'] img";
function assertXhsAccess(page) {
  let u;
  try { u = new URL(page.url()); } catch { return; }
  if (!u.pathname.includes("/website-login/error")) return;
  const code = u.searchParams.get("error_code") || "unknown";
  const message = u.searchParams.get("error_msg") || "小红书网页拒绝访问";
  throw new Error("小红书网页限制访问（错误码 " + code + "）： " + message +
    "。请检查 VPN/代理和当前网络，或稍后通过官方网页确认；当前没有可用二维码。");
}
const TTL = 4 * 60 * 1000;
const MAX = 10;
let activeLogin = null;

function expireLogin(login) {
  if (login.expired) return;
  login.expired = true;
  clearTimeout(login.timer);
  clearTimeout(login.timerDeadline);
  if (activeLogin === login) activeLogin = null;
  login.browser?.close().catch(() => {});
}
function pollLogin(login) {
  const poll = async () => {
    if (login.expired) return;
    try {
      if (await login.page.$(LOGIN_SEL)) {
        await saveCookies(login.page);
        console.log("[lulu-xhs] QR login confirmed; session saved locally");
        expireLogin(login);
        return;
      }
    } catch (e) {
      console.error("[lulu-xhs] login poll:", e.message);
    }
    if (!login.expired) login.timer = setTimeout(poll, 2500);
  };
  login.timer = setTimeout(poll, 2500);
}

async function qrLogin() {
  if (activeLogin) expireLogin(activeLogin);
  const browser = await launchBrowser({ headless: true });
  let login;
  try {
    const page = await browser.newPage();
    await page.goto("https://www.xiaohongshu.com/explore", {
      waitUntil: "domcontentloaded", timeout: 60000
    });
    await loadCookies(page);
    await page.reload({ waitUntil: "domcontentloaded", timeout: 60000 });
    assertXhsAccess(page);
    if (await page.$(LOGIN_SEL)) {
      await saveCookies(page);
      await browser.close();
      return { content: [{ type:"text", text:"已经登录，不需要再次扫码。" }] };
    }
    await page.evaluate(() => {
      const el = document.querySelector(".login-btn, .side-bar .login-btn, .user .login")
        || [...document.querySelectorAll("button, a")].find(x => (x.innerText || "").trim() === "登录");
      el?.click();
    });
    const qr = await page.waitForSelector(QR_SEL, { visible: true, timeout: 25000 })
      .catch(e => { assertXhsAccess(page); throw e; });
    const shot = await qr.screenshot({ type:"png" });
    login = { browser, page, expired:false, timer:null };
    activeLogin = login;
    pollLogin(login);
    login.timerDeadline = setTimeout(() => expireLogin(login), TTL);
    // Clear the deadline together with interval in expireLogin.
    return { content: [
      { type:"text", text:"登录二维码已生成，4 分钟内请保存到手机相册，再在小红书 App 的扫一扫中从相册选取。扫码后返回露露机测试连接；二维码和 Cookie 不上传至云端。" },
      { type:"image", mimeType:"image/png", data:shot.toString("base64") }
    ] };
  } catch (e) {
    if (login) expireLogin(login);
    else await browser.close().catch(() => {});
    throw e;
  }
}

const TOOLS = Object.freeze([
  { name:"check_login_status", description:"检查本机小红书扫码登录状态，只读。",
    inputSchema:{ type:"object",properties:{},additionalProperties:false } },
  { name:"get_login_qrcode", description:"生成同手机相册可扫描的登录二维码，不发帖不互动。",
    inputSchema:{ type:"object",properties:{},additionalProperties:false } },
  { name:"list_feeds", description:"阅读首页推荐笔记列表，只读。",
    inputSchema:{ type:"object",properties:{},additionalProperties:false } },
  { name:"search_feeds", description:"搜索真实小红书笔记，只读。",
    inputSchema:{ type:"object",properties:{keyword:{type:"string"}},required:["keyword"],additionalProperties:false } },
  { name:"get_feed_detail", description:"打开笔记并读取正文，只读。",
    inputSchema:{ type:"object",properties:{feed_id:{type:"string"},xsec_token:{type:"string"}},
      required:["feed_id","xsec_token"],additionalProperties:false } },
]);
const allow = new Set(TOOLS.map(t => t.name));
const jsonText = obj => ({content:[{type:"text",text:JSON.stringify(obj)}]});
const errorText = msg => ({isError:true,content:[{type:"text",text:String(msg).slice(0,400)}]});
async function invoke(name,args={}) {
  switch(name) {
    case "check_login_status": {
      const result = await checkLoginStatus();
      return jsonText(result);
    }
    case "get_login_qrcode": return await qrLogin();
    case "list_feeds": {
      const res = await getRecommendFeeds(MAX);
      if (!res?.success) throw new Error(res?.message || "推荐列表获取失败");
      return jsonText({feeds:res.feeds.slice(0,MAX)});
    }
    case "search_feeds": {
      const keyword = String(args.keyword||"").trim().slice(0,64);
      if (!keyword) throw new Error("请提供关键词");
      const res = await searchFeeds(keyword,MAX);
      if (!res?.success) throw new Error(res?.message||"搜索失败");
      return jsonText({feeds:res.feeds.slice(0,MAX)});
    }
    case "get_feed_detail": {
      const id = String(args.feed_id||"").trim();
      const token = String(args.xsec_token||"").trim();
      if (!/^[a-zA-Z0-9]{20,36}$/.test(id) || !token || token.length>2500) {
        throw new Error("笔记 ID 或访问令牌无效");
      }
      const result = await getFeedDetail(id,token);
      if (!result?.success || !result.detail) throw new Error(result?.message||"正文读取失败");
      // Adapt upstream's normalized "detail" to Lulu's existing "data.note" format.
      // The upstream normalized detail contains an imageUrls:string[].
      // Keep the MCP response compatible with the canonical imageList
      // objects consumed by Android and other Go-compatible clients.
      const imageList = (Array.isArray(result.detail.imageUrls)
        ? result.detail.imageUrls : []).filter(Boolean).slice(0,30)
        .map(image => typeof image === "string"
          ? {urlDefault:image}
          : {urlDefault:image.urlDefault||image.url||""});
      return jsonText({data:{note:{
        title:result.detail.title||"", desc:result.detail.desc||"",
        id:result.detail.id||id, author:result.detail.author||{},
        imageList
      }}});
    }
    default: throw new Error("只读权限拒绝：不允许发帖、评论、点赞或收藏");
  }
}
function newServer() {
  const server = new Server(
    {name:"lulu-xiaohongshu-readonly",version:"0.1.0"},
    {capabilities:{tools:{}}}
  );
  server.setRequestHandler(ListToolsRequestSchema, async()=>({tools:TOOLS}));
  server.setRequestHandler(CallToolRequestSchema, async req=>{
    const {name,arguments:args} = req.params;
    if (!allow.has(name)) return errorText("只读权限拒绝：禁止发帖、评论、点赞和收藏");
    try { return await invoke(name,args); }
    catch (e) {console.error("[lulu-xhs] "+name+": "+e.message);return errorText(e.message);}
  });
  return server;
}

const app=express();
app.disable("x-powered-by");
app.use(express.json({limit:"32kb"}));
app.get("/health",(_req,res)=>res.json({ready:true,local:true,readOnly:true}));
const sessions=new Map();
app.post("/mcp",async(req,res)=>{
  try{
    let transport;
    const sid=req.get("mcp-session-id");
    if (sid && sessions.has(sid)) {
      transport=sessions.get(sid);
    } else if (!sid && req.body?.method==="initialize") {
      transport=new StreamableHTTPServerTransport({
        sessionIdGenerator:()=>randomUUID(),
        onsessioninitialized:id=>sessions.set(id,transport)
      });
      transport.onclose=()=>{
        if(transport.sessionId) sessions.delete(transport.sessionId);
      };
      await newServer().connect(transport);
    } else {
      return res.status(400).json({error:"MCP session missing; initialize first"});
    }
    await transport.handleRequest(req,res,req.body);
  }catch(e){
    console.error("[lulu-xhs] HTTP:",e);
    if(!res.headersSent)res.status(500).json({error:"MCP error"});
  }
});
app.all("/mcp",(_req,res)=>res.status(405).end());

process.on("SIGINT",()=>{if(activeLogin)expireLogin(activeLogin);process.exit(0);});
process.on("SIGTERM",()=>{if(activeLogin)expireLogin(activeLogin);process.exit(0);});
app.listen(PORT,HOST,()=>console.log(`[lulu-xhs] Read-only MCP listening at http://${HOST}:${PORT}/mcp`));
