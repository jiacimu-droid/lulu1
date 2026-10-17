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
test('reader diagnostic distinguishes hydrated search from an unready/blocked browser', () => {
  const b = browser({path: '/search_result?keyword=cat',
    state: {search: {feeds: [row('real-value')]}}, text: '登录后查看内容'});
  const d = b.snapshot('search', '', 'cat').diagnostics;
  assert.equal(d.page.path, '/search_result');
  assert.equal(d.page.stateCards, 1);
  assert.equal(d.page.loginPrompt, true);
  assert.equal(d.page.visibleCards, 0);
  assert.equal(JSON.stringify(d).includes('real-value'), false);
  assert.equal(JSON.stringify(d).includes('"keyword":"cat"'), false);
});
test('diagnostics report website verification separately from intercepted requests', () => {
  const b = browser({path: '/search_result?keyword=news', text: '请进行安全验证',
    state: {search: {feeds: []}}});
  const d = b.snapshot('search', '', 'news').diagnostics;
  assert.equal(d.page.verificationPrompt, true);
  assert.equal(d.api.search, undefined);
});
test('nested Vue refs and search source are handled', () => {
  const b = browser({path: '/search_result?keyword=%E7%8C%AB', state:
    {search: {_value: {feeds: {_rawValue: {_value: [row()]}}}}}});
  assert.equal(b.snapshot('search', '', '猫').items[0].source, 'pc_search');
});
test('when XHS hides its search input, only its real search opener is clicked', () => {
  const b = browser();
  let clicks = 0;
  // The page has a visible magnifying-glass control but no input until
  // that control is pressed. Opening it is not the same as submitting text.
  b.sandbox.document.querySelector = selector =>
    selector.startsWith('#search-input') ? null : {click: () => {clicks++;}};
  assert.equal(b.sandbox.__luluXhsReader.startSearch('猫咪'), 'input-missing');
  assert.equal(b.sandbox.__luluXhsReader.openSearchBox(), 'opened');
  assert.equal(clicks >= 1, true);
  b.sandbox.document.querySelector = () => null;
  assert.equal(b.sandbox.__luluXhsReader.openSearchBox(), 'opener-missing');
});
test('narrow XHS search widget accepts a visible contenteditable search field', () => {
  const b = browser();
  const events = [];
  const field = {tagName: 'DIV', isContentEditable: true, textContent: '',
    getBoundingClientRect: () => ({width: 300, height: 48}),
    dispatchEvent: event => { events.push(event.type); return true; },
    blur() {}};
  b.sandbox.document.querySelectorAll = selector =>
    selector.includes('contenteditable="true"') ? [field] : [];
  b.sandbox.document.querySelector = () => null;
  b.sandbox.Event = class {constructor(type) {this.type = type;}};
  b.sandbox.KeyboardEvent = class extends b.sandbox.Event {};
  assert.equal(b.sandbox.__luluXhsReader.startSearch('猫咪'), 'submitted');
  assert.equal(field.textContent, '猫咪');
  assert.deepEqual(events.slice(0, 2), ['input', 'change']);
  assert.equal(events.includes('keydown'), true);
  assert.equal(b.sandbox.__luluXhsReader.pendingSearch('猫咪'), true);
});

test('same path with an old search query must not return stale results', () => {
  const b = browser({path: '/search_result?keyword=old', state: {search: {feeds: [row()]}}});
  assert.equal(b.snapshot('search', '', 'new').items.length, 0);
});
test('old visible links are not sufficient to finish a newly requested SPA search', () => {
  const stale = {
    href: 'https://www.xiaohongshu.com/explore/' + id + '?xsec_token=old-token',
    getAttribute: () => '旧笔记',
    innerText: '上一轮的旧笔记',
    closest: () => null,
    getBoundingClientRect: () => ({width: 260, height: 120}),
  };
  const b = browser({path: '/search_result?keyword=%E7%8C%AB',
    elements: [stale], state: {search: {feeds: [row()]}}});
  b.sandbox.__luluXhsReader.beginSearch('猫');
  assert.deepEqual(b.snapshot('search', '', '猫').items, []);
  b.sandbox.__INITIAL_STATE__.search.feeds = [{...row('real-new-token'), id: other}];
  const found = b.snapshot('search', '', '猫').items;
  assert.equal(found.length, 1);
  assert.equal(found[0].id, other);
});

test('visible search links remain readable when site changes card CSS classes', () => {
  const cardLink = {
    href: 'https://www.xiaohongshu.com/explore/' + id +
      '?xsec_token=actual-token&xsec_source=pc_search',
    getAttribute: key => key === 'aria-label' ? '从真实搜索链接读取' : null,
    innerText: '真实笔记链接',
    closest: () => null,
    getBoundingClientRect: () => ({width: 280, height: 160})
  };
  const b = browser({path: '/search_result?keyword=cat', elements: [cardLink],
    state: {search: {feeds: []}}});
  const notes = b.snapshot('search', '', 'cat').items;
  assert.equal(notes.length, 1);
  assert.equal(notes[0].id, id);
  assert.equal(notes[0].token, 'actual-token');
  assert.equal(notes[0].title, '从真实搜索链接读取');
});
test('hidden stale search links must not be mistaken for visible results', () => {
  const hiddenLink = {
    href: 'https://www.xiaohongshu.com/explore/' + id +
      '?xsec_token=old-token&xsec_source=pc_feed',
    getAttribute: () => null,
    innerText: '隐藏的旧结果',
    closest: () => null,
    getBoundingClientRect: () => ({width: 0, height: 0})
  };
  const b = browser({path: '/search_result?keyword=cat', elements: [hiddenLink],
    state: {search: {feeds: []}}});
  assert.equal(b.snapshot('search', '', 'cat').items.length, 0);
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
test('SPA search keeps the real page alive until fresh results actually hydrate', () => {
  const b = navigationBrowser();
  const ticks = [];
  b.sandbox.setInterval = callback => { ticks.push(callback); return 1; };
  b.sandbox.clearInterval = () => {};
  b.sandbox.history.pushState(null, '', '/search_result?keyword=%E7%8C%AB');
  assert.deepEqual(b.messages, [{kind: 'search-start', keyword: '猫'}]);
  // pushState may happen while OLD search cards are still mounted. Do not
  // report search-ready or surface those cards as results for "猫".
  ticks[0]();
  assert.equal(b.messages.length, 1);
  assert.equal(b.snapshot('search', '', '猫').items.length, 0);
  // Only genuinely changed hydrated rows can complete this search.
  b.sandbox.__INITIAL_STATE__.search.feeds = [{...row('hydrated-cat-token'), id: other}];
  ticks[0]();
  assert.deepEqual(b.messages[1], {kind: 'search-ready', keyword: '猫'});
  assert.equal(b.snapshot('search', '', '猫').items[0].id, other);
});
test('second SPA search never treats first keyword SSR cards as new results', () => {
  const b = navigationBrowser();
  const ticks = [];
  b.sandbox.setInterval = callback => { ticks.push(callback); return ticks.length; };
  b.sandbox.clearInterval = () => {};
  b.sandbox.history.pushState(null, '', '/search_result?keyword=旧关键词');
  b.sandbox.history.pushState(null, '', '/search_result?keyword=新关键词');
  assert.equal(b.sandbox.__luluXhsReader.pendingSearch('新关键词'), true);
  assert.equal(b.snapshot('search', '', '新关键词').items.length, 0);
  ticks.at(-1)();
  assert.equal(b.messages.some(x => x.kind === 'search-ready' && x.keyword === '新关键词'), false);
  b.sandbox.__INITIAL_STATE__.search.feeds = [{...row('fresh-token'), id: other}];
  ticks.at(-1)();
  assert.equal(b.snapshot('search', '', '新关键词').items[0].id, other);
  assert.equal(b.messages.at(-1).kind, 'search-ready');
});

test('a stalled visible search reports a failure rather than pretending to have read notes', () => {
  const b = navigationBrowser();
  const ticks = [];
  b.sandbox.setInterval = callback => { ticks.push(callback); return 1; };
  b.sandbox.clearInterval = () => {};
  // Simulate an unrendered page without genuine search state or DOM cards.
  b.sandbox.__INITIAL_STATE__ = {feed: {feeds: [row()]}};
  b.sandbox.history.pushState(null, '', '/search_result?keyword=%E8%AE%B0%E8%80%85');
  for (let i = 0; i < 30; i++) ticks[0]();
  assert.equal(b.messages.some(message => message.kind === 'search-ready'), false);
  assert.deepEqual(b.messages.at(-1), {kind: 'search-stalled', keyword: '记者', reason: 'empty-page'});
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
test('duplicate SPA route notifications cannot erase a successfully captured search', async () => {
  const b = browser({api: 'search/notes', state: {feed: {feeds: [row('home')]}},
    payload: {code: 0, data: {items: [row('result')]}}});
  b.sandbox.__luluXhsReader.beginSearch('猫');
  await b.receive('猫');
  assert.equal(b.snapshot('search', '', '猫').items[0].token, 'result');
  // The site can fire a form submit followed by pushState for the same request.
  b.sandbox.__luluXhsReader.beginSearch('猫');
  assert.equal(b.snapshot('search', '', '猫').items[0].token, 'result');
  b.sandbox.__luluXhsReader.beginSearch('不同关键词');
  assert.equal(b.snapshot('search', '', '不同关键词').items.length, 0);
});

test('verified empty search response never leaks old SSR or homepage notes', async () => {
  const b = browser({path: '/search_result?keyword=记者', api: 'search/notes',
    state: {search: {feeds: [row('oldSSR')]}, feed: {feeds: [row('home')]}},
    payload: {code: 0, data: {items: []}}});
  b.sandbox.__luluXhsReader.beginSearch('记者');
  await b.receive('记者');
  const snapshot = b.snapshot('search', '', '记者');
  assert.equal(snapshot.empty, true);
  assert.equal(snapshot.items.length, 0);
});

test('an actual empty overlay search is not replaced by homepage recommendations', async () => {
  const b = browser({api: 'search/notes', state: {feed: {feeds: [row()]}}, payload: {code: 0, data: {items: []}}});
  b.sandbox.__luluXhsReader.beginSearch('记者'); await b.receive('记者');
  assert.equal(b.snapshot('search', '', '记者').empty, true);
  assert.equal(b.snapshot('search', '', '记者').items.length, 0);
});
test('alternate search paths return their real SSR results', () => {
  for (const path of ['/search', '/search/result', '/search_result_ai']) {
    const b = browser({path: path + '?keyword=cat', state: {search: {feeds: [row()]}}});
    assert.equal(b.snapshot('search', '', 'cat').items[0].id, id);
  }
});
test('AI search route only accepts the matching keyword and genuinely fresh cards', () => {
  const b = browser({path: '/search_result_ai?keyword=%E7%8C%AB', state:
    {search: {feeds: [row('old-token')]}, feed: {feeds: [row('home-token')]}}});
  // A different keyword must never claim the visible cards as its search results.
  assert.equal(b.snapshot('search', '', '狗').items.length, 0);
  b.sandbox.__luluXhsReader.beginSearch('猫');
  // A route transition with the old Vue state is not proof of a fresh search.
  assert.equal(b.snapshot('search', '', '猫').items.length, 0);
  b.sandbox.__INITIAL_STATE__.search.feeds = [{...row('new-token'), id: other}];
  const result = b.snapshot('search', '', '猫');
  assert.equal(result.onPage, true);
  assert.equal(result.items.length, 1);
  assert.equal(result.items[0].id, other);
  assert.equal(result.items[0].token, 'new-token');
  assert.equal(result.items[0].source, 'pc_search');
});
test('fresh AI search document trusts its own SSR data without trusting stale SPA cards', () => {
  const b = navigationBrowser('/search_result_ai?keyword=%E7%8C%AB');
  assert.deepEqual(b.messages[0], {kind: 'search-start', keyword: '猫'});
  // This is a newly loaded document, so its hydrated SSR search data is
  // first-party page data rather than a stale pre-navigation feed.
  const result = b.snapshot('search', '', '猫');
  assert.equal(result.onPage, true);
  assert.equal(result.items.length, 1);
  assert.equal(result.items[0].id, id);
  assert.equal(b.snapshot('search', '', '狗').items.length, 0);
});
test('AI result route sends search-ready and associates note clicks with search cards', () => {
  const b = navigationBrowser();
  const ticks = [];
  b.sandbox.setInterval = callback => { ticks.push(callback); return ticks.length; };
  b.sandbox.clearInterval = () => {};
  b.sandbox.history.pushState(null, '', '/search_result_ai?keyword=%E7%8C%AB');
  assert.deepEqual(b.messages[0], {kind: 'search-start', keyword: '猫'});
  ticks[0]();
  assert.equal(b.messages.some(message => message.kind === 'search-ready'), false);
  b.sandbox.__INITIAL_STATE__.search.feeds = [{...row('verified-search'), id: other}];
  ticks[0]();
  assert.deepEqual(b.messages.at(-1), {kind: 'search-ready', keyword: '猫'});
  b.click('https://www.xiaohongshu.com/explore/' + other);
  assert.equal(b.messages.at(-1).kind, 'note');
  assert.equal(b.messages.at(-1).source, 'pc_search');
  assert.equal(b.messages.at(-1).token, 'verified-search');
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

test('character search presses Enter rather than a magnifier that only focuses the input', () => {
  const b = browser(); const events = [];
  let searches = 0, clicks = 0, formSubmits = 0;
  b.sandbox.Event = class {constructor(type, options) {this.type = type; Object.assign(this, options);}};
  b.sandbox.KeyboardEvent = b.sandbox.Event;
  const input = {value: '', blur() {}, form: {requestSubmit() {formSubmits++;}},
    dispatchEvent(event) {
      events.push(event.type);
      if (event.type === 'keydown' && event.key === 'Enter' && input.value === '记者') searches++;
    }};
  b.sandbox.document.querySelector = selector => selector.includes('#search-input') ? input : {click() {clicks++;}};
  assert.equal(b.sandbox.__luluXhsReader.startSearch('记者'), 'submitted');
  assert.equal(searches, 1);
  assert.equal(clicks, 0); assert.equal(formSubmits, 0);
  assert.deepEqual(events, ['input', 'change', 'keydown', 'keyup']);
  // Submitting an input is not evidence that a network request was made.
  assert.equal(b.snapshot('search', '', '记者').diagnostics.search.requestObserved, false);
});

test('a pending real search request is distinguished from merely setting a keyword', () => {
  const b = xhrFixture();
  b.sandbox.__luluXhsReader.beginSearch('cat');
  assert.equal(b.snapshot('search', '', 'cat').diagnostics.search.requestObserved, false);
  b.request('cat');
  const state = b.snapshot('search', '', 'cat').diagnostics.search;
  assert.equal(state.requestObserved, true);
  assert.equal(state.responseMatched, false);
  b.sandbox.__luluXhsReader.beginSearch('dog');
  b.request('cat');
  assert.equal(b.snapshot('search', '', 'dog').diagnostics.search.requestObserved, false);
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

test('Chinese search results survive the website double-encoding its keyword', () => {
  for (const path of ['/search_result', '/search_result_ai']) {
    const b = browser({path: path + '?keyword=' + encodeURIComponent(encodeURIComponent('与电影夜奔')),
      state: {search: {feeds: [row('real-search-token')]}}});
    assert.equal(b.snapshot('search', '', '与电影夜奔').items[0]?.id, id);
    assert.equal(b.snapshot('search', '', '别的词').items.length, 0);
  }
});
test('double-encoded SPA route keeps the typed Chinese query and its real response together', async () => {
  const b = browser({path: '/search_result?keyword=' + encodeURIComponent(encodeURIComponent('猫咪')),
    api: 'search/notes', payload: {code: 0, data: {items: [row('matched')]}}});
  b.sandbox.__luluXhsReader.beginSearch('猫咪');
  // A transport that cannot inspect the request body falls back to the URL.
  await b.receive();
  assert.equal(b.snapshot('search', '', '猫咪').items[0]?.token, 'matched');
  const routed = navigationBrowser('/search_result?keyword=' + encodeURIComponent(encodeURIComponent('猫咪')));
  assert.equal(routed.messages[0]?.keyword, '猫咪');
});
test('search_result note links are real cards and route to exact note reading', () => {
  const href = `https://www.xiaohongshu.com/search_result/${id}?xsec_token=signed%2B%2F%3D&xsec_source=pc_search`;
  const anchor = {href, innerText: '搜索结果', getAttribute: () => null, closest: () => null,
    getBoundingClientRect: () => ({width: 280, height: 180})};
  const b = browser({path: '/search_result?keyword=cat', elements: [anchor]});
  assert.equal(b.snapshot('search', '', 'cat').items[0]?.token, 'signed+/=');
  const routed = navigationBrowser('/search_result?keyword=cat');
  assert.equal(routed.click(href).prevented, true);
  assert.equal(routed.messages.at(-1).kind, 'note');
  assert.equal(routed.messages.at(-1).id, id);
  const detail = browser({path: '/search_result/' + id,
    state: {note: {noteDetailMap: {[id]: {note: {noteId: id, desc: '搜索原帖的正文'}}}}}});
  assert.equal(detail.snapshot('note', id).note?.body, '搜索原帖的正文');
});
test('live DOM search results are usable when the old SSR search array never updates', () => {
  const b = browser({path: '/search_result?keyword=cat', state: {search: {feeds: [row('old-ssr')]}}});
  b.sandbox.__luluXhsReader.beginSearch('cat');
  const link = {href: `https://www.xiaohongshu.com/search_result/${other}?xsec_token=fresh&xsec_source=pc_search`,
    innerText: '实际新结果', getAttribute: () => null, closest: () => null,
    getBoundingClientRect: () => ({width: 280, height: 180})};
  b.sandbox.document.querySelectorAll = selector => selector.includes('a[href') ? [link] : [];
  const result = b.snapshot('search', '', 'cat');
  assert.equal(result.items.length, 1);
  assert.equal(result.items[0].id, other);
});
test('the note media sibling and CSS background images are read without avatars', () => {
  const b = browser({path: '/explore/' + id, state: {note: {noteDetailMap: {[id]: {note: {
    noteId: id, desc: '短正文'}}}}}});
  const media = {style: {backgroundImage: 'url("https://sns-webpic-qc.xhscdn.com/real-image?sig=a%2Fb")'},
    getAttribute: () => null};
  const root = {querySelectorAll: selector => selector.includes('background') ? [media] : [],
    querySelector: () => null};
  b.sandbox.document.querySelector = selector => selector.includes('#noteContainer') ? root : null;
  const detail = b.snapshot('note', id).note;
  assert.equal(detail.body, '短正文');
  assert.equal(detail.images[0]?.url, 'https://sns-webpic-qc.xhscdn.com/real-image?sig=a%2Fb');
});
test('hydrated Vue image refs and protocol-relative CDN addresses keep their real images', () => {
  const b = browser({path: '/explore/' + id, state: {note: {noteDetailMap: {[id]: {note: {
    noteId: id, desc: '真实图文', imageList: {_value: [{_value: {
      urlDefault: '//sns-webpic-qc.xhscdn.com/real', width: 1200, height: 1600}}]}}}}}}});
  const detail = b.snapshot('note', id).note;
  assert.equal(detail.images[0]?.url, 'https://sns-webpic-qc.xhscdn.com/real');
  assert.equal(detail.images[0]?.width, 1200);
});

test('state and carousel variants of the same photos are not sent to vision twice', () => {
  const b = browser({path: '/explore/' + id, state: {note: {noteDetailMap: {[id]: {note: {
    noteId: id, desc: '真实正文', imageList: [{urlDefault: 'https://sns-webpic-qc.xhscdn.com/photo!large'}]}}}}}});
  const image = {currentSrc: 'https://sns-webpic-qc.xhscdn.com/photo!small'};
  const root = {querySelectorAll: () => [image], querySelector: () => null};
  b.sandbox.document.querySelector = selector => selector.includes('#noteContainer') ? root : null;
  const note = b.snapshot('note', id).note;
  assert.equal(note.images.length, 1);
  assert.equal(note.images[0].url, 'https://sns-webpic-qc.xhscdn.com/photo!large');
});
test('search-related words inside a note do not prematurely complete an empty search', () => {
  const b = browser({path: '/explore/' + id, text: '作者说当时显示暂无搜索结果',
    state: {note: {noteDetailMap: {[id]: {note: {noteId: id, desc: '作者的真实内容'}}}}}});
  assert.equal(b.snapshot('note', id).empty, false);
});
test('without a native bridge, note taps retain ordinary browser navigation', () => {
  const b = navigationBrowser();
  delete b.sandbox.luluXhsNavigation;
  assert.equal(b.click(`https://www.xiaohongshu.com/explore/${id}?xsec_token=actual`).prevented, false);
});
