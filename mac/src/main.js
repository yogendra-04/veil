// Veil — main process: window, tabs, ad blocker, VPN tunnel.
const { app, BrowserWindow, WebContentsView, session, ipcMain, Menu, dialog, shell } = require('electron');
const path = require('path');
const fs = require('fs');
const net = require('net');
const { spawn } = require('child_process');
const { ElectronBlocker } = require('@ghostery/adblocker-electron');

const CHROME_HEIGHT = 84;            // tab strip + toolbar, in px (must match ui.css)
const HOME = 'https://duckduckgo.com';
const SEARCH = 'https://duckduckgo.com/?q=';

let win;
let browse;                           // the session every tab uses
let blocker = null;
const tabs = new Map();               // id -> { view, blocked }
let activeId = null;
let nextId = 1;
let panelOpen = false;

// ---------- settings ----------
const settingsFile = () => path.join(app.getPath('userData'), 'settings.json');
const defaults = {
  adblock: true,
  vpn: { enabled: false, mode: 'wireguard', proxy: 'socks5://127.0.0.1:1080', wgConfig: '', wireproxyPath: '' },
};
let settings = structuredClone(defaults);
function loadSettings() {
  try { settings = { ...defaults, ...JSON.parse(fs.readFileSync(settingsFile(), 'utf8')) };
        settings.vpn = { ...defaults.vpn, ...settings.vpn }; } catch {}
}
function saveSettings() { fs.writeFileSync(settingsFile(), JSON.stringify(settings, null, 2)); }

// ---------- ad blocker ----------
async function initBlocker() {
  const cache = path.join(app.getPath('userData'), 'adblock-engine.bin');
  try { if (Date.now() - fs.statSync(cache).mtimeMs > 7 * 864e5) fs.unlinkSync(cache); } catch {} // weekly refresh
  blocker = await ElectronBlocker.fromPrebuiltAdsAndTracking(fetch, {
    path: cache,
    read: fs.promises.readFile,
    write: fs.promises.writeFile,
  });
  blocker.on('request-blocked', (req) => {
    for (const t of tabs.values()) {
      if (t.view.webContents.id === req.tabId) { t.blocked++; break; }
    }
    pushState();
  });
  applyAdblock();
}
function applyAdblock() {
  if (!blocker) return;
  const on = blocker.isBlockingEnabled(browse);
  if (settings.adblock && !on) blocker.enableBlockingInSession(browse);
  if (!settings.adblock && on) blocker.disableBlockingInSession(browse);
}

// ---------- VPN ----------
// Two modes:
//   proxy     — route all browser traffic through a SOCKS5/HTTP proxy you run (e.g. Outline, ssh -D)
//   wireguard — run `wireproxy` (userspace WireGuard, no root, no system VPN) with your wg .conf,
//               and point the browser at its local SOCKS5 port.
// Kill switch: while the VPN is enabled the browser NEVER falls back to a direct connection.
const vpn = { status: 'off', detail: '', ip: '', child: null };
const IP_URL = process.env.VEIL_IP_URL || 'https://api.ipify.org?format=json';
const BLACKHOLE = 'socks5://127.0.0.1:9';   // nothing listens here → requests fail instead of leaking

function findWireproxy() {
  const candidates = [
    settings.vpn.wireproxyPath,
    path.join(process.resourcesPath || '', 'bin', 'wireproxy'),
    path.join(__dirname, '..', 'bin', 'wireproxy'),
    '/opt/homebrew/bin/wireproxy',
    '/usr/local/bin/wireproxy',
  ].filter(Boolean);
  return candidates.find((p) => { try { fs.accessSync(p, fs.constants.X_OK); return true; } catch { return false; } });
}
function freePort() {
  return new Promise((res, rej) => {
    const s = net.createServer().listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => res(p)); });
    s.on('error', rej);
  });
}
function waitForPort(port, ms = 10000) {
  const until = Date.now() + ms;
  return new Promise((res, rej) => {
    const tryOnce = () => {
      const c = net.connect(port, '127.0.0.1');
      c.once('connect', () => { c.destroy(); res(); });
      c.once('error', () => { c.destroy(); Date.now() > until ? rej(new Error('tunnel did not start')) : setTimeout(tryOnce, 200); });
    };
    tryOnce();
  });
}
function stopWireproxy() { if (vpn.child) { vpn.child.kill(); vpn.child = null; } }

async function setVpnState(status, detail = '') { vpn.status = status; vpn.detail = detail; pushState(); }

async function applyVpn() {
  stopWireproxy();
  vpn.ip = '';
  if (!settings.vpn.enabled) {
    await browse.setProxy({ mode: 'direct' });
    await setVpnState('off');
    return checkIp();
  }
  // Kill switch first: block everything until the tunnel is confirmed.
  await browse.setProxy({ proxyRules: BLACKHOLE, proxyBypassRules: '<-loopback>' });
  await setVpnState('connecting');
  try {
    let rules;
    if (settings.vpn.mode === 'proxy') {
      rules = settings.vpn.proxy.trim();
      if (!/^(socks5|socks4|http|https):\/\/[^\s]+:\d+$/.test(rules)) throw new Error('Proxy must look like socks5://host:port');
    } else {
      const bin = findWireproxy();
      if (!bin) throw new Error('wireproxy not found — run `brew install wireproxy` or put it in mac/bin/');
      if (!settings.vpn.wgConfig || !fs.existsSync(settings.vpn.wgConfig)) throw new Error('Choose your WireGuard .conf file');
      const port = await freePort();
      const cfg = path.join(app.getPath('userData'), 'wireproxy.conf');
      fs.writeFileSync(cfg, `WGConfig = ${settings.vpn.wgConfig}\n\n[Socks5]\nBindAddress = 127.0.0.1:${port}\n`, { mode: 0o600 });
      const child = spawn(bin, ['-c', cfg], { stdio: ['ignore', 'pipe', 'pipe'] });
      vpn.child = child;
      let log = '';
      child.stdout.on('data', (d) => (log += d));
      child.stderr.on('data', (d) => (log += d));
      child.on('exit', (code) => {
        if (vpn.child === child) { vpn.child = null; setVpnState('error', `Tunnel stopped (code ${code}). ${log.slice(-200)}`); }
      });
      await waitForPort(port);
      rules = `socks5://127.0.0.1:${port}`;
    }
    // socks5 → Chromium resolves DNS through the proxy too, so no DNS leak.
    await browse.setProxy({ proxyRules: rules, proxyBypassRules: '<-loopback>' });
    await browse.closeAllConnections();
    const ip = await checkIp();
    if (!ip) throw new Error('Tunnel is up but no internet through it — check the server/config');
    await setVpnState('on');
  } catch (e) {
    await browse.setProxy({ proxyRules: BLACKHOLE, proxyBypassRules: '<-loopback>' }); // stay locked
    await setVpnState('error', e.message);
  }
}
async function checkIp() {
  try {
    const r = await browse.fetch(IP_URL, { signal: AbortSignal.timeout(8000) });
    const body = (await r.text()).trim();
    vpn.ip = body.startsWith('{') ? JSON.parse(body).ip : body;
  } catch { vpn.ip = ''; }
  pushState();
  return vpn.ip;
}

// ---------- tabs ----------
function toUrl(input) {
  const s = input.trim();
  if (!s) return HOME;
  if (/^[a-z]+:\/\//i.test(s) || s.startsWith('about:')) return s;
  if (/^localhost(:\d+)?(\/|$)/.test(s)) return 'http://' + s;
  if (/^[^\s]+\.[a-z]{2,}(:\d+)?(\/.*)?$/i.test(s)) return 'https://' + s;
  return SEARCH + encodeURIComponent(s);
}
function layout() {
  if (!win) return;
  const [w, h] = win.getContentSize();
  for (const [id, t] of tabs) {
    const show = id === activeId && !panelOpen && !t.error;
    t.view.setVisible(show);
    if (show) t.view.setBounds({ x: 0, y: CHROME_HEIGHT, width: w, height: Math.max(0, h - CHROME_HEIGHT) });
  }
}
function createTab(url = HOME, activate = true) {
  const id = nextId++;
  const view = new WebContentsView({ webPreferences: { session: browse, sandbox: true, contextIsolation: true } });
  const wc = view.webContents;
  wc.setWebRTCIPHandlingPolicy('disable_non_proxied_udp');   // stop WebRTC leaking your real IP
  wc.setWindowOpenHandler(({ url }) => { createTab(url); return { action: 'deny' }; });
  wc.on('did-start-navigation', (_e, _url, isInPlace, isMainFrame) => {
    if (isMainFrame && !isInPlace) { tabs.get(id) && (tabs.get(id).blocked = 0); }
  });
  for (const ev of ['did-navigate', 'did-navigate-in-page', 'page-title-updated', 'did-start-loading', 'did-stop-loading', 'page-favicon-updated']) {
    wc.on(ev, (_e, ...args) => {
      if (ev === 'page-favicon-updated') tabs.get(id) && (tabs.get(id).favicon = args[0]?.[0] || '');
      pushState();
    });
  }
  wc.on('did-fail-load', (_e, code, desc, failedUrl, isMainFrame) => {
    if (isMainFrame && code !== -3 && tabs.get(id)) { tabs.get(id).error = friendlyError(desc); layout(); pushState(); }
  });
  wc.on('did-start-loading', () => { if (tabs.get(id)?.error) { tabs.get(id).error = ''; layout(); } });
  win.contentView.addChildView(view);
  tabs.set(id, { view, blocked: 0, favicon: '', error: '' });
  wc.loadURL(toUrl(url));
  if (activate) activateTab(id); else layout();
  return id;
}
function activateTab(id) { if (!tabs.has(id)) return; activeId = id; layout(); pushState(); }
function closeTab(id) {
  const t = tabs.get(id); if (!t) return;
  const order = [...tabs.keys()]; const idx = order.indexOf(id);
  win.contentView.removeChildView(t.view);
  t.view.webContents.close();
  tabs.delete(id);
  if (tabs.size === 0) return createTab();
  if (activeId === id) activateTab(order[idx + 1] ?? order[idx - 1]);
  else pushState();
}
function friendlyError(code) {
  if (settings.vpn.enabled && vpn.status !== 'on') return 'The VPN isn’t connected, so Veil blocked this page to keep your real IP hidden.';
  if (/PROXY|TUNNEL|SOCKS/.test(code)) return 'Couldn’t reach the site through the VPN. The VPN server may be down.';
  if (/NAME_NOT_RESOLVED/.test(code)) return 'That address couldn’t be found.';
  if (/INTERNET_DISCONNECTED/.test(code)) return 'You’re offline.';
  return `This page couldn’t load (${code}).`;
}
const active = () => tabs.get(activeId)?.view.webContents;

// ---------- UI state ----------
let pushQueued = false;
function pushState() {          // coalesce bursts (e.g. 50 blocked requests) into one UI update
  if (pushQueued) return;
  pushQueued = true;
  setTimeout(() => { pushQueued = false; sendState(); }, 30);
}
function sendState() {
  if (!win || win.isDestroyed()) return;
  const list = [...tabs].map(([id, t]) => {
    const wc = t.view.webContents;
    return {
      id, title: wc.getTitle() || 'New Tab', url: wc.getURL(), favicon: t.favicon,
      loading: wc.isLoading(), blocked: t.blocked, error: t.error,
      canBack: wc.navigationHistory.canGoBack(), canForward: wc.navigationHistory.canGoForward(),
    };
  });
  win.webContents.send('state', {
    tabs: list, activeId, panelOpen,
    adblock: settings.adblock, adblockReady: !!blocker,
    vpn: { ...settings.vpn, status: vpn.status, detail: vpn.detail, ip: vpn.ip, wireproxyFound: !!findWireproxy() },
  });
}

// ---------- IPC from the toolbar ----------
ipcMain.handle('tab:new', (_e, url) => createTab(url));
ipcMain.handle('tab:close', (_e, id) => closeTab(id ?? activeId));
ipcMain.handle('tab:activate', (_e, id) => activateTab(id));
ipcMain.handle('nav:go', (_e, input) => active()?.loadURL(toUrl(input)));
ipcMain.handle('nav:back', () => active()?.navigationHistory.goBack());
ipcMain.handle('nav:forward', () => active()?.navigationHistory.goForward());
ipcMain.handle('nav:reload', () => active()?.reload());
ipcMain.handle('nav:stop', () => active()?.stop());
ipcMain.handle('panel', (_e, open) => { panelOpen = open; layout(); pushState(); });
ipcMain.handle('adblock:set', (_e, on) => { settings.adblock = on; saveSettings(); applyAdblock(); active()?.reload(); pushState(); });
ipcMain.handle('vpn:set', async (_e, patch) => { settings.vpn = { ...settings.vpn, ...patch }; saveSettings(); await applyVpn(); });
ipcMain.handle('vpn:check', () => checkIp());
ipcMain.handle('vpn:pickConfig', async () => {
  const r = await dialog.showOpenDialog(win, { title: 'Choose WireGuard config', filters: [{ name: 'WireGuard', extensions: ['conf'] }], properties: ['openFile'] });
  if (r.canceled) return;
  settings.vpn.wgConfig = r.filePaths[0]; saveSettings(); pushState();
});
ipcMain.handle('vpn:pickBinary', async () => {
  const r = await dialog.showOpenDialog(win, { title: 'Locate wireproxy', properties: ['openFile'] });
  if (r.canceled) return;
  settings.vpn.wireproxyPath = r.filePaths[0]; saveSettings(); pushState();
});
ipcMain.handle('state', () => pushState());

// ---------- menu & shortcuts ----------
function buildMenu() {
  const send = (ch) => () => win.webContents.send(ch);
  Menu.setApplicationMenu(Menu.buildFromTemplate([
    { role: 'appMenu' },
    { label: 'File', submenu: [
      { label: 'New Tab', accelerator: 'Cmd+T', click: () => createTab() },
      { label: 'Close Tab', accelerator: 'Cmd+W', click: () => closeTab(activeId) },
      { label: 'Open Location', accelerator: 'Cmd+L', click: send('focus-url') },
    ] },
    { role: 'editMenu' },
    { label: 'View', submenu: [
      { label: 'Reload', accelerator: 'Cmd+R', click: () => active()?.reload() },
      { label: 'Back', accelerator: 'Cmd+[', click: () => active()?.navigationHistory.goBack() },
      { label: 'Forward', accelerator: 'Cmd+]', click: () => active()?.navigationHistory.goForward() },
      { label: 'Next Tab', accelerator: 'Cmd+Alt+Right', click: () => cycle(1) },
      { label: 'Previous Tab', accelerator: 'Cmd+Alt+Left', click: () => cycle(-1) },
      { type: 'separator' },
      { label: 'Developer Tools (page)', accelerator: 'Cmd+Alt+I', click: () => active()?.toggleDevTools() },
      { role: 'togglefullscreen' },
    ] },
    { role: 'windowMenu' },
  ]));
}
function cycle(dir) {
  const ids = [...tabs.keys()]; if (ids.length < 2) return;
  activateTab(ids[(ids.indexOf(activeId) + dir + ids.length) % ids.length]);
}

// ---------- boot ----------
app.whenReady().then(async () => {
  loadSettings();
  browse = session.fromPartition('persist:veil');
  browse.setPermissionRequestHandler((_wc, perm, cb) => cb(['fullscreen', 'clipboard-sanitized-write'].includes(perm)));

  win = new BrowserWindow({
    width: 1280, height: 820, minWidth: 600, minHeight: 400,
    titleBarStyle: 'hiddenInset', trafficLightPosition: { x: 14, y: 12 },
    backgroundColor: '#16171b',
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: true },
  });
  win.loadFile(path.join(__dirname, 'ui', 'index.html'));
  win.on('resize', layout);
  buildMenu();

  await applyVpn();               // lock down networking before the first page loads
  createTab(HOME);
  initBlocker().catch((e) => console.error('Ad blocker failed to load:', e));
});
app.on('window-all-closed', () => app.quit());
app.on('before-quit', stopWireproxy);
// Pages may not launch external apps (mailto:, zoom:, …) on their own; only mailto is handed off.
app.on('web-contents-created', (_e, wc) => {
  wc.on('will-navigate', (ev, url) => {
    if (/^(https?|about|file|data|blob):/.test(url)) return;
    ev.preventDefault();
    if (url.startsWith('mailto:')) shell.openExternal(url);
  });
});
