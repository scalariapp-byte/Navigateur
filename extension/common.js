const $ = s => document.querySelector(s);
const hostOf = u => { try { return new URL(u).hostname.replace(/^www\./, ''); } catch { return ''; } };
const send = (t, o = {}) => chrome.runtime.sendMessage({ t, ...o }).then(r => { if (r?.e) throw new Error(r.e); return r?.v; });
const copy = t => navigator.clipboard.writeText(t);
const fav = u => `/_favicon/?pageUrl=${encodeURIComponent(u)}&size=32`;
function h(tag, props = {}, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else if (k in e) e[k] = v; else e.setAttribute(k, v);
  }
  e.append(...kids.flat().filter(x => x != null && x !== false));
  return e;
}
function gen(n = 20) {
  const c = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%&*-_+=?';
  return [...crypto.getRandomValues(new Uint32Array(n))].map(x => c[x % c.length]).join('');
}
// Écran de création / déverrouillage, puis onReady()
async function gate(root, onReady) {
  const s = await send('status');
  if (s.unlocked) return onReady();
  const pw = h('input', { type: 'password', placeholder: s.exists ? 'Mot de passe maître' : 'Nouveau mot de passe maître (12+)' });
  const pw2 = s.exists ? null : h('input', { type: 'password', placeholder: 'Confirmer' });
  const err = h('p', { className: 'err' });
  root.replaceChildren(h('form', { className: 'gate', onsubmit: async e => {
    e.preventDefault();
    try {
      if (s.exists) await send('unlock', { pw: pw.value });
      else { if (pw.value !== pw2.value) throw new Error('Les mots de passe diffèrent'); await send('create', { pw: pw.value }); }
      onReady();
    } catch (x) { err.textContent = x.message; }
  } }, h('h3', {}, s.exists ? '🔒 Coffre verrouillé' : 'Créer le coffre'), pw, pw2,
    h('button', {}, s.exists ? 'Déverrouiller' : 'Créer'), err,
    s.exists ? null : h('p', { className: 'muted' }, 'Aucune récupération possible si oublié.')));
  pw.focus();
}
