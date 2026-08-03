#!/data/data/com.termux/files/usr/bin/sh
# ssh-bridge.sh — expose this phone's sshd (port 8022) over a plain :443 web
# tunnel, so Claude (limited to :443 outbound) can SSH in. Prints ONE line to
# paste back. No VPN, no browser, no account.
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$PREFIX/bin:$PREFIX/bin/applets:/system/bin:/system/xbin:$PATH"
export HOME="${HOME:-/data/data/com.termux/files/home}"
B="$HOME/.sshbridge"; mkdir -p "$B"; cd "$B"

command -v node >/dev/null 2>&1 || pkg install -y nodejs >/dev/null 2>&1
[ -d node_modules/ws ] || { echo "installing ws (one time)…"; npm init -y >/dev/null 2>&1; npm install ws >/dev/null 2>&1; }

cat > ws-server.mjs <<'WSS'
import { WebSocketServer } from 'ws';
import net from 'node:net';
const WSPORT=parseInt(process.env.WSPORT||'8090',10), TARGET=parseInt(process.env.TARGET||'8022',10);
const wss=new WebSocketServer({port:WSPORT,host:'127.0.0.1'});
wss.on('connection',ws=>{const tcp=net.connect(TARGET,'127.0.0.1');
 ws.on('message',d=>tcp.write(d)); tcp.on('data',d=>ws.readyState===1&&ws.send(d));
 const done=()=>{try{ws.close()}catch{} try{tcp.destroy()}catch{}};
 ws.on('close',done); ws.on('error',done); tcp.on('close',done); tcp.on('error',done);});
console.error('ws-server '+WSPORT+'->'+TARGET);
WSS

pgrep -x sshd >/dev/null 2>&1 || sshd
pkill -f ws-server.mjs 2>/dev/null
WSPORT=8090 TARGET=8022 nohup node ws-server.mjs >/dev/null 2>&1 &
sleep 1

CF="$B/cloudflared"
if [ ! -x "$CF" ]; then
  echo "fetching tunnel (one time)…"
  curl -fsSL -o "$CF" "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64" && chmod +x "$CF"
fi
echo "opening :443 tunnel…"
pkill -f "cloudflared.*8090" 2>/dev/null
"$CF" tunnel --no-autoupdate --url http://127.0.0.1:8090 >"$B/cf.log" 2>&1 &
URL=""
for i in $(seq 1 45); do
  URL=$(grep -oE 'https://[a-z0-9]+(-[a-z0-9]+)+\.trycloudflare\.com' "$B/cf.log" | head -1)
  [ -n "$URL" ] && break; sleep 1
done
echo
echo "=================================================================="
if [ -n "$URL" ]; then
  WSURL=$(echo "$URL" | sed 's#^https#wss#')
  echo "  BRIDGE UP.  Paste this ONE line back to Claude:"
  echo
  echo "    WSURL=$WSURL"
  echo
  echo "  (leave this window running — it is the open door)"
else
  echo "  tunnel did not come up. Screenshot this to Claude:"
  tail -6 "$B/cf.log"
fi
echo "=================================================================="
wait
