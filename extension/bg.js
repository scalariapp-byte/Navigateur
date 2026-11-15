// Coffre : PBKDF2-SHA256 (600k) -> clé AES-256-GCM. Seul le chiffré est stocké sur disque.
// La clé déverrouillée vit dans chrome.storage.session (RAM, inaccessible aux content scripts).
const enc = new TextEncoder(), dec = new TextDecoder();
const b64 = u => btoa(String.fromCharCode(...new Uint8Array(u)));
const ub64 = s => Uint8Array.from(atob(s), c => c.charCodeAt(0));
const hostOf = u => { try { return new URL(u).hostname.replace(/^www\./, ''); } catch { return ''; } };
const LOCK_MIN = 15;
let key = null, items = null;

const getVault = async () => (await chrome.storage.local.get('vault')).vault;

async function derive(pw, salt) {
  const k = await crypto.subtle.importKey('raw', enc.encode(pw), 'PBKDF2', false, ['deriveKey']);
  return crypto.subtle.deriveKey({ name: 'PBKDF2', salt, iterations: 600000, hash: 'SHA-256' },
    k, { name: 'AES-GCM', length: 256 }, true, ['encrypt', 'decrypt']);
}
async function open() {
  const v = await getVault();
  items = JSON.parse(dec.decode(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: ub64(v.iv) }, key, ub64(v.ct))));
}
async function seal() {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, enc.encode(JSON.stringify(items)));
  await chrome.storage.local.set({ vault: { ...(await getVault()), iv: b64(iv), ct: b64(ct) } });
}
async function keep() {
  await chrome.storage.session.set({ k: b64(await crypto.subtle.exportKey('raw', key)) });
  chrome.alarms.create('lock', { delayInMinutes: LOCK_MIN });
}
async function restore() { // le service worker peut être tué : on recharge depuis la session
  if (key) return true;
  const { k } = await chrome.storage.session.get('k');
  if (!k) return false;
  key = await crypto.subtle.importKey('raw', ub64(k), 'AES-GCM', true, ['encrypt', 'decrypt']);
  await open();
  return true;
}
async function need() {
  if (!await restore()) throw 'Coffre verrouillé';
  chrome.alarms.create('lock', { delayInMinutes: LOCK_MIN });
}
async function lock() {
  key = items = null;
  await chrome.storage.session.remove('k');
  return true;
}
chrome.alarms.onAlarm.addListener(a => a.name === 'lock' && lock());

const H = {
  async status() { return { exists: !!await getVault(), unlocked: await restore() }; },
  async create({ pw }) {
    if (await getVault()) throw 'Coffre déjà existant';
    if (!pw || pw.length < 12) throw '12 caractères minimum';
    const salt = crypto.getRandomValues(new Uint8Array(16));
    key = await derive(pw, salt); items = [];
    await chrome.storage.local.set({ vault: { salt: b64(salt) } });
    await seal(); await keep(); return true;
  },
  async unlock({ pw }) {
    const v = await getVault();
    key = await derive(pw, ub64(v.salt));
    try { await open(); } catch { key = null; throw 'Mot de passe incorrect'; }
    await keep(); return true;
  },
  lock,
  async list() { await need(); return items; },
  async forHost({ url }) { await need(); const h = hostOf(url); return items.filter(i => i.host === h); },
  async save({ item }) {
    await need();
    item.host = hostOf(/:\/\//.test(item.host) ? item.host : 'https://' + item.host);
    if (!item.host) throw 'Site invalide';
    const i = items.findIndex(x => item.id ? x.id === item.id : x.host === item.host && x.user === item.user);
    item.id ||= i < 0 ? crypto.randomUUID() : items[i].id;
    item.t = Date.now();
    i < 0 ? items.push(item) : (items[i] = item);
    await seal(); return true;
  },
  async del({ id }) { await need(); items = items.filter(i => i.id !== id); await seal(); return true; },
  async captured({ user, pass }, s) {
    const host = hostOf(s.tab?.url);
    if (!host || !pass) return;
    if (await restore() && items.some(i => i.host === host && i.user === user && i.pass === pass)) return;
    await chrome.storage.session.set({ pending: { host, user, pass } });
    chrome.action.setBadgeText({ text: '+', tabId: s.tab.id });
  },
  async pending() { return (await chrome.storage.session.get('pending')).pending || null; },
  async dropPending() { await chrome.storage.session.remove('pending'); chrome.action.setBadgeText({ text: '' }); return true; },
  async import({ vault }) {
    if (!vault?.salt || !vault.iv || !vault.ct) throw 'Fichier invalide';
    await lock(); await chrome.storage.local.set({ vault }); return true;
  },
};

chrome.runtime.onMessage.addListener((m, s, reply) => {
  const fromExt = s.url?.startsWith(chrome.runtime.getURL(''));
  // Les pages web (via content script) ne peuvent que proposer un identifiant capturé.
  if (!H[m.t] || (!fromExt && m.t !== 'captured')) return;
  H[m.t](m, s).then(v => reply({ v }), e => reply({ e: String(e?.message || e) }));
  return true;
});
