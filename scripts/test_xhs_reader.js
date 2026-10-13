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
function browser({path = '/explore', state = {}, text = '', elements = [], payload, api = 'homefeed'} = {}) {
  const sandbox = {URL, location: {href: 'https://www.xiaohongshu.com' + path},
    document: {body: {innerText: text}, querySelectorAll: () => elements, querySelector: () => null},
    __INITIAL_STATE__: state,
    fetch: async () => ({status: 200, clone: () => ({text: async () => JSON.stringify(payload)})})};
  sandbox.window = sandbox;
  vm.createContext(sandbox);
  vm.runInContext(script, sandbox);
  return {snapshot: (kind = 'feed', noteId = '', keyword = '') =>
    JSON.parse(sandbox.__luluXhsReader.snapshot(kind, noteId, keyword)),
    receive: async () => {await sandbox.fetch(`https://edith.xiaohongshu.com/api/sns/web/v1/${api}`); await new Promise(setImmediate);}, sandbox};
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
  b.sandbox.Element = class Element {constructor(link) {this.link = link;} closest() {return this.link;} };
  vm.runInContext(routeScript, b.sandbox);
  return {...b, messages, click(href) {
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
  assert.deepEqual(b.messages[0], {kind: 'search', keyword: '猫'});
});
test('login/profile links remain in the visible website', () => {
  const b = navigationBrowser(); const event = b.click('https://www.xiaohongshu.com/user/profile/123');
  assert.equal(event.prevented, false); assert.equal(b.messages.length, 0);
});
test('note href without a token can use its actual current-page card', () => {
  const b = navigationBrowser(); b.click(`https://www.xiaohongshu.com/explore/${id}`);
  assert.equal(b.messages[0].token, 'token+/='); assert.equal(b.messages[0].title, '猫咪和纸箱');
});
