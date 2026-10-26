// Test-only: a fake "VPN" forward proxy + a local page that tries to load ad/tracker scripts.
const http = require('http');
const site = http.createServer((req, res) => {
  if (req.url === '/ip') return res.end('203.0.113.7');
  res.setHeader('content-type', 'text/html');
  res.end(`<!doctype html><title>Ad test page</title><body style="font:16px sans-serif;padding:30px">
<h1>Ad test page</h1><p>Scripts below should be blocked.</p>
<script async src="https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"></script>
<script async src="https://www.googletagmanager.com/gtag/js?id=G-TEST"></script>
<script src="https://connect.facebook.net/en_US/fbevents.js"></script>
<img src="https://ad.doubleclick.net/ddm/ad/x.gif">
<div class="adsbygoogle" style="display:block;width:300px;height:250px;background:#fc0">AD BOX (should be hidden)</div>
</body>`);
}).listen(8765);
let proxied = 0;
http.createServer((req, res) => {
  proxied++;
  const u = new URL(req.url);
  http.get({ host: '127.0.0.1', port: u.port || 80, path: u.pathname + u.search }, (r) => { res.writeHead(r.statusCode, r.headers); r.pipe(res); })
    .on('error', () => { res.writeHead(502); res.end(); });
}).on('connect', (_req, sock) => { sock.end('HTTP/1.1 502 Bad Gateway\r\n\r\n'); })
  .listen(8766, () => console.log('fixtures up'));
process.on('SIGTERM', () => { console.log('proxied requests:', proxied); process.exit(0); });
