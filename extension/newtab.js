// ---- Apps (non sensibles -> chrome.storage.sync, synchronisées entre vos Chrome) ----
let apps;
const saveApps = () => { chrome.storage.sync.set({ apps }); renderApps(); };
const ask = (msg, def = '') => (prompt(msg, def) || '').trim();
async function renderApps() {
  apps ??= (await chrome.storage.sync.get('apps')).apps ||
    [{ g: 'Favoris', items: [{ n: 'Gmail', u: 'https://mail.google.com' }, { n: 'YouTube', u: 'https://www.youtube.com' }] }];
  const q = $('#q').value.toLowerCase();
  $('#apps').replaceChildren(...apps.map((g, gi) => h('section', { className: 'group' },
    h('header', {}, h('h2', {}, g.g),
      h('button', { className: 'ghost sm', title: 'Renommer', onclick: () => { const n = ask('Nom du groupe', g.g); if (n) { g.g = n; saveApps(); } } }, '✎'),
      h('button', { className: 'ghost sm', title: 'Monter', onclick: () => { if (gi) { apps.splice(gi - 1, 0, apps.splice(gi, 1)[0]); saveApps(); } } }, '↑'),
      h('button', { className: 'ghost sm', title: 'Supprimer', onclick: () => { if (confirm(`Supprimer « ${g.g} » ?`)) { apps.splice(gi, 1); saveApps(); } } }, '×')),
    h('div', { className: 'tiles' },
      g.items.filter(a => !q || a.n.toLowerCase().includes(q)).map(a => h('a', { className: 'tile', href: a.u, title: 'Clic droit : retirer',
        oncontextmenu: e => { e.preventDefault(); if (confirm(`Retirer ${a.n} ?`)) { g.items.splice(g.items.indexOf(a), 1); saveApps(); } } },
        h('img', { src: fav(a.u), alt: '' }), h('span', {}, a.n))),
      h('button', { className: 'tile add', title: 'Ajouter une app', onclick: () => addApp(g) }, '+')))),
    h('button', { className: 'ghost', onclick: () => { const n = ask('Nom du nouveau groupe'); if (n) { apps.push({ g: n, items: [] }); saveApps(); } } }, '+ Groupe'));
}
function addApp(g) {
  let u = ask('URL du site (ex: notion.so)');
  if (!u) return;
  if (!/^https?:\/\//i.test(u)) u = 'https://' + u;
  if (!hostOf(u)) return alert('URL invalide');
  g.items.push({ n: ask('Nom', hostOf(u)) || hostOf(u), u });
  saveApps();
}

// ---- Coffre ----
async function renderVault() {
  const root = $('#vault');
  gate(root, async () => {
    const q = $('#q').value.toLowerCase();
    const rows = (await send('list')).filter(i => !q || (i.host + ' ' + i.user + ' ' + (i.note || '')).toLowerCase().includes(q))
      .sort((a, b) => a.host.localeCompare(b.host));
    root.replaceChildren(
      h('div', { className: 'row' },
        h('button', { onclick: () => form({}) }, '+ Ajouter'),
        h('button', { className: 'ghost', onclick: exportVault }, 'Exporter (chiffré)'),
        h('label', { className: 'btn ghost' }, 'Importer', h('input', { type: 'file', accept: '.json', hidden: true, onchange: importVault })),
        h('span', { className: 'grow' }),
        h('button', { className: 'ghost', onclick: async () => { await send('lock'); renderVault(); } }, '🔒 Verrouiller')),
      h('div', { id: 'form' }),
      rows.length ? h('table', {}, rows.map(i => {
        const pw = h('code', {}, '••••••••');
        return h('tr', {},
          h('td', {}, h('img', { src: fav('https://' + i.host), alt: '' }), ' ', h('a', { href: 'https://' + i.host }, i.host)),
          h('td', {}, i.user), h('td', {}, pw), h('td', { className: 'muted' }, i.note || ''),
          h('td', { className: 'act' },
            h('button', { className: 'ghost sm', title: 'Afficher', onclick: () => pw.textContent = pw.textContent === i.pass ? '••••••••' : i.pass }, '👁'),
            h('button', { className: 'ghost sm', onclick: () => copy(i.pass) }, 'Copier'),
            h('button', { className: 'ghost sm', title: 'Modifier', onclick: () => form(i) }, '✎'),
            h('button', { className: 'ghost sm', title: 'Supprimer', onclick: async () => { if (confirm(`Supprimer ${i.host} ?`)) { await send('del', { id: i.id }); renderVault(); } } }, '×')));
      })) : h('p', { className: 'muted' }, 'Aucune entrée. Les identifiants saisis sur les sites vous seront proposés via l’icône de l’extension.'));
  });
}
function form(i) {
  const f = $('#form');
  const host = h('input', { placeholder: 'site (ex: github.com)', value: i.host || '', required: true });
  const user = h('input', { placeholder: 'identifiant', value: i.user || '' });
  const pass = h('input', { placeholder: 'mot de passe', value: i.pass || '', required: true });
  const note = h('input', { placeholder: 'note', value: i.note || '' });
  f.replaceChildren(h('form', { className: 'card row wrap', onsubmit: async e => {
    e.preventDefault();
    try { await send('save', { item: { id: i.id, host: host.value, user: user.value, pass: pass.value, note: note.value } }); renderVault(); }
    catch (x) { alert(x.message); }
  } }, host, user, pass, h('button', { type: 'button', className: 'ghost', title: 'Générer', onclick: () => pass.value = gen() }, '🎲'),
    note, h('button', {}, 'Enregistrer'), h('button', { type: 'button', className: 'ghost', onclick: () => f.replaceChildren() }, 'Annuler')));
  host.focus();
}
async function exportVault() {
  const { vault } = await chrome.storage.local.get('vault');
  h('a', { href: URL.createObjectURL(new Blob([JSON.stringify(vault)], { type: 'application/json' })),
    download: `coffre-${new Date().toISOString().slice(0, 10)}.json` }).click();
}
async function importVault(e) {
  try {
    const vault = JSON.parse(await e.target.files[0].text());
    if (confirm('Remplacer le coffre actuel par ce fichier ? (son mot de passe maître sera requis)')) { await send('import', { vault }); renderVault(); }
  } catch (x) { alert(x.message); }
}

// ---- Navigation / recherche ----
function route() {
  const v = location.hash === '#vault' ? 'vault' : 'apps';
  document.querySelectorAll('main').forEach(m => m.hidden = m.id !== v);
  document.querySelectorAll('nav a').forEach(a => a.classList.toggle('on', a.hash === '#' + v));
  v === 'vault' ? renderVault() : renderApps();
}
addEventListener('hashchange', route);
$('#q').oninput = route;
$('#sf').onsubmit = e => {
  e.preventDefault();
  const t = $('#q').value.trim();
  if (!t || location.hash === '#vault') return;
  if (/^[^\s]+\.[^\s]{2,}$/.test(t)) location.href = /^https?:\/\//i.test(t) ? t : 'https://' + t;
  else chrome.search.query({ text: t, disposition: 'CURRENT_TAB' });
};
route();
