# Veil — private browser with ad blocker + VPN

Two apps and one server script:

| Folder | What it is |
|---|---|
| `mac/` | macOS browser (Electron/Chromium). Tabs, ad & tracker blocking, VPN with kill switch. |
| `android/` | Android browser (Kotlin/WebView). Tabs, ad & tracker blocking, built-in WireGuard VPN. |
| `server/` | One script that turns a cheap Ubuntu VPS into your own WireGuard VPN server. |

Both apps send **only the browser's traffic** through the VPN. Everything else on your Mac/phone keeps its normal connection.

---

## 1. VPN server (≈10 minutes, once)

Rent any small Ubuntu 22.04/24.04 VPS (1 vCPU / 512 MB is plenty — Hetzner, DigitalOcean, Vultr, Lightsail, Oracle free tier…). Pick a region near you or wherever you want to "appear" from.

```bash
scp server/setup-wireguard.sh root@YOUR_SERVER_IP:
ssh root@YOUR_SERVER_IP 'bash setup-wireguard.sh'
```

It installs WireGuard, enables NAT, and creates `/root/veil-clients/mac.conf` and `android.conf`.
Add more devices later with `bash setup-wireguard.sh add laptop2`.
If your provider has its own firewall panel, allow **UDP 51820**.

Already pay for a VPN (Mullvad, Proton, IVPN…)? Download a **WireGuard config** from their site and skip this step.

---

## 2. macOS app

Needs Node.js 20+ (`brew install node`).

```bash
cd mac
npm install
brew install wireproxy        # userspace WireGuard, no admin rights needed
npm start                     # run it
npm run dist                  # build Veil.dmg into mac/dist/
```

In Veil: click the **VPN** pill → turn it on → **Choose .conf…** → pick `mac.conf`.
The pill turns green and shows the IP sites now see.

**How it works**
- **Ad blocker:** Ghostery's engine with EasyList, EasyPrivacy and uBlock filters, plus cosmetic hiding of leftover ad boxes. The green pill shows how many requests were blocked on the current page; click it to turn blocking off/on.
- **VPN:** Veil runs `wireproxy` (WireGuard in user space) and routes the whole browser through it as SOCKS5, so DNS goes through the tunnel too. There's also a **Proxy** mode for SOCKS5/HTTP proxies (e.g. `ssh -D 1080 you@server` → `socks5://127.0.0.1:1080`).
- **Kill switch:** while the VPN is on, Veil never falls back to your real connection — if the tunnel drops, pages are blocked until it reconnects.
- **WebRTC leak protection:** WebRTC can only use the proxied route.
- Shortcuts: ⌘T new tab, ⌘W close, ⌘L address bar, ⌘R reload, ⌘[ / ⌘] back/forward, ⌘⌥←/→ switch tabs.

To ship a self-contained app, drop the `wireproxy` binary for your Mac (from github.com/whyvl/wireproxy releases, or `cp $(which wireproxy) mac/bin/`) into `mac/bin/` before `npm run dist`.
The unsigned build will need right-click → Open the first time (or sign it with your Apple Developer ID).

---

## 3. Android app

Needs Android Studio (Ladybug or newer).

1. **File → Open** → choose the `android/` folder. Let Gradle sync (it downloads the SDK pieces and the WireGuard library).
2. Plug in your phone (USB debugging on) and press **Run ▶** — or **Build → Build APK(s)** and install the APK.
3. In Veil: tap the **VPN** chip → **Import file** (pick `android.conf`) or **Paste config** (copy the text first). Accept Android's VPN prompt once.

**How it works**
- **Ad blocker:** blocks requests to ~80k ad, tracker and malware domains (StevenBlack unified hosts list, refreshed weekly), plus CSS that hides empty ad slots. Third-party cookies are off.
- **VPN:** the official WireGuard library (same code as the WireGuard app). The config is locked to Veil with `IncludedApplications`, so only the browser is tunneled. Android shows its normal key icon.
- **Kill switch:** while the VPN is on but not connected, every page and request is blocked.
- Popups only open when you tap something. Veil can be set as your default browser (Settings → Apps → Default apps).

---

## Honest limits

- A VPN hides your IP and traffic from your ISP/Wi-Fi, not from the sites you log into. Your VPN server provider can see that traffic leaves their machine.
- The Android blocker works at the domain level; YouTube in-video ads are served from the same domains as videos and won't be blocked. The Mac blocker is stronger (full filter-list syntax).
- These are personal-use builds: no auto-update, no sync, no extensions. Before publishing to an app store, add your own signing keys and icons.

## Project layout

```
mac/src/main.js        tabs, ad blocker, VPN/kill switch (main process)
mac/src/ui/            toolbar + settings panel
mac/test/              headless smoke test (local fake VPN + ad page)
android/app/src/main/java/dev/veil/browser/
    MainActivity.kt    tabs, WebView, kill switch, UI
    AdBlocker.kt       hosts-list blocker + cosmetic CSS
    VpnManager.kt      WireGuard tunnel (GoBackend), per-app routing
server/setup-wireguard.sh
```
