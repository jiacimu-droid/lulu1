// Run without an account or network: execute the production extractor in browser fixtures.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const {test} = require('node:test');
const script = fs.readFileSync('app/src/main/assets/xhs-reader.js', 'utf8');
const id = '1234567890abcdef12345678';
const other = 'abcdef123456789012345678';
const row = (token = 'token+/=') => ({id, modelType: 'note', xsecToken: token,
  noteCard: {displayTitle: '猫咪和纸箱', user: {nickname: '真实作者'}}});
function browser({path = '/explore', state = {}, text = '', elements = [], payload, api = 'homefeed', xhr} = {}) {
  const sandbox = {URL, location: {href: 'https://www.xiaohongshu.com' + path},
    document: {body: {innerText: text}, querySelectorAll: () => elements, querySelector: () => null},
    __INITIAL_STATE__: state,
    fetch: async () => ({status: 200, clone: () => ({text: async () => JSON.stringify(payload)})})};
  if (xhr) sandbox.XMLHttpRequest = xhr;
  sandbox.window = sandbox;
  vm.createContext(sandbox);
  vm.runInContext(script, sandbox);
  return {snapshot: (kind = 'feed', noteId = '', keyword = '') =>
    JSON.parse(sandbox.__luluXhsReader.snapshot(kind, noteId, keyword)),
    receive: async (keyword) => {await sandbox.fetch(`https://edith.xiaohongshu.com/api/sns/web/v1/${api}`, keyword === undefined ? undefined : {method: 'POST', body: JSON.stringify({keyword})}); await new Promise(setImmediate);}, sandbox};
}

test('plain SSR feed returns actual title, author and unmodified token', () => {
  const b = browser({state: {feed: {feeds: [row()]}}});
  assert.deepEqual(b.snapshot().items, [{id, token: 'token+/=', source: 'pc_feed', title: '猫咪和纸箱', author: '真实作者'}]);
});
test('nested Vue refs and search source are handled', () => {
  const b = browser({path: '/search_result?keyword=%E7%8C%AB', state:
    {search: {_value: {feeds: {_rawValue: {_value: [row()]}}}}}});
  assert.equal(b.snapshot('search', '', '猫').items[0].source, 'pc_search');
});
test('same path with an old search query must not return stale results', () => {
  const b = browser({path: '/search_result?keyword=old', state: {search: {feeds: [row()]}}});
  assert.equal(b.snapshot('search', '', 'new').items.length, 0);
});
test('client API response works when initial state is empty', async () => {
  const b = browser({payload: {success: true, code: 0, data: {items: [row()]}}});
  await b.receive();
  assert.equal(b.snapshot().items[0].id, id);
});
test('snake_case search API returns title, author, and correct source', async () => {
  const b = browser({path: '/search_result?keyword=cat', api: 'search/notes', payload:
    {success: true, code: 0, data: {items: [{id, xsec_token: 'abc=',
      note_card: {display_title: '真实搜索', user: {nickname: '作者'}}}]}}});
  await b.receive();
  const item = b.snapshot('search', '', 'cat').items[0];
  assert.equal(item.title, '真实搜索'); assert.equal(item.source, 'pc_search');
  assert.equal(item.token, 'abc=');
});
test('duplicate notes and non-note recommendations are filtered', () => {
  const b = browser({state: {feed: {feeds: [row(''), row('complete'), {...row(), id: other, modelType: 'user'}]}}});
  assert.equal(b.snapshot().items.length, 1);
  assert.equal(b.snapshot().items[0].token, 'complete');
});
test('detail requires body belonging to the requested note', () => {
  const state = {note: {noteDetailMap: {[id]: {note: {noteId: id, title: '原文', desc: '真实正文'}}}}};
  const b = browser({path: '/explore/' + id, state});
  assert.equal(b.snapshot('note', id).note.body, '真实正文');
  assert.equal(b.snapshot('note', other).note, null);
});
test('a mismatched note ID in state cannot become a reading', () => {
  const b = browser({path: '/explore/' + id, state: {note: {noteDetailMap:
    {[id]: {note: {noteId: other, desc: '另一篇的文字'}}}}}});
  assert.equal(b.snapshot('note', id).note, null);
});
test('comments, title-only posts and body page text are not article bodies', () => {
  const b = browser({path: '/explore/' + id, text: '推荐和评论好多文字', state: {note: {noteDetailMap:
    {[id]: {note: {title: '仅标题'}, comments: [{content: '评论文字'}]}}}}});
  assert.equal(b.snapshot('note', id).note, null);
});
test('detail captured from a real feed response preserves author', async () => {
  const b = browser({path: '/explore/' + id, api: 'feed', payload: {code: 0, success: true,
    data: {items: [{id, note_card: {note_id: id, title: '笔记', desc: '来自响应的原文', user: {nickname: '作者'}}}]}}});
  await b.receive(); assert.equal(b.snapshot('note', id).note.author, '作者');
});
test('HTTP-200 business errors are detected, including IP risk', async () => {
  for (const code of [300012, 300017, -1]) {
    const b = browser({payload: {success: false, code}}); await b.receive();
    assert.equal(b.snapshot().error, code === -1 ? 'api:-1' : 'restricted');
  }
});
test('risk redirect is not hidden by an old initial state', () => {
  const b = browser({path: '/website-login/error?error_code=300012', state: {feed: {feeds: [row()]}}});
  assert.equal(b.snapshot().error, 'restricted');
});
test('blocked dialog takes precedence over existing note data', () => {
  const b = browser({elements: [{innerText: '当前笔记暂时无法浏览', getBoundingClientRect: () => ({height: 200})}],
    state: {feed: {feeds: [row()]}}});
  assert.equal(b.snapshot().error, 'restricted');
});
test('empty result differs from login failure', () => {
  assert.equal(browser({path: '/search_result?keyword=cat', text: '没有找到相关结果'})
    .snapshot('search', '', 'cat').empty, true);
  assert.equal(browser({text: '扫码登录'}).snapshot().error, 'login');
});
test('unrelated API payloads are never captured', async () => {
  const b = browser({api: 'user/me', payload: {data: {items: [row()]}}});
  await b.receive(); assert.equal(b.snapshot().items.length, 0);
});

const routeScript = fs.readFileSync('app/src/main/assets/xhs-browser-route.js', 'utf8');
function navigationBrowser(path = '/explore') {
  const b = browser({path, state: {feed: {feeds: [row()]}, search: {feeds: [row()]}}});
  const messages = [], handlers = {};
  b.sandbox.luluXhsNavigation = {postMessage: json => messages.push(JSON.parse(json))};
  b.sandbox.history = {pushState: (_, __, href) => { b.sandbox.location.href = new URL(href, b.sandbox.location.href).href; },
    replaceState: (_, __, href) => { b.sandbox.location.href = new URL(href, b.sandbox.location.href).href; }};
  // Real location exposes pathname; reflect href changes in this fixture.
  Object.defineProperty(b.sandbox.location, 'pathname', {get: () => new URL(b.sandbox.location.href).pathname});
  b.sandbox.addEventListener = (kind, handler) => { handlers[kind] = handler; };
  b.sandbox.document.addEventListener = (kind, handler) => { handlers[kind] = handler; };
  b.sandbox.Element = class Element {constructor(link) {this.link = link;} closest(selector) {return selector === 'a[href]' ? this.link : null;} };
  vm.runInContext(routeScript, b.sandbox);
  return {...b, messages, handlers, click(href) {
    const event = {target: new b.sandbox.Element({href, innerText: '标题'}),
      prevented: false, stopped: false, preventDefault() {this.prevented = true;},
      stopImmediatePropagation() {this.stopped = true;}};
    handlers.click(event); return event;
  }};
}
test('note click avoids the website modal and preserves the token', () => {
  const b = navigationBrowser();
  const event = b.click(`https://www.xiaohongshu.com/explore/${id}?xsec_token=token%2B%2F%3D&xsec_source=pc_feed`);
  assert.equal(event.prevented, true); assert.equal(event.stopped, true);
  assert.equal(b.messages[0].token, 'token+/='); assert.equal(b.messages[0].kind, 'note');
});
test('SPA search navigates to native reading without waiting for the white overlay', () => {
  const b = navigationBrowser();
  b.sandbox.history.pushState(null, '', '/search_result?keyword=%E7%8C%AB');
  assert.deepEqual(b.messages[0], {kind: 'search-start', keyword: '猫'});
});
test('login/profile links remain in the visible website', () => {
  const b = navigationBrowser(); const event = b.click('https://www.xiaohongshu.com/user/profile/123');
  assert.equal(event.prevented, false); assert.equal(b.messages.length, 0);
});
test('note href without a token can use its actual current-page card', () => {
  const b = navigationBrowser(); b.click(`https://www.xiaohongshu.com/explore/${id}`);
  assert.equal(b.messages[0].token, 'token+/='); assert.equal(b.messages[0].title, '猫咪和纸箱');
});

test('diagnostics preserve HTTP-200 business failure without note/token data', async () => {
  const b = browser({api: 'feed', path: '/explore/' + id, payload: {success: false, code: -1, secret: 'never-copy-me'}});
  await b.receive();
  const value = b.snapshot('note', id).diagnostics;
  assert.equal(value.api.note.http, 200); assert.equal(value.api.note.code, '-1');
  assert.equal(JSON.stringify(value).includes('never-copy-me'), false);
  assert.equal(JSON.stringify(value).includes('token'), false);
});
test('diagnostics measure visual/document viewport and lower hit element', () => {
  const b = browser();
  Object.assign(b.sandbox, {innerWidth: 980, innerHeight: 1300, visualViewport: {height: 500}});
  b.sandbox.document.documentElement = {clientHeight: 1300};
  b.sandbox.document.elementFromPoint = () => ({tagName: 'DIV', className: 'search-cover',
    getBoundingClientRect: () => ({top: 500, bottom: 1300, height: 800})});
  const viewport = b.snapshot().diagnostics.viewport;
  assert.equal(viewport.visualHeight, 500); assert.equal(viewport.lowerElement, 'DIV.search-cover');
  assert.equal(viewport.lowerRect.height, 800);
});
test('actual profile-note source must not be replaced by a cached feed source', () => {
  const b = navigationBrowser();
  b.click(`https://www.xiaohongshu.com/explore/${id}?xsec_token=actual&xsec_source=pc_user`);
  assert.equal(b.messages[0].source, 'pc_user'); assert.equal(b.messages[0].token, 'actual');
});

function searchEvent(target) {
  return {target, key: 'Enter', prevented: false,
    preventDefault() {this.prevented = true;}, stopImmediatePropagation() {}};
}
function setSearchInput(b, value) {
  const input = {value, matches: () => true, blur() {this.blurred = true;}};
  b.sandbox.document.querySelector = () => input;
  return input;
}
test('Enter opens native reading without cancelling the website search', () => {
  const b = navigationBrowser(); const input = setSearchInput(b, ' 记者 ');
  const event = searchEvent(input); b.handlers.keydown(event);
  assert.equal(event.prevented, false); assert.equal(input.blurred, true);
  assert.deepEqual(b.messages, [{kind: 'search-start', keyword: '记者'}]);
});
test('form submit routes search but unrelated and empty forms are untouched', () => {
  const b = navigationBrowser(); setSearchInput(b, '猫');
  b.handlers.submit(searchEvent({querySelector: () => null}));
  assert.equal(b.messages.length, 0);
  b.handlers.submit(searchEvent({querySelector: () => ({})}));
  assert.equal(b.messages[0].keyword, '猫');
  setSearchInput(b, '  '); const empty = searchEvent({querySelector: () => ({})});
  b.handlers.submit(empty); assert.equal(empty.prevented, false); assert.equal(b.messages.length, 1);
});
test('search icon click routes without a search anchor', () => {
  const b = navigationBrowser(); setSearchInput(b, '记者');
  const element = new b.sandbox.Element(null);
  element.closest = selector => selector !== 'a[href]' ? {} : null;
  b.handlers.click(searchEvent(element));
  assert.deepEqual(b.messages[0], {kind: 'search-start', keyword: '记者'});
});
test('successful overlay search API hands off even when homepage URL stays unchanged', async () => {
  const b = browser({api: 'search/notes', payload: {code: 0, success: true, data: {items: [row()]}}});
  const messages = [];
  b.sandbox.luluXhsNavigation = {postMessage: json => messages.push(JSON.parse(json))};
  b.sandbox.__luluXhsSearchKeyword = () => '记者';
  await b.receive(); assert.deepEqual(messages[0], {kind: 'search-ready', keyword: '记者'});
  assert.equal(b.snapshot('search', '', '记者').items[0].id, id);
});
test('actual note image variants are preserved, deduplicated and restricted to official hosts', () => {
  const b = browser({path: '/explore/' + id, state: {note: {noteDetailMap: {[id]: {note: {
    desc: '正文', imageList: [
      {urlDefault: 'http://sns-webpic-qc.xhscdn.com:80/a%2Fb?sig=x%2Fy', width: 900, height: 1200},
      {url_default: 'https://sns-webpic-qc.xhscdn.com/a%2Fb?sig=x%2Fy'},
      {infoList: [{imageScene: 'WB_DFT', url: 'https://sns-img.xhscdn.com/second'}]},
      {}, {urlDefault: '/undefined'}, {urlDefault: 'https://xhscdn.com.evil.test/a'},
      {urlDefault: 'https://user:pass@sns-img.xhscdn.com/a'}, {urlDefault: 'data:image/png;base64,abc'}
    ]}}}}}});
  const photos = b.snapshot('note', id).note.images;
  assert.equal(photos.length, 2);
  assert.equal(photos[0].url, 'https://sns-webpic-qc.xhscdn.com/a%2Fb?sig=x%2Fy');
  assert.equal(photos[1].url, 'https://sns-img.xhscdn.com/second');
});
test('image-only note returns actual pixels sources without inventing a text body', () => {
  const b = browser({path: '/explore/' + id, state: {note: {noteDetailMap: {[id]: {note: {
    title: '图片知识', image_list: [{url: 'https://sns-img.xhscdn.com/photo'}]}}}}}});
  const note = b.snapshot('note', id).note;
  assert.equal(note.body, ''); assert.equal(note.images.length, 1);
});

test('DOM-only note collects its own carousel images and preserves signed URLs', () => {
  const b = browser({path: '/explore/' + id});
  const image = {currentSrc: 'https://sns-img.xhscdn.com/real?sig=a%2Fb', naturalWidth: 1000, naturalHeight: 1200};
  const container = {querySelectorAll: selector => {
    assert.equal(selector.includes('img'), true); return [image];
  }};
  const scope = {closest: () => container, querySelector: selector =>
    selector.includes('desc') ? {innerText: '渲染后的正文'} : null};
  b.sandbox.document.querySelector = selector => selector === '.note-scroller' ? scope : null;
  const note = b.snapshot('note', id).note;
  assert.equal(note.body, '渲染后的正文'); assert.equal(note.images.length, 1);
  assert.equal(note.images[0].url, 'https://sns-img.xhscdn.com/real?sig=a%2Fb');
});
test('carousel fallback cannot collect unrelated document images without a note scope', () => {
  const b = browser({path: '/explore/' + id});
  assert.equal(b.snapshot('note', id).note, null);
});

test('blank or placeholder image URLs fall through to actual lazy image addresses', () => {
  const b = browser({path: '/explore/' + id, state: {note: {noteDetailMap: {[id]: {note: {
    desc: '图文笔记', imageList: [{urlDefault: '', urlPre: 'https://sns-img.xhscdn.com/preview'},
      {urlDefault: 'data:image/gif;base64,R0lG', url: 'https://sns-img.xhscdn.com/actual'}]}}}}}});
  assert.equal(b.snapshot('note', id).note.images.length, 2);
  const dom = browser({path: '/explore/' + id});
  const container = {querySelectorAll: () => [{currentSrc: 'data:image/gif;base64,R0lG',
    getAttribute: key => key === 'data-src' ? 'https://sns-img.xhscdn.com/lazy' : null}]};
  const scope = {closest: () => container, querySelector: () => null};
  dom.sandbox.document.querySelector = selector => selector === '.note-scroller' ? scope : null;
  assert.equal(dom.snapshot('note', id).note.images[0].url, 'https://sns-img.xhscdn.com/lazy');
});

test('search responses inside a homepage overlay are bound to the actual request keyword', async () => {
  const b = browser({api: 'search/notes', state: {feed: {feeds: [row('homepage')]}},
    payload: {code: 0, data: {items: [row('search-real')]}}});
  b.sandbox.__luluXhsReader.beginSearch('记者');
  await b.receive('记者');
  assert.equal(b.snapshot('search', '', '记者').items[0].token, 'search-real');
  assert.equal(b.snapshot('search', '', '猫').items.length, 0);
});
test('a late response from another query cannot satisfy the new search or show an old error', async () => {
  const b = browser({api: 'search/notes', payload: {code: 300012, success: false}});
  b.sandbox.__luluXhsReader.beginSearch('new'); await b.receive('old');
  assert.equal(b.snapshot('search', '', 'new').error, '');
  assert.equal(b.snapshot('search', '', 'new').items.length, 0);
});
test('an actual empty overlay search is not replaced by homepage recommendations', async () => {
  const b = browser({api: 'search/notes', state: {feed: {feeds: [row()]}}, payload: {code: 0, data: {items: []}}});
  b.sandbox.__luluXhsReader.beginSearch('记者'); await b.receive('记者');
  assert.equal(b.snapshot('search', '', '记者').empty, true);
  assert.equal(b.snapshot('search', '', '记者').items.length, 0);
});
test('alternate search paths return their real SSR results', () => {
  for (const path of ['/search', '/search/result']) {
    const b = browser({path: path + '?keyword=cat', state: {search: {feeds: [row()]}}});
    assert.equal(b.snapshot('search', '', 'cat').items[0].id, id);
  }
});
test('programmatic search uses the real input and button without a forged API request', () => {
  const b = browser(); const events = [];
  const input = {value: '', dispatchEvent: event => events.push(event.type), blur() {}};
  let clicks = 0;
  b.sandbox.Event = class {constructor(type) {this.type = type;}};
  b.sandbox.document.querySelector = selector => selector.includes('#search-input') ? input : {click() {clicks++;}};
  assert.equal(b.sandbox.__luluXhsReader.startSearch('记者'), 'submitted');
  assert.equal(input.value, '记者'); assert.equal(clicks, 1);
  assert.deepEqual(events, ['input', 'change']);
  assert.equal(b.sandbox.__luluXhsReader.pendingSearch('记者'), true);
});

function xhrFixture() {
  class XHR {
    constructor() {this.listeners = {}; this.responseType = 'json'; this.status = 200;}
    open() {} send(body) {this.sentBody = body;}
    addEventListener(kind, fn) {this.listeners[kind] = fn;}
    receive(payload) {this.response = payload; this.listeners.load();}
  }
  const b = browser({xhr: XHR});
  return {...b, request(keyword) {
    const request = new b.sandbox.XMLHttpRequest();
    request.open('POST', 'https://edith.xiaohongshu.com/api/sns/web/v1/search/notes');
    request.send(JSON.stringify({keyword}));
    return request;
  }};
}
test('XHR search forwards the original body and reads its actual keyword in an overlay', () => {
  const b = xhrFixture(); b.sandbox.__luluXhsReader.beginSearch('记者');
  const request = b.request('记者'); request.receive({code: 0, data: {items: [row('actual-xhr')]}});
  assert.equal(request.sentBody, JSON.stringify({keyword: '记者'}));
  assert.equal(b.snapshot('search', '', '记者').items[0].token, 'actual-xhr');
});
test('late XHR responses and network errors cannot invalidate a different keyword', () => {
  const b = xhrFixture(); b.sandbox.__luluXhsReader.beginSearch('old');
  const request = b.request('old'); b.sandbox.__luluXhsReader.beginSearch('new');
  request.receive({code: 300012, success: false}); request.listeners.error();
  assert.equal(b.snapshot('search', '', 'new').error, '');
  assert.equal(b.snapshot('search', '', 'new').items.length, 0);
});

test('changing SPA URL cannot relabel stale SSR feeds while a new search is pending', async () => {
  const b = browser({path: '/search_result?keyword=new', api: 'search/notes',
    state: {search: {feeds: [row('old-SSR')]}}, payload: {code: 0, data: {items: [row('new-response')]}}});
  b.sandbox.__luluXhsReader.beginSearch('new');
  assert.equal(b.snapshot('search', '', 'new').items.length, 0);
  await b.receive('new');
  assert.equal(b.snapshot('search', '', 'new').items[0].token, 'new-response');
});
