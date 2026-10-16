// Visible login browser: route manual searches and note clicks to the shared native reader.
(() => {
  if (window.__luluXhsRouting) return;
  window.__luluXhsRouting = true;
  const send = value => window.luluXhsNavigation?.postMessage(JSON.stringify(value));
  const searchSelector = '#search-input,input.search-input,input[type="search"],input[name="keyword"],input[placeholder*="搜索"],input[placeholder*="搜"],[role="searchbox"],[class*="search"] input';
  const searchInput = () => document.querySelector(searchSelector);
  // Search is a live SPA operation. Never cover the visible WebView while
  // its data is pending. Hand off only real cards found in this document.
  let searchWatch = null;
  const watchResults = keyword => {
    if (!keyword || typeof setInterval !== 'function') return;
    if (searchWatch != null) clearInterval(searchWatch);
    let checks = 0;
    searchWatch = setInterval(() => {
      checks++;
      let result;
      try {
        result = JSON.parse(window.__luluXhsReader?.snapshot('search', '', keyword) || '{}');
      } catch (_) { result = {}; }
      if ((result.items?.length || 0) > 0 || result.empty) {
        clearInterval(searchWatch); searchWatch = null;
        send({kind: 'search-ready', keyword});
      } else if (checks >= 30 || result.error === 'restricted' || result.error === 'login') {
        clearInterval(searchWatch); searchWatch = null;
        send({kind: 'search-stalled', keyword, reason: result.error || 'empty-page'});
      }
    }, 500);
  };
  window.__luluXhsSearchKeyword = () => String(searchInput()?.value || '').trim().slice(0, 64);
  const submitSearch = event => {
    const keyword = window.__luluXhsSearchKeyword();
    if (!keyword || !window.luluXhsNavigation) return false;
    window.__luluXhsReader?.beginSearch(keyword);
    searchInput()?.blur?.();
    send({kind: 'search-start', keyword});
    watchResults(keyword);
    return true;
  };
  document.addEventListener('keydown', event => {
    if (event.key === 'Enter' && event.target?.matches?.(searchSelector)) submitSearch(event);
  }, true);
  document.addEventListener('submit', event => {
    if (event.target?.querySelector?.(searchSelector)) submitSearch(event);
  }, true);
  const searchResultPaths = ['/search_result', '/search_result_ai', '/search', '/search/result'];
  const route = (freshDocument = false) => {
    if (searchResultPaths.includes(location.pathname)) {
      const keyword = new URL(location.href).searchParams.get('keyword') || '';
      if (!keyword) return;
      // A history.pushState from a different search must clear the old SSR
      // baseline BEFORE asynchronous Vue hydration. Repeated events for the
      // same query must not erase a verified API response.
      if (!window.__luluXhsReader?.pendingSearch(keyword))
        window.__luluXhsReader?.beginSearch(keyword, freshDocument);
      send({kind: 'search-start', keyword});
      watchResults(keyword);
    }
  };
  for (const name of ['pushState', 'replaceState']) {
    const original = history[name];
    history[name] = function (...args) { const value = original.apply(this, args); route(); return value; };
  }
  addEventListener('popstate', route);
  document.addEventListener('click', event => {
    const element = event.target instanceof Element ? event.target : event.target?.parentElement;
    if (element?.closest?.('button[type="submit"],.search-icon,.search-button,.search-btn,[aria-label*="搜索"],[aria-label="search"]') && submitSearch(event)) return;
    const a = element?.closest?.('a[href]');
    if (!a) return;
    try {
      const u = new URL(a.href, location.href);
      if (u.protocol !== 'https:' || !['www.xiaohongshu.com', 'xiaohongshu.com'].includes(u.hostname)) return;
      const match = u.pathname.match(/^\/(?:explore|discovery\/item)\/([a-z0-9]{12,40})$/i);
      if (!match) return;
      const source = u.searchParams.get('xsec_source') || '';
      const kind = searchResultPaths.includes(location.pathname) ? 'search' : 'feed';
      const keyword = new URL(location.href).searchParams.get('keyword') || '';
      const cards = JSON.parse(window.__luluXhsReader?.snapshot(kind, '', keyword) || '{}').items || [];
      const card = cards.find(card => card.id === match[1]);
      const token = u.searchParams.get('xsec_token') || card?.token || '';
      // Never invoke the broken website detail modal for an actual note tap.
      event.preventDefault(); event.stopImmediatePropagation();
      send({kind: 'note', id: match[1], token, source: source || card?.source || (kind === 'search' ? 'pc_search' : 'pc_feed'),
        title: card?.title || a.innerText || '', author: card?.author || ''});
    } catch (_) {}
  }, true);
  // The initial document can arrive with real SSR search results already
  // hydrated. Later SPA navigation must continue rejecting stale cards.
  route(true);
})();
