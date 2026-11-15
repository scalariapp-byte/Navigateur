const hostOf = u => { try { return new URL(u).hostname.replace(/^www\./, ''); } catch { return ''; } };
const vis = e => e && e.offsetParent !== null && !e.disabled && !e.readOnly;
const pwFields = r => [...r.querySelectorAll('input[type=password]')].filter(vis);
const userFields = r => [...r.querySelectorAll('input[autocomplete=username],input[type=email],input[type=text],input[type=tel],input:not([type])')].filter(vis);
const userBefore = (r, p) => userFields(r).filter(i => p.compareDocumentPosition(i) & Node.DOCUMENT_POSITION_PRECEDING).pop();

function grab(scope) {
  const p = pwFields(scope).filter(i => i.value).pop(); // le dernier = nouveau mdp sur les formulaires de changement
  if (!p) { // étape "identifiant seul" (connexion en 2 temps)
    const u = userFields(scope).find(i => i.value);
    if (u) sessionStorage.setItem('nvU', u.value);
    return;
  }
  const u = userBefore(p.form || document, p);
  chrome.runtime.sendMessage({ t: 'captured', user: u?.value || sessionStorage.getItem('nvU') || '', pass: p.value }).catch(() => {});
}
document.addEventListener('submit', e => grab(e.target), true);
document.addEventListener('keydown', e => { if (e.key === 'Enter' && e.target.tagName === 'INPUT') grab(e.target.form || document); }, true);
document.addEventListener('click', e => { const b = e.target.closest?.('button,input[type=submit],[role=button]'); if (b) grab(b.form || document); }, true);

function set(el, v) {
  if (!el) return;
  el.focus();
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(el, v); // compatible React/Vue
  el.dispatchEvent(new Event('input', { bubbles: true }));
  el.dispatchEvent(new Event('change', { bubbles: true }));
}
chrome.runtime.onMessage.addListener(m => {
  if (m.t !== 'fill' || hostOf(location.href) !== m.host) return;
  const p = pwFields(document)[0];
  set(p ? userBefore(p.form || document, p) : userFields(document)[0], m.user);
  set(p, m.pass);
});
