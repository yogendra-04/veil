const { app, BrowserWindow, webContents } = require('electron');
const fs = require('fs'), path = require('path');
const out = process.env.SHOT_DIR, ud = path.join(out, 'ud2'); fs.mkdirSync(ud, { recursive: true });
app.setPath('userData', ud);
// reuse the cached filter engine from the first run so we don't need the network
try { fs.copyFileSync(path.join(out, 'userdata', 'adblock-engine.bin'), path.join(ud, 'adblock-engine.bin')); } catch {}
fs.writeFileSync(path.join(ud, 'settings.json'), JSON.stringify({ adblock: true, vpn: { enabled: true, mode: 'proxy', proxy: 'http://127.0.0.1:8766' } }));
process.env.VEIL_IP_URL = 'http://localhost:8765/ip';
require('../src/main.js');
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
app.whenReady().then(async () => {
  await wait(4000);
  const win = BrowserWindow.getAllWindows()[0];
  const ui = (js) => win.webContents.executeJavaScript(js);
  await ui('veil.call("nav:go","http://localhost:8765/page")');
  await wait(4000);
  const tab = webContents.getAllWebContents().find((w) => w.getURL().includes('8765'));
  const hidden = await tab.executeJavaScript('getComputedStyle(document.querySelector(".adsbygoogle")).display');
  console.log('pill:', await ui('document.getElementById("vpntxt").textContent'), '| shield:', await ui('document.getElementById("shieldtxt").textContent'), '| ad box display:', hidden);
  fs.writeFileSync(path.join(out, '3-ui.png'), (await win.webContents.capturePage()).toPNG());
  fs.writeFileSync(path.join(out, '3-page.png'), (await tab.capturePage()).toPNG());
  // Turn the VPN off → direct; turn the proxy off → kill switch must block
  await ui('veil.call("vpn:set",{proxy:"http://127.0.0.1:1"})'); await wait(2500);
  await ui('veil.call("nav:reload")'); await wait(2500);
  console.log('dead proxy pill:', await ui('document.getElementById("vpntxt").textContent'), '| error:', await ui('document.getElementById("errmsg").textContent'));
  fs.writeFileSync(path.join(out, '4-ui.png'), (await win.webContents.capturePage()).toPNG());
  app.quit();
});
