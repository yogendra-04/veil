#!/usr/bin/env bash
# Veil VPN server — sets up WireGuard on a fresh Ubuntu/Debian VPS and makes client configs.
#
#   sudo bash setup-wireguard.sh            # first run: installs server + "mac" and "android" clients
#   sudo bash setup-wireguard.sh add NAME   # later: add another device
#
# Client configs land in /root/veil-clients/.
set -euo pipefail

[[ $EUID -eq 0 ]] || { echo "Run as root (sudo)."; exit 1; }

WG_DIR=/etc/wireguard
CLIENT_DIR=/root/veil-clients
PORT=${PORT:-51820}
SUBNET=10.66.66
DNS=${DNS:-"1.1.1.1, 1.0.0.1"}

add_client() {
  local name=$1
  [[ $name =~ ^[a-zA-Z0-9_-]+$ ]] || { echo "Client name: letters, numbers, - and _ only"; exit 1; }
  [[ -f $CLIENT_DIR/$name.conf ]] && { echo "Client '$name' already exists: $CLIENT_DIR/$name.conf"; return; }
  local used next priv pub psk
  used=$(grep -oP "AllowedIPs = $SUBNET\.\K[0-9]+" $WG_DIR/wg0.conf || true)
  next=2; while grep -qx "$next" <<<"$used"; do next=$((next+1)); done
  (( next < 255 )) || { echo "Subnet full"; exit 1; }

  priv=$(wg genkey); pub=$(wg pubkey <<<"$priv"); psk=$(wg genpsk)
  cat >>$WG_DIR/wg0.conf <<EOF

[Peer]
# $name
PublicKey = $pub
PresharedKey = $psk
AllowedIPs = $SUBNET.$next/32
EOF
  umask 077
  cat >$CLIENT_DIR/$name.conf <<EOF
[Interface]
PrivateKey = $priv
Address = $SUBNET.$next/32
DNS = $DNS

[Peer]
PublicKey = $(cat $WG_DIR/server.pub)
PresharedKey = $psk
Endpoint = $(cat $WG_DIR/endpoint)
AllowedIPs = 0.0.0.0/0
PersistentKeepalive = 25
EOF
  wg syncconf wg0 <(wg-quick strip wg0)
  echo "✓ Client '$name' → $CLIENT_DIR/$name.conf"
}

if [[ ${1:-} == add ]]; then
  [[ -f $WG_DIR/wg0.conf ]] || { echo "Run without arguments first to set up the server."; exit 1; }
  add_client "${2:?Usage: $0 add NAME}"
  exit 0
fi

[[ -f $WG_DIR/wg0.conf ]] && { echo "WireGuard is already set up. Use: $0 add NAME"; exit 0; }

echo "→ Installing WireGuard…"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq wireguard iptables curl >/dev/null

IFACE=$(ip -4 route show default | awk '{print $5; exit}')
PUBLIC_IP=$(curl -4 -fsS --max-time 5 https://api.ipify.org || ip -4 addr show "$IFACE" | grep -oP 'inet \K[0-9.]+' | head -1)
echo "→ Public IP $PUBLIC_IP on $IFACE, UDP port $PORT"

mkdir -p $WG_DIR $CLIENT_DIR; chmod 700 $WG_DIR $CLIENT_DIR
umask 077
wg genkey | tee $WG_DIR/server.key | wg pubkey >$WG_DIR/server.pub
echo "$PUBLIC_IP:$PORT" >$WG_DIR/endpoint

cat >$WG_DIR/wg0.conf <<EOF
[Interface]
Address = $SUBNET.1/24
ListenPort = $PORT
PrivateKey = $(cat $WG_DIR/server.key)
PostUp = iptables -t nat -A POSTROUTING -s $SUBNET.0/24 -o $IFACE -j MASQUERADE; iptables -A FORWARD -i wg0 -j ACCEPT; iptables -A FORWARD -o wg0 -m state --state RELATED,ESTABLISHED -j ACCEPT
PostDown = iptables -t nat -D POSTROUTING -s $SUBNET.0/24 -o $IFACE -j MASQUERADE; iptables -D FORWARD -i wg0 -j ACCEPT; iptables -D FORWARD -o wg0 -m state --state RELATED,ESTABLISHED -j ACCEPT
EOF

echo "net.ipv4.ip_forward=1" >/etc/sysctl.d/99-veil-wireguard.conf
sysctl -q --system

# Open the port if ufw is active
if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then ufw allow "$PORT"/udp >/dev/null; fi

systemctl enable --now wg-quick@wg0 >/dev/null

add_client mac
add_client android

cat <<EOF

Done. Next:
  Mac:      scp root@$PUBLIC_IP:$CLIENT_DIR/mac.conf ~/Documents/veil-mac.conf
            then Veil → VPN pill → Choose .conf…
  Android:  get android.conf onto the phone (Drive, email to yourself, or copy the text from
            cat $CLIENT_DIR/android.conf), then Veil → VPN chip → Import file / Paste config
  If your cloud provider has its own firewall (AWS, Oracle, GCP…), allow UDP $PORT there too.
EOF
