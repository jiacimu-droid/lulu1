// Read only what the current XHS page actually received. No signed API calls,
// cookie access, login automation, or native command bridge.
(() => {
  if (window.__luluXhsReader) return;
  // XHS desktop builds on Vue 3 reactive Proxy/ref state. Reading plain
  // `value` alone misses __v_raw/__v_isRef, yielding an empty list despite
  // cards being visible in the browser. Only unwrap the requested subtree.
  const unwrap = value => {
    for (let i = 0; i < 8 && value && typeof value === 'object'; i++) {
      try {
        if (value.__v_isRef) { value = value.value; continue; }
        if (value.__v_raw) { value = value.__v_raw; continue; }
        if ('_rawValue' in value) { value = value._rawValue; continue; }
        if ('_value' in value) { value = value._value; continue; }
        if ('value' in value && !Array.isArray(value) &&
            !('noteCard' in value) && !('id' in value)) {
          value = value.value; continue;
        }
      } catch (_) {}
      break;
    }
    return value;
  };
  const clean = (v, cap) => typeof v === 'string' ? v.trim().slice(0, cap) : '';
  const validId = id => /^[a-z0-9]{12,40}$/i.test(id);
  const notePath = /^\/(?:explore|discovery\/item|search_result)\/([a-z0-9]{12,40})\/?$/i;
  // XHS's router sometimes applies encodeURIComponent before serializing the
  // query. URLSearchParams removes only the outer layer. Do not touch tokens.
  const decodeKeyword = raw => {
    let value = clean(raw, 768);
    for (let i = 0; i < 2 && /%[a-f0-9]{2}/i.test(value); i++) {
      try { const next = decodeURIComponent(value); if (next === value) break; value = next; }
      catch (_) { break; }
    }
    return value.slice(0, 64);
  };
  const matchesKeyword = (raw, expected) => raw === expected || decodeKeyword(raw) === expected;
  const list = (rows, source) => {
    rows = unwrap(rows);
    if (!Array.isArray(rows)) rows = unwrap(rows?.items ?? rows?.feeds);
    if (!Array.isArray(rows)) return [];
    const cards = new Map();
    for (const raw of rows.slice(0, 80)) {
      const row = unwrap(raw), card = unwrap(row?.noteCard ?? row?.note_card ?? row);
      if (!card || (row.modelType && row.modelType !== 'note')) continue;
      const id = clean(row.id ?? card.noteId ?? card.note_id ?? card.id, 40);
      if (!validId(id)) continue;
      const token = clean(row.xsecToken ?? row.xsec_token ?? card.xsecToken ?? card.xsec_token, 512);
      const cover = unwrap(card.cover ?? card.coverInfo ?? card.cover_info ?? card.imageList?.[0]);
      const coverUrl = clean(cover?.urlDefault ?? cover?.url_default ??
        cover?.urlPre ?? cover?.url ?? card.coverUrl ?? card.cover_url, 1024);
      const next = {id, token, source,
        title: clean(card.displayTitle ?? card.display_title ?? card.title, 200) || '未命名笔记',
        author: clean(card.authorName ?? card.user?.nickname ?? card.user?.nickName, 120)};
      if (coverUrl) next.coverUrl = coverUrl;
      if (!cards.has(id) || (!cards.get(id).token && token)) cards.set(id, next);
    }
    return [...cards.values()].slice(0, 35);
  };
  const images = raw => {
    const rows = unwrap(raw?.imageList ?? raw?.image_list) || [];
    if (!Array.isArray(rows)) return [];
    const result = new Map();
    for (const item of rows.slice(0, 30)) {
      const row = unwrap(item);
      const info = unwrap(row?.infoList ?? row?.info_list) || [];
      const candidates = [row?.urlDefault, row?.url_default, info.find?.(v => v.imageScene === 'WB_DFT')?.url, row?.urlPre, row?.url];
      for (const candidate of candidates) try {
        if (typeof candidate !== 'string' || !/^(?:https?:)?\/\//i.test(candidate.trim())) continue;
        const url = new URL(candidate, location.href);
        if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password ||
            !(/(^|\.)xhscdn\.com$|(^|\.)xiaohongshu\.com$/.test(url.hostname))) continue;
        if (url.protocol === 'http:' && url.port === '80') url.port = '';
        url.protocol = 'https:';
        result.set(url.href, {url: url.href, width: Number(row.width) || 0, height: Number(row.height) || 0});
        break;
      } catch (_) {}
    }
    return [...result.values()];
  };
  const note = (raw, id) => {
    raw = unwrap(raw);
    const actual = clean(raw?.noteId ?? raw?.note_id ?? raw?.id, 40);
    if (!raw || (actual && actual !== id)) return null;
    const body = clean(raw.desc ?? raw.description, 8000);
    const photos = images(raw);
    if (!body && !photos.length) return null; // Image-only posts need actual visual reading before AI receipt.
    return {id, title: clean(raw.title, 200), body, images: photos,
      author: clean(raw.user?.nickname ?? raw.user?.nickName, 120)};
  };
  // Only normalized list/body fields stay in this document's bounded memory.
  const received = {feed: [], search: [], notes: new Map(), failures: {}, api: {},
    searchKeyword: '', searchPending: '', searchRequested: '', searchEmpty: false, searchBaseline: '',
    searchDomBaseline: null};
  const searchStateRows = () => list(unwrap(unwrap(window.__INITIAL_STATE__)?.search)?.feeds, 'pc_search');
  const fingerprint = rows => rows.map(row => row.id + ':' + row.token).join('|');
  const pageKeyword = () => decodeKeyword(new URL(location.href).searchParams.get('keyword')) ||
    clean(window.__luluXhsSearchKeyword?.(), 64);
  const requestKeyword = (url, body) => {
    try {
      const value = typeof body === 'string' ? JSON.parse(body) : body;
      const raw = clean(value?.keyword, 768) || new URL(url, location.href).searchParams.get('keyword') || pageKeyword();
      return received.searchPending && matchesKeyword(raw, received.searchPending)
        ? received.searchPending : decodeKeyword(raw);
    } catch (_) { return pageKeyword(); }
  };
  const beginSearch = (keyword, freshDocument = false) => {
    const next = clean(keyword, 64);
    // SPA search can fire Enter, form-submit, and pushState for ONE search.
    // The old condition cleared a *successful* matching response whenever
    // another event announced the same keyword. That made native readers
    // time out while the website had already loaded real search cards.
    if (next !== received.searchPending) {
      received.search = []; received.searchKeyword = ''; received.searchEmpty = false;
      received.searchRequested = '';
      delete received.api.search;
    }
    if (received.searchPending !== next) {
      // URL changes before Vue fetch finishes can leave the previous search
      // array visible in __INITIAL_STATE__. Never label that stale array as
      // results for the new keyword. A changed array, a confirmed response,
      // or a full SSR navigation can satisfy the new request.
      // On a newly loaded search-result document, SSR data already belongs
      // to this URL. A SPA transition on the same document is different:
      // its existing Vue list may still belong to the previous keyword.
      // Baseline the latter only, or a fully hydrated fresh search document
      // will incorrectly appear stale until the native timeout.
      received.searchBaseline = freshDocument ? '' : fingerprint(searchStateRows());
      received.searchDomBaseline = freshDocument ? null : new Set(domCards('feed').map(card => card.id));
    }
    received.searchPending = next;
    received.failures.search = '';
  };
  // XHS uses different search controls in narrow/mobile-width layouts,
  // including textarea and contenteditable wrappers. Only accept controls
  // explicitly associated with search, never arbitrary login/text fields.
  const searchInputSelectors = '#search-input,input.search-input,input[type="search"],input[name="keyword"],' +
    'input[placeholder*="搜索"],input[placeholder*="搜"],input[placeholder*="关键词"],' +
    'input[aria-label*="搜索"],input[aria-label*="搜"],input[enterkeyhint="search"],' +
    '[role="searchbox"],[role="search"] input,[class*="search"] input,' +
    '[class*="search"] textarea,[class*="search"] [contenteditable="true"]';
  const isVisibleSearchControl = el => {
    const r = el?.getBoundingClientRect?.();
    return !!el && (!r || (r.width > 2 && r.height > 2));
  };
  const findSearchInput = () => {
    const candidates = [...(document.querySelectorAll?.(searchInputSelectors) || [])];
    if (!candidates.length) {
      const only = document.querySelector?.(searchInputSelectors);
      if (only) candidates.push(only);
    }
    return candidates.find(isVisibleSearchControl) || null;
  };
  const openSearchBox = () => {
    // Search entry differs between desktop and narrow XHS layouts; accept
    // only recognizable search controls, never arbitrary site or note buttons.
    const selectors = '.search-icon,.search-button,.search-btn,[class*="search-input-wrapper"],[class*="search-trigger"],[aria-label*="搜索"],[title*="搜索"],[aria-label="search"]';
    const nodes = [...(document.querySelectorAll?.(selectors) || [])];
    if (!nodes.length) {
      const only = document.querySelector?.(selectors);
      if (only) nodes.push(only);
    }
    const button = nodes.find(node => isVisibleSearchControl(node) && typeof node.click === 'function');
    if (!button) return 'opener-missing';
    button.click();
    return 'opened';
  };
  const startSearch = keyword => {
    const input = findSearchInput();
    if (!input) return 'input-missing';
    beginSearch(keyword);
    const editable = input.isContentEditable === true ||
      input.getAttribute?.('contenteditable') === 'true';
    if (editable) input.textContent = keyword;
    else {
      const prototype = input.tagName === 'TEXTAREA'
        ? window.HTMLTextAreaElement?.prototype : window.HTMLInputElement?.prototype;
      const setter = Object.getOwnPropertyDescriptor(prototype || {}, 'value')?.set;
      if (setter) setter.call(input, keyword); else input.value = keyword;
    }
    input.dispatchEvent(new Event('input', {bubbles: true}));
    input.dispatchEvent(new Event('change', {bubbles: true}));
    const button = document.querySelector('.search-icon,.search-button,.search-btn,[aria-label*="搜索"],[aria-label="search"]') || input.form?.querySelector('button[type="submit"]');
    // The desktop site's magnifier can merely focus/open its input. Its Vue
    // handler listens for Enter; clicking that icon and reporting "submitted"
    // left character searches on the homepage. Use ONE submission mechanism.
    if (typeof KeyboardEvent === 'function') {
      input.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true}));
      input.dispatchEvent(new KeyboardEvent('keyup', {key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true}));
    }
    else if (input.form?.requestSubmit) input.form.requestSubmit();
    else if (button) button.click();
    else return 'submit-unavailable';
    input.blur?.();
    return 'submitted';
  };
  const domCards = kind => {
    const result = new Map();
    const anchors = document.querySelectorAll(
      'section.note-item a[href],.note-item a[href],a[href*="/explore/"],a[href*="/discovery/item/"],a[href*="/search_result/"]');
    for (const a of [...anchors].slice(0, 180)) try {
      const u = new URL(a.href || a.getAttribute('href'), location.href);
      const match = u.pathname.match(notePath);
      if (!['www.xiaohongshu.com','xiaohongshu.com'].includes(u.hostname) ||
          !match || u.protocol !== 'https:') continue;
      const source = u.searchParams.get('xsec_source') || (kind === 'search' ? 'pc_search' : 'pc_feed');
      // Hidden recommendation grids can coexist with live search results.
      if (kind === 'search' && source !== 'pc_search') continue;
      const tile = a.closest?.('section.note-item,.note-item,[class*="note-item"],[class*="feed-item"]') || a;
      const box = tile.getBoundingClientRect?.();
      if (!box || box.width < 3 || box.height < 3) continue;
      const next = {id: match[1], token: u.searchParams.get('xsec_token') || '', source,
        title: clean(tile.querySelector?.('.title,[class*="title"]')?.innerText ||
          a.getAttribute?.('aria-label') || a.innerText, 200) || '未命名笔记',
        author: clean(tile.querySelector?.('.author .name,.username,[class*="author"] .name')?.innerText, 120),
        coverUrl: clean(tile.querySelector?.('img')?.currentSrc || tile.querySelector?.('img')?.src, 1024)};
      if (!result.has(next.id) || (!result.get(next.id).token && next.token)) result.set(next.id, next);
    } catch (_) {}
    return [...result.values()].slice(0, 35);
  };
  const category = raw => {
    try {
      const u = new URL(raw, location.href);
      if (u.protocol !== 'https:' || !['www.xiaohongshu.com', 'edith.xiaohongshu.com'].includes(u.hostname)) return '';
      if (/\/api\/sns\/web\/v\d+\/search\/notes$/.test(u.pathname)) return 'search';
      if (/\/api\/sns\/web\/v\d+\/homefeed$/.test(u.pathname)) return 'feed';
      if (/\/api\/sns\/web\/v\d+\/feed$/.test(u.pathname)) return 'note';
    } catch (_) {}
    return '';
  };
  const capture = (kind, payload, status, keyword = '') => {
    if (kind === 'search' && received.searchPending && keyword !== received.searchPending) return;
    if (!kind) return;
    received.api[kind] = {http: status, code: payload?.code == null ? null : String(payload.code).slice(0, 16),
      success: payload?.success !== false};
    if (status >= 400 || payload?.success === false || (payload?.code != null && Number(payload.code) !== 0)) {
      const code = String(payload?.code ?? status).slice(0, 16);
      received.failures[kind] = /300012|300017|461|471/.test(code) ? 'restricted' : `api:${code}`;
      return;
    }
    received.failures[kind] = '';
    const data = payload?.data ?? payload;
    if (kind === 'note') {
      for (const row of (data?.items ?? []).slice(0, 8)) {
        const raw = row?.note_card ?? row?.noteCard ?? row?.note;
        const id = clean(row?.id ?? raw?.note_id ?? raw?.noteId, 40);
        const value = validId(id) && note(raw, id);
        if (value) received.notes.set(id, value);
      }
      while (received.notes.size > 8) received.notes.delete(received.notes.keys().next().value);
    } else {
      received[kind] = list(data?.items ?? data?.feeds ?? data, kind === 'search' ? 'pc_search' : 'pc_feed');
      if (kind === 'search') {
        received.searchKeyword = keyword;
        received.searchEmpty = Array.isArray(data?.items ?? data?.feeds ?? data) && (data?.items ?? data?.feeds ?? data).length === 0;
        if (keyword) window.luluXhsNavigation?.postMessage(JSON.stringify({kind: 'search-ready', keyword}));
      }
    }
  };
  const invalidResponse = (kind, status, keyword) => {
    if (kind === 'search' && received.searchPending && keyword !== received.searchPending) return;
    received.api[kind] = {http: status, code: null, success: false};
    received.failures[kind] = 'invalid';
  };
  const nativeFetch = window.fetch;
  if (nativeFetch) window.fetch = function (...args) {
    const url = typeof args[0] === 'string' ? args[0] : args[0]?.url;
    const kind = category(url);
    const keyword = kind === 'search' ? (args[1]?.body !== undefined || typeof args[0] === 'string'
      ? Promise.resolve(requestKeyword(url, args[1]?.body))
      : args[0].clone().text().then(body => requestKeyword(url, body))) : Promise.resolve('');
    if (kind === 'search') keyword.then(value => {
      if (value && value === received.searchPending) received.searchRequested = value;
    }).catch(() => {});
    return nativeFetch.apply(this, args).then(response => {
      if (kind) response.clone().text().then(async text => {
        if (text.length <= 1500000) try { capture(kind, JSON.parse(text), response.status, await keyword); } catch (_) { invalidResponse(kind, response.status, await keyword); }
      }).catch(() => {});
      return response;
    }, async error => { if (kind && (kind !== 'search' || await keyword === received.searchPending || !received.searchPending)) received.failures[kind] = 'network'; throw error; });
  };
  const xhr = window.XMLHttpRequest?.prototype;
  if (xhr) {
    const open = xhr.open, send = xhr.send;
    xhr.send = function (body) {
      if (this.__luluSearchUrl) {
        this.__luluSearchKeyword = requestKeyword(this.__luluSearchUrl, body);
        if (this.__luluSearchKeyword && this.__luluSearchKeyword === received.searchPending)
          received.searchRequested = this.__luluSearchKeyword;
      }
      return send.call(this, body);
    };
    xhr.open = function (method, url, ...rest) {
      const kind = category(url);
      this.__luluSearchUrl = kind === 'search' ? url : '';
      this.__luluSearchKeyword = kind === 'search' ? pageKeyword() : '';
      this.addEventListener('error', () => { if (kind && (kind !== 'search' || !received.searchPending || this.__luluSearchKeyword === received.searchPending)) received.failures[kind] = 'network'; }, {once: true});
      this.addEventListener('load', () => {
        if (!kind) return;
        try {
          if (this.responseType === 'json') capture(kind, this.response, this.status, this.__luluSearchKeyword);
          else if (this.responseText.length <= 1500000) capture(kind, JSON.parse(this.responseText), this.status, this.__luluSearchKeyword);
        } catch (_) { invalidResponse(kind, this.status, this.__luluSearchKeyword); }
      }, {once: true});
      return open.call(this, method, url, ...rest);
    };
  }
  const diagnostics = () => {
    const width = window.innerWidth || 0, height = window.innerHeight || 0;
    const hit = document.elementFromPoint?.(width / 2, height * .75);
    const rect = hit?.getBoundingClientRect();
    const path = new URL(location.href).pathname;
    const searchState = unwrap(unwrap(window.__INITIAL_STATE__)?.search);
    const hydrated = list(searchState?.feeds, 'pc_search');
    const visibleCards = document.querySelectorAll?.('section.note-item,.note-item')?.length || 0;
    const bodyText = (document.body?.innerText || '').slice(0, 2000);
    // Record page-level facts, NEVER the note text, query string, cookies or
    // note URL. Without this we falsely called every stalled SPA a network timeout.
    return {api: received.api,
      page: {path: path.slice(0, 60), readyState: String(document.readyState || ''),
        stateCards: hydrated.length, visibleCards, hasSearchInput: !!findSearchInput(),
        loginPrompt: /扫码登录|请先登录|登录后查看/.test(bodyText),
        verificationPrompt: /滑块验证|安全验证|访问异常|IP at risk|300012|300017/.test(bodyText)},
      search: {pending: !!received.searchPending,
        requestObserved: !!received.searchRequested && received.searchRequested === received.searchPending,
        responseMatched: received.searchKeyword === received.searchPending && !!received.searchKeyword, count: received.search.length, empty: received.searchEmpty}, viewport: {width, height,
      visualHeight: window.visualViewport?.height ?? null,
      documentHeight: document.documentElement?.clientHeight ?? null,
      lowerElement: hit ? String(hit.tagName || '') + '.' + String(hit.className || '').slice(0, 100) : '',
      lowerRect: rect ? {top: Math.round(rect.top), bottom: Math.round(rect.bottom), height: Math.round(rect.height)} : null}};
  };
  window.__luluXhsReader = {diagnostics, beginSearch, startSearch, openSearchBox, pageKeyword, pendingSearch: keyword => received.searchPending === keyword, snapshot(kind, id, keyword) {
    const url = new URL(location.href), path = url.pathname;
    const searchResponse = kind === 'search' && !!keyword && received.searchKeyword === keyword;
    const searchPage = kind === 'search' &&
      ['/search_result','/search_result_ai','/search','/search/result'].includes(path) &&
      matchesKeyword(url.searchParams.get('keyword'), keyword);
    // A native/website search may remain marked pending forever when the site
    // changes its request transport. Both /search_result and the live
    // /search_result_ai route must be read from their own verified query
    // and fresh SSR/Vue/DOM data, independently of fetch/XHR interception.
    const onPage = kind === 'search' ? searchResponse || searchPage
      : kind === 'note' ? path.match(notePath)?.[1] === id
      : path === '/' || path === '/explore';
    const visibleText = [...document.querySelectorAll('.access-wrapper,.error-wrapper,.not-found-wrapper,.blocked-wrapper,[role="dialog"]')]
      .filter(el => el.getBoundingClientRect().height > 0).map(el => el.innerText || '').join(' ').slice(0, 6000);
    const bodyText = (document.body?.innerText || '').slice(0, 6000);
    const restricted = /website-login\/error/.test(path) || /IP at risk|300012|300017|安全验证|滑块验证|访问异常|当前笔记暂时无法浏览|笔记详情获取失败/.test(visibleText);
    if (restricted || received.failures[kind] === 'restricted') return JSON.stringify({error: 'restricted', diagnostics: diagnostics()});
    const state = unwrap(window.__INITIAL_STATE__) || {};
    let items = [], detail = null;
    if (onPage) {
      if (kind === 'note') {
        const map = unwrap(unwrap(state.note)?.noteDetailMap) || {};
        const entry = unwrap(map[id]);
        detail = note(unwrap(entry?.note ?? entry?.data?.note), id) ?? received.notes.get(id) ?? null;
        {
          // Read only the current note's own carousel; never avatars or recommendation images.
          const scope = document.querySelector('.note-scroller');
          const container = scope?.closest?.('#noteContainer,.note-container') ||
            document.querySelector('#noteContainer,.note-container,[class*="note-detail"]') || scope;
          const elements = [...(container?.querySelectorAll?.(
            '.swiper-slide img,.carousel-image img,.note-slider img,.note-image img,.media-container img,' +
            '.swiper-slide [style*="background"],.note-slider [style*="background"],.note-slider-img') || [])];
          const indexed = elements.map((el, position) => {
            const index = el.closest?.('[data-swiper-slide-index]')?.getAttribute?.('data-swiper-slide-index');
            return {el, index: index == null ? position : Number(index)};
          }).sort((a, b) => a.index - b.index);
          const photos = images({imageList: indexed.map(({el}) => ({
            urlDefault: el.currentSrc, urlPre: el.getAttribute?.('data-src'), url: el.src ||
              (el.style?.backgroundImage || window.getComputedStyle?.(el)?.backgroundImage || '')
                .match(/url\(["']?(.*?)["']?\)/)?.[1],
            width: el.naturalWidth || 0, height: el.naturalHeight || 0
          }))});
          const textScope = scope || container;
          const body = clean(textScope?.querySelector?.('#detail-desc,.desc,[class*="note-desc"]')?.innerText, 8000);
          if (detail) detail = {...detail,
            body: body.length > detail.body.length ? body : detail.body,
            // Hydrated state and the carousel often use two resized URLs for
            // the SAME photo. Keep the more complete ordered source, rather
            // than double-counting every image or sending duplicates to vision.
            images: photos.length > detail.images.length ? photos : detail.images};
          else {
            if (body || photos.length) detail = {id, body, images: photos,
              title: clean(textScope?.querySelector?.('#detail-title,.title')?.innerText, 200),
              author: clean(document.querySelector('.author-wrapper .username')?.innerText, 120)};
          }
          if (detail) {
            const pagination = container?.querySelector?.('.swiper-pagination,.fraction,.note-slider .pagination')?.innerText || '';
            detail.expectedImageCount = Math.max(detail.images.length,
              Math.min(30, Number(pagination.match(/\d+\s*\/\s*(\d+)/)?.[1]) || 0));
          }
        }
        if (detail) {
          // Comments are optional: capture only text actually rendered in
          // the opened note, not comments guessed from counts or user profile
          // avatars. Never claim that an unloaded comment list was read.
          const commentRoot = document.querySelector(
            '#noteContainer,.note-container,.note-scroller,[class*="note-detail"]') || document;
          const commentEls = [...commentRoot.querySelectorAll(
            '.comments-container .comment-item,.comment-list .comment-item,[class*="comments-container"] [class*="comment-item"]'
          )].slice(0, 12);
          detail.comments = commentEls.map(el => {
            const box = el.getBoundingClientRect?.();
            if (!box || box.width < 2 || box.height < 2) return '';
            const text = el.querySelector(
              '.comment-content,.comment-text,[class*="comment-content"],[class*="content-text"]'
            )?.innerText || '';
            return clean(text, 260);
          }).filter(Boolean).slice(0, 10);
        }
      } else {
        const section = unwrap(kind === 'search' ? state.search : state.feed ?? state.homeFeed);
        const stateItems = list(section?.feeds, kind === 'search' ? 'pc_search' : 'pc_feed');
        const staleSearchState = kind === 'search' && searchPage && !searchResponse &&
          received.searchPending === keyword && !!received.searchBaseline &&
          fingerprint(stateItems) === received.searchBaseline;
        // An explicit, verified empty API response is authoritative. Do not
        // relabel stale hydrated/home cards as search hits while its actual
        // response is empty or still pending.
        items = searchResponse ? received.search :
          staleSearchState ? [] : stateItems;
        if (!items.length && kind !== 'search') items = received[kind];
        // DOM fallback is only valid after a matching search document has
        // hydrated; a homepage overlay with an explicit empty response must
        // NOT smuggle unrelated recommendation links into search results.
        if (!items.length && !(kind === 'search' && searchResponse) &&
            (kind !== 'search' || searchPage)) {
          // After SPA pushState, old page cards may remain visible while
          // hydration is pending. Their visible DOM cannot prove they belong
          // to the newly requested keyword; wait for fresh state/API evidence.
          // Classes have changed repeatedly in the live XHS SPA. Prefer its
          // actual real note links to guessed Vue internals; never scrape a
          // generic page text or invent a note ID. Only trust signed links
          // belonging to the current page's allowed note-route patterns.
          // SSR state may remain unchanged forever in a client-rendered grid.
          // Fresh signed DOM cards can prove progress independently. Exclude
          // every pre-search card until matching API/state evidence arrives.
          items = domCards(kind).filter(card => kind !== 'search' ||
            !received.searchDomBaseline?.has(card.id));
        }
        items = [...new Map(items.map(item => [item.id, item])).values()].slice(0, 35);
      }
    }
    const login = /website-login/.test(path) || (!items.length && !detail && /扫码登录|请先登录|登录后查看/.test(bodyText));
    return JSON.stringify({items, note: detail, onPage, diagnostics: diagnostics(),
      error: login ? 'login' : items.length || detail ? '' : received.failures[kind] || '',
      empty: (searchResponse && received.searchEmpty) ||
        (kind === 'search' && onPage && /没有找到相关|暂无搜索结果|无相关结果/.test(bodyText))});
  }};
})();
