// Visible login browser: route manual searches and note clicks to the shared native reader.
(() => {
  if (window.__luluXhsRouting) return;
  window.__luluXhsRouting = true;
  const send = value => window.luluXhsNavigation?.postMessage(JSON.stringify(value));
  const route = () => {
    if (location.pathname === '/search_result')
      send({kind: 'search', keyword: new URL(location.href).searchParams.get('keyword') || ''});
  };
  for (const name of ['pushState', 'replaceState']) {
    const original = history[name];
    history[name] = function (...args) { const value = original.apply(this, args); route(); return value; };
  }
  addEventListener('popstate', route);
  document.addEventListener('click', event => {
    const element = event.target instanceof Element ? event.target : event.target?.parentElement;
    const a = element?.closest?.('a[href]');
    if (!a) return;
    try {
      const u = new URL(a.href, location.href);
      if (u.protocol !== 'https:' || !['www.xiaohongshu.com', 'xiaohongshu.com'].includes(u.hostname)) return;
      const match = u.pathname.match(/^\/(?:explore|discovery\/item)\/([a-z0-9]{12,40})$/i);
      if (!match) return;
      const source = u.searchParams.get('xsec_source') || 'pc_feed';
      const kind = location.pathname === '/search_result' ? 'search' : 'feed';
      const keyword = new URL(location.href).searchParams.get('keyword') || '';
      const cards = JSON.parse(window.__luluXhsReader?.snapshot(kind, '', keyword) || '{}').items || [];
      const card = cards.find(card => card.id === match[1]);
      const token = u.searchParams.get('xsec_token') || card?.token || '';
      // Never invoke the broken website detail modal for an actual note tap.
      event.preventDefault(); event.stopImmediatePropagation();
      send({kind: 'note', id: match[1], token, source: card?.source || source,
        title: card?.title || a.innerText || '', author: card?.author || ''});
    } catch (_) {}
  }, true);
  route();
})();
