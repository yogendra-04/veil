// Headless smoke test (not shipped): boots the app, toggles VPN modes, captures screenshots.
const { app, BrowserWindow, webContents } = require('electron');
const fs = require('fs'), path = require('path');
const out = process.env.SHOT_DIR;
const ud = path.join(out, 'userdata'); fs.mkdirSync(ud, { recursive: true });
app.setPath('userData', ud);
fs.writeFileSync(path.join(ud, 'settings.json'), JSON.stringify({ adblock: true, vpn: { enabled: true, mode: 'proxy', proxy: process.env.TEST_PROXY } }));
app.commandLine.appendSwitch('ignore-certificate-errors'); // sandbox MITM proxy only
require('../src/main.js');
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
app.whenReady().then(async () => {
  await wait(15000);
  const win = BrowserWindow.getAllWindows()[0];
  const snap = async (name) => {
    fs.writeFileSync(path.join(out, name + '-ui.png'), (await win.webContents.capturePage()).toPNG());
    const tab = webContents.getAllWebContents().find((w) => w !== win.webContents && w.getURL().startsWith('http'));
    if (tab) fs.writeFileSync(path.join(out, name + '-page.png'), (await tab.capturePage()).toPNG());
    console.log(name, 'tab url:', tab?.getURL(), 'title:', tab?.getTitle());
  };
  win.webContents.on('ipc-message', () => {});
  const state = () => win.webContents.executeJavaScript('JSON.stringify({vpn: document.getElementById("vpntxt").textContent, shield: document.getElementById("shieldtxt").textContent, status: document.getElementById("vpnStatus").textContent})');
  console.log('proxy mode:', await state());
  await snap('1-proxy');
  // wireguard mode with nothing configured -> must stay blocked (kill switch)
  await win.webContents.executeJavaScript('veil.call("vpn:set",{mode:"wireguard"})');
  await wait(3000);
  console.log('wg unconfigured:', await state());
  await win.webContents.executeJavaScript('veil.call("panel",true)');
  await wait(800);
  await snap('2-panel');
  app.quit();
});
