const root = $('#app');
let tab;
async function main() { [tab] = await chrome.tabs.query({ active: true, currentWindow: true }); gate(root, show); }
async function show() {
  const p = await send('pending');
  const list = await send('forHost', { url: tab?.url || '' });
  const drop = async () => { await send('dropPending'); show(); };
  root.replaceChildren(
    p && h('div', { className: 'card pending' }, h('b', {}, `Enregistrer pour ${p.host} ?`), h('div', { className: 'muted' }, p.user || '(sans identifiant)'),
      h('div', { className: 'row' },
        h('button', { onclick: async () => { await send('save', { item: { host: p.host, user: p.user, pass: p.pass } }); drop(); } }, 'Enregistrer'),
        h('button', { className: 'ghost', onclick: drop }, 'Ignorer'))),
    h('h4', {}, hostOf(tab?.url) || '—'),
    list.length ? null : h('p', { className: 'muted' }, 'Aucun identifiant pour ce site.'),
    list.map(i => h('div', { className: 'card row' }, h('span', { className: 'grow' }, i.user || '(vide)'),
      h('button', { onclick: () => fill(i) }, 'Remplir'),
      h('button', { className: 'ghost', onclick: () => copy(i.pass) }, 'Copier'))),
    h('div', { className: 'row' },
      h('button', { className: 'ghost', onclick: () => copy(gen()) }, 'Générer & copier'),
      h('button', { className: 'ghost', onclick: () => chrome.tabs.create({ url: 'newtab.html#vault' }) }, 'Coffre'),
      h('button', { className: 'ghost', onclick: async () => { await send('lock'); main(); } }, '🔒')));
}
async function fill(i) {
  await chrome.tabs.sendMessage(tab.id, { t: 'fill', host: i.host, user: i.user, pass: i.pass }, { frameId: 0 }).catch(() => {});
  window.close();
}
main();
