const $ = (id) => document.getElementById(id);
const call = window.veil.call;
let S = null;
let editingUrl = false;

// ---- toolbar actions ----
$('newtab').onclick = () => call('tab:new');
$('back').onclick = () => call('nav:back');
$('forward').onclick = () => call('nav:forward');
$('reload').onclick = () => (cur()?.loading ? call('nav:stop') : call('nav:reload'));
$('urlform').onsubmit = (e) => { e.preventDefault(); call('nav:go', $('url').value); $('url').blur(); };
$('url').onfocus = () => { editingUrl = true; setTimeout(() => $('url').select(), 0); };
$('url').onblur = () => { editingUrl = false; render(); };
$('url').onkeydown = (e) => { if (e.key === 'Escape') $('url').blur(); };
window.veil.onFocusUrl(() => $('url').focus());

$('retry').onclick = () => call('nav:reload');
$('openvpn').onclick = () => call('panel', true);
$('shield').onclick = () => call('adblock:set', !S.adblock);
$('vpnpill').onclick = () => call('panel', !S.panelOpen);

// ---- settings panel ----
$('vpnOn').onchange = (e) => call('vpn:set', { enabled: e.target.checked });
$('adOn').onchange = (e) => call('adblock:set', e.target.checked);
document.querySelectorAll('#mode button').forEach((b) => (b.onclick = () => call('vpn:set', { mode: b.dataset.mode })));
$('pickConf').onclick = async () => { await call('vpn:pickConfig'); if (S.vpn.enabled) call('vpn:set', {}); };
$('pickBin').onclick = async () => { await call('vpn:pickBinary'); if (S.vpn.enabled) call('vpn:set', {}); };
$('saveProxy').onclick = () => call('vpn:set', { proxy: $('proxy').value });
$('checkIp').onclick = () => { $('ip').textContent = 'Checking…'; call('vpn:check'); };
document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && S?.panelOpen) call('panel', false); });

const cur = () => S?.tabs.find((t) => t.id === S.activeId);

function tabEl(t) {
  const el = document.createElement('div');
  el.className = 'tab' + (t.id === S.activeId ? ' active' : '');
  el.title = t.title;
  const icon = t.loading ? Object.assign(document.createElement('span'), { className: 'spin' })
    : t.favicon ? Object.assign(document.createElement('img'), { src: t.favicon, onerror() { this.replaceWith(Object.assign(document.createElement('span'), { className: 'ph' })); } })
    : Object.assign(document.createElement('span'), { className: 'ph' });
  const title = Object.assign(document.createElement('span'), { className: 't', textContent: t.title });
  const x = Object.assign(document.createElement('button'), { className: 'x', textContent: '×', title: 'Close tab (⌘W)' });
  x.onclick = (e) => { e.stopPropagation(); call('tab:close', t.id); };
  el.onmousedown = (e) => { if (e.button === 1) call('tab:close', t.id); else call('tab:activate', t.id); };
  el.append(icon, title, x);
  return el;
}

function render() {
  if (!S) return;
  const t = cur();
  $('tabs').replaceChildren(...S.tabs.map(tabEl));
  $('back').disabled = !t?.canBack;
  $('forward').disabled = !t?.canForward;
  $('reload').textContent = t?.loading ? '✕' : '↻';
  if (!editingUrl) $('url').value = t?.url?.startsWith('https://duckduckgo.com/?') ? '' : (t?.url || '');

  // shield pill
  const sh = $('shield');
  sh.className = 'pill ' + (S.adblock ? (S.adblockReady ? 'good' : 'warn') : '');
  $('shieldtxt').textContent = S.adblock ? (S.adblockReady ? `${t?.blocked || 0} blocked` : 'loading') : 'Ads allowed';

  // vpn pill + panel
  const v = S.vpn;
  const tone = { on: 'good', connecting: 'warn', error: 'bad', off: '' }[v.status];
  $('vpnpill').className = 'pill ' + tone;
  $('vpntxt').textContent = { on: `VPN · ${v.ip}`, connecting: 'Connecting…', error: 'VPN blocked', off: 'VPN off' }[v.status];
  $('panel').hidden = !S.panelOpen;
  $('errbox').hidden = S.panelOpen || !t?.error;
  $('errmsg').textContent = t?.error || '';
  $('vpnOn').checked = v.enabled;
  $('adOn').checked = S.adblock;
  const st = $('vpnStatus');
  st.className = 'status ' + tone;
  st.textContent = {
    on: `Connected — sites see ${v.ip}`,
    connecting: 'Connecting…',
    error: `Not connected — traffic blocked. ${v.detail}`,
    off: 'Off — sites see your real connection',
  }[v.status];
  document.querySelectorAll('#mode button').forEach((b) => b.classList.toggle('on', b.dataset.mode === v.mode));
  $('wgBox').hidden = v.mode !== 'wireguard';
  $('proxyBox').hidden = v.mode !== 'proxy';
  $('wgPath').textContent = v.wgConfig || 'Not chosen';
  $('wpStatus').textContent = v.wireproxyFound ? 'Found' : 'Not installed';
  if (document.activeElement !== $('proxy')) $('proxy').value = v.proxy;
  $('ip').textContent = v.ip || (v.status === 'error' ? 'Blocked (kill switch)' : '—');
}

window.veil.onState((s) => { S = s; render(); });
call('state');
