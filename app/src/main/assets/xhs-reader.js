// Read only what the current XHS page actually received. No signed API calls,
// cookie access, login automation, or native command bridge.
(() => {
  if (window.__luluXhsReader) return;
  const unwrap = value => {
    for (let i = 0; i < 5 && value && typeof value === 'object'; i++) {
      if ('_rawValue' in value) value = value._rawValue;
      else if ('_value' in value) value = value._value;
      else if ('value' in value && !Array.isArray(value)) value = value.value;
      else break;
    }
    return value;
  };
  const clean = (v, cap) => typeof v === 'string' ? v.trim().slice(0, cap) : '';
  const validId = id => /^[a-z0-9]{12,40}$/i.test(id);
  const list = (rows, source) => {
    rows = unwrap(rows);
    if (!Array.isArray(rows)) rows = unwrap(rows?.items ?? rows?.feeds);
    if (!Array.isArray(rows)) return [];
    const cards = new Map();
    for (const raw of rows.slice(0, 80)) {
      const row = unwrap(raw), card = unwrap(row?.noteCard ?? row?.note_card);
      if (!card || (row.modelType && row.modelType !== 'note')) continue;
      const id = clean(row.id ?? card.noteId ?? card.note_id, 40);
      if (!validId(id)) continue;
      const token = clean(row.xsecToken ?? row.xsec_token ?? card.xsecToken ?? card.xsec_token, 512);
      const next = {id, token, source,
        title: clean(card.displayTitle ?? card.display_title ?? card.title, 200) || '未命名笔记',
        author: clean(card.user?.nickname ?? card.user?.nickName, 120)};
      if (!cards.has(id) || (!cards.get(id).token && token)) cards.set(id, next);
    }
    return [...cards.values()].slice(0, 35);
  };
  const images = raw => {
    const rows = unwrap(raw?.imageList ?? raw?.image_list) || [];
    if (!Array.isArray(rows)) return [];
    const result = new Map();
    for (const row of rows.slice(0, 30)) {
      const info = row?.infoList ?? row?.info_list ?? [];
      const candidate = row?.urlDefault ?? row?.url_default ?? info.find?.(v => v.imageScene === 'WB_DFT')?.url ?? row?.urlPre ?? row?.url;
      try {
        if (typeof candidate !== 'string' || !candidate.trim()) continue;
        const url = new URL(candidate);
        if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password ||
            !(/(^|\.)xhscdn\.com$|(^|\.)xiaohongshu\.com$/.test(url.hostname))) continue;
        if (url.protocol === 'http:' && url.port === '80') url.port = '';
        url.protocol = 'https:';
        result.set(url.href, {url: url.href, width: Number(row.width) || 0, height: Number(row.height) || 0});
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
  const received = {feed: [], search: [], notes: new Map(), failures: {}, api: {}};
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
  const capture = (kind, payload, status) => {
    if (!kind) return;
    received.api[kind] = {http: status, code: payload?.code == null ? null : String(payload.code).slice(0, 16),
      success: payload?.success !== false};
    if (status >= 400 || payload?.success === false || (payload?.code != null && Number(payload.code) !== 0)) {
      const code = String(payload?.code ?? status).slice(0, 16);
      received.failures[kind] = /300012|300017|461|471/.test(code) ? 'restricted' : `api:${code}`;
      return;
    }
    received.failures[kind] = '';
    // Also hand off searches that happen inside an overlay without changing history.
    if (kind === 'search' && window.luluXhsNavigation && window.__luluXhsSearchKeyword) {
      const keyword = window.__luluXhsSearchKeyword();
      if (keyword) window.luluXhsNavigation.postMessage(JSON.stringify({kind: 'search', keyword}));
    }
    const data = payload?.data ?? payload;
    if (kind === 'note') {
      for (const row of (data?.items ?? []).slice(0, 8)) {
        const raw = row?.note_card ?? row?.noteCard ?? row?.note;
        const id = clean(row?.id ?? raw?.note_id ?? raw?.noteId, 40);
        const value = validId(id) && note(raw, id);
        if (value) received.notes.set(id, value);
      }
      while (received.notes.size > 8) received.notes.delete(received.notes.keys().next().value);
    } else received[kind] = list(data?.items ?? data?.feeds ?? data, kind === 'search' ? 'pc_search' : 'pc_feed');
  };
  const nativeFetch = window.fetch;
  if (nativeFetch) window.fetch = function (...args) {
    const kind = category(typeof args[0] === 'string' ? args[0] : args[0]?.url);
    return nativeFetch.apply(this, args).then(response => {
      if (kind) response.clone().text().then(text => {
        if (text.length <= 1500000) try { capture(kind, JSON.parse(text), response.status); } catch (_) {}
      }).catch(() => {});
      return response;
    }, error => { if (kind) received.failures[kind] = 'network'; throw error; });
  };
  const xhr = window.XMLHttpRequest?.prototype;
  if (xhr) {
    const open = xhr.open;
    xhr.open = function (method, url, ...rest) {
      const kind = category(url);
      this.addEventListener('error', () => { if (kind) received.failures[kind] = 'network'; }, {once: true});
      this.addEventListener('load', () => {
        if (!kind) return;
        try {
          if (this.responseType === 'json') capture(kind, this.response, this.status);
          else if (this.responseText.length <= 1500000) capture(kind, JSON.parse(this.responseText), this.status);
        } catch (_) {}
      }, {once: true});
      return open.call(this, method, url, ...rest);
    };
  }
  const diagnostics = () => {
    const width = window.innerWidth || 0, height = window.innerHeight || 0;
    const hit = document.elementFromPoint?.(width / 2, height * .75);
    const rect = hit?.getBoundingClientRect();
    return {api: received.api, viewport: {width, height,
      visualHeight: window.visualViewport?.height ?? null,
      documentHeight: document.documentElement?.clientHeight ?? null,
      lowerElement: hit ? String(hit.tagName || '') + '.' + String(hit.className || '').slice(0, 100) : '',
      lowerRect: rect ? {top: Math.round(rect.top), bottom: Math.round(rect.bottom), height: Math.round(rect.height)} : null}};
  };
  window.__luluXhsReader = {diagnostics, snapshot(kind, id, keyword) {
    const url = new URL(location.href), path = url.pathname;
    const onPage = kind === 'search' ? path === '/search_result' && url.searchParams.get('keyword') === keyword
      : kind === 'note' ? new RegExp('^/(explore|discovery/item)/' + id + '/?$').test(path)
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
        if (!detail || !detail.images?.length) {
          // Read only the current note's own carousel; never avatars or recommendation images.
          const scope = document.querySelector('.note-scroller');
          const container = scope?.closest?.('#noteContainer,.note-container') || scope;
          const elements = [...(container?.querySelectorAll?.('.swiper-slide img,.carousel-image img,.note-slider img,.note-image img,.media-container img') || [])];
          const indexed = elements.map((el, position) => {
            const index = el.closest?.('[data-swiper-slide-index]')?.getAttribute?.('data-swiper-slide-index');
            return {el, index: index == null ? position : Number(index)};
          }).sort((a, b) => a.index - b.index);
          const photos = images({imageList: indexed.map(({el}) => ({
            url: el.currentSrc || el.getAttribute?.('data-src') || el.src,
            width: el.naturalWidth || 0, height: el.naturalHeight || 0
          }))});
          if (detail) detail = {...detail, images: photos};
          else {
            const body = clean(scope?.querySelector('#detail-desc,.desc')?.innerText, 8000);
            if (body || photos.length) detail = {id, body, images: photos,
              title: clean(scope?.querySelector('#detail-title,.title')?.innerText, 200),
              author: clean(document.querySelector('.author-wrapper .username')?.innerText, 120)};
          }
        }
      } else {
        const section = unwrap(kind === 'search' ? state.search : state.feed ?? state.homeFeed);
        items = list(section?.feeds, kind === 'search' ? 'pc_search' : 'pc_feed');
        if (!items.length) items = received[kind];
        // Actual note links retain the site's token/source; avoid generic links in menus.
        if (!items.length) for (const card of document.querySelectorAll('section.note-item,.note-item')) {
          const a = card.querySelector('a[href*="/explore/"],a[href*="/discovery/item/"]');
          if (!a) continue;
          try {
            const u = new URL(a.href, location.href), match = u.pathname.match(/^\/(?:explore|discovery\/item)\/([a-z0-9]{12,40})$/i);
            if (!['www.xiaohongshu.com','xiaohongshu.com'].includes(u.hostname) || !match) continue;
            items.push({id: match[1], token: u.searchParams.get('xsec_token') || '',
              source: u.searchParams.get('xsec_source') || (kind === 'search' ? 'pc_search' : 'pc_feed'),
              title: clean(card.querySelector('.title')?.innerText, 200) || '未命名笔记',
              author: clean(card.querySelector('.author .name,.username')?.innerText, 120)});
          } catch (_) {}
        }
        items = [...new Map(items.map(item => [item.id, item])).values()].slice(0, 35);
      }
    }
    const login = /website-login/.test(path) || (!items.length && !detail && /扫码登录|请先登录|登录后查看/.test(bodyText));
    return JSON.stringify({items, note: detail, onPage, diagnostics: diagnostics(), error: login ? 'login' : received.failures[kind] || '',
      empty: onPage && /没有找到相关|暂无搜索结果|无相关结果/.test(bodyText)});
  }};
})();
