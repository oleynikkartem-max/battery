#!/data/data/com.termux/files/usr/bin/bash
# Router watchdog for Xiaomi Router AX1500 (RD12, fw 1.0.31), runs in Termux on the Mi Box.
#
# Every 5 s the box checks two things:
#   - internet: ping 1.1.1.1 / 8.8.8.8;
#   - the router's DNS/DHCP service: the router must answer its own name miwifi.com
#     (TTL 0, so Android never caches it). 2026-11-15 this service hung: the internet
#     worked, but phones lost names and then could not join the Wi-Fi at all.
# If either one stays broken (internet 15 s, DNS 60 s), the box reboots the router, waits,
# and repeats with growing pauses; afterwards it sends the owner a Telegram report.
# If the router itself does not answer, the box is off the LAN or the router is off/booting:
# nothing can be done, so it just waits.
#
#   wan-watchdog.sh              run the loop (started by ~/.termux/boot/00-wan-watchdog)
#   wan-watchdog.sh status       log in to the router, print WAN state, internet and DNS checks
#   wan-watchdog.sh reboot-now   log in and reboot the router immediately
#   wan-watchdog.sh tg-test      send a test Telegram message
#
# Tunables can be overridden from the environment (used for testing).

export PATH=/data/data/com.termux/files/usr/bin:/system/bin:$PATH

ROUTER=${ROUTER:-192.168.31.1}
HOSTS=${HOSTS:-"1.1.1.1 8.8.8.8"}
DNS_NAME=${DNS_NAME:-miwifi.com}
CHECK_EVERY=${CHECK_EVERY:-5}
CONFIRM_NET=${CONFIRM_NET:-15}        # seconds without internet before a reboot
CONFIRM_DNS=${CONFIRM_DNS:-60}        # seconds without router DNS before a reboot
LAN_GRACE=${LAN_GRACE:-90}            # after the router comes back by itself, let it settle
GRACE=${GRACE:-30}                    # after the watchdog starts (Wi-Fi coming up)
WAITS=${WAITS:-"240 480 960 1200"}    # wait for recovery after reboot 1, 2, 3, 4+ (seconds)

DIR=${DIR:-$HOME/.wan-watchdog}
PASSFILE=${PASSFILE:-$HOME/.wan-watchdog/router_pass}   # router admin password, chmod 600
TGFILE=$DIR/tg                                          # bot token, owner chat id; chmod 600
LOG=$DIR/watchdog.log
PIDFILE=$DIR/pid
PENDING=$DIR/pending_msg
mkdir -p "$DIR"

log() {
  echo "$(date '+%F %T') $*" >> "$LOG"
  if [ "$(wc -c < "$LOG")" -gt 200000 ]; then
    tail -n 500 "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
  fi
}

# --- checks ---
online() {
  local h
  for h in $HOSTS; do /system/bin/ping -c1 -W2 "$h" >/dev/null 2>&1 && return 0; done
  return 1
}
dns_ok() {
  local i
  for i in 1 2; do
    /system/bin/ping -c1 -W1 "$DNS_NAME" 2>&1 | head -n1 | grep -q "($ROUTER)" && return 0
    sleep 1
  done
  return 1
}
router_up() { /system/bin/ping -c1 -W2 "$ROUTER" >/dev/null 2>&1; }

# Prints what is broken ("internet" or "dns"), nothing when all is fine.
problem() {
  online || { echo internet; return; }
  dns_ok || echo dns
}

# --- router API ---
# Prints the session token on success.
router_login() {
  local page key mac nonce pw h1 h2 rsp token
  page=$(curl -s -m 10 "http://$ROUTER/cgi-bin/luci/web") || return 1
  key=$(printf '%s' "$page" | sed -n "s/.*key: *'\([0-9a-f]*\)'.*/\1/p" | head -n1)
  mac=$(printf '%s' "$page" | sed -n "s/.*deviceId = '\([0-9a-fA-F:]*\)'.*/\1/p" | head -n1)
  [ -n "$key" ] && [ -n "$mac" ] || return 1
  pw=$(cat "$PASSFILE") || return 1
  nonce="0_${mac}_$(date +%s)_$((RANDOM % 10000))"
  h1=$(printf '%s' "$pw$key" | sha256sum | cut -d' ' -f1)
  h2=$(printf '%s' "$nonce$h1" | sha256sum | cut -d' ' -f1)
  rsp=$(curl -s -m 10 "http://$ROUTER/cgi-bin/luci/api/xqsystem/login" \
        --data-urlencode "username=admin" --data-urlencode "password=$h2" \
        --data-urlencode "logtype=2" --data-urlencode "nonce=$nonce") || return 1
  token=$(printf '%s' "$rsp" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
  [ -n "$token" ] || return 1
  printf '%s' "$token"
}

wan_state() { curl -s -m 5 "http://$ROUTER/cgi-bin/luci/;stok=$1/api/xqnetwork/pppoe_status"; }

router_reboot() {
  local token rsp i
  token=$(router_login) || { log "router login failed"; return 1; }
  log "router WAN state before reboot: $(wan_state "$token")"
  rsp=$(curl -s -m 10 "http://$ROUTER/cgi-bin/luci/;stok=$token/api/xqsystem/reboot?client=web")
  log "reboot sent, router answered: $rsp"
  case "$rsp" in *'"code":0'*) ;; *) return 1 ;; esac
  # The router keeps answering for ~20 s after the command; wait until it really goes down.
  for i in $(seq 1 30); do router_up || break; sleep 2; done
  return 0
}

# --- Telegram report (kept on disk until delivered) ---
tg_send() {
  local tok chat rsp
  [ -f "$TGFILE" ] || return 1
  tok=$(sed -n 1p "$TGFILE"); chat=$(sed -n 2p "$TGFILE")
  rsp=$(curl -s -m 15 "https://api.telegram.org/bot$tok/sendMessage" \
        --data-urlencode "chat_id=$chat" --data-urlencode "text=$1") || return 1
  case "$rsp" in *'"ok":true'*) return 0 ;; *) return 1 ;; esac
}
report() { printf '%s' "$1" > "$PENDING"; flush_report; }
flush_report() {
  [ -f "$PENDING" ] || return 0
  if tg_send "$(cat "$PENDING")"; then rm -f "$PENDING"; log "report sent to Telegram"; fi
}

fmt_dur() {
  local s=$1
  if [ "$s" -lt 60 ]; then echo "${s} с"; else echo "$((s / 60)) хв $((s % 60)) с"; fi
}
what() {
  case "$1" in
    internet) echo "зник інтернет" ;;
    *) echo "роутер перестав роздавати адреси й імена (Wi-Fi не пускав пристрої)" ;;
  esac
}

case "$1" in
  status)
    if token=$(router_login); then echo "login OK"; wan_state "$token"; echo; else echo "login FAILED"; fi
    online && echo "internet: up" || echo "internet: DOWN"
    dns_ok && echo "router DNS: ok" || echo "router DNS: NOT answering"
    exit 0 ;;
  reboot-now)
    router_reboot && echo "reboot accepted" || { echo "reboot FAILED"; exit 1; }
    exit 0 ;;
  tg-test)
    tg_send "${2:-🌐 Перевірка: сторож роутера на Mi Box може писати вам сюди.}" && echo "sent" || { echo "send FAILED"; exit 1; }
    exit 0 ;;
esac

# --- loop ---
if [ -f "$PIDFILE" ] && grep -q wan-watchdog "/proc/$(cat "$PIDFILE")/cmdline" 2>/dev/null; then
  echo "already running, pid $(cat "$PIDFILE")"; exit 0
fi
echo $$ > "$PIDFILE"

log "watchdog started (internet ${CONFIRM_NET}s, dns ${CONFIRM_DNS}s, waits: $WAITS)"
sleep "$GRACE"
log "watching"

# Waits up to $1 seconds until nothing is broken; true when fixed.
wait_fixed() {
  local until=$((SECONDS + $1))
  while [ "$SECONDS" -lt "$until" ]; do
    [ -z "$(problem)" ] && return 0
    sleep "$CHECK_EVERY"
  done
  return 1
}

lan_lost=0
while :; do
  p=$(problem)
  if [ -z "$p" ]; then
    [ "$lan_lost" = 1 ] && { log "all good again"; lan_lost=0; }
    flush_report
    sleep "$CHECK_EVERY"
    continue
  fi

  # The router itself does not answer: the box is off the Wi-Fi, or the router is off or
  # booting. A reboot is impossible then; wait for the LAN.
  if ! router_up; then
    [ "$lan_lost" = 1 ] || log "router does not answer ($p), waiting"
    lan_lost=1
    sleep "$CHECK_EVERY"
    continue
  fi
  # The router has just come back by itself: let it settle, so a router that rebooted on
  # its own (or was switched on by hand) is not rebooted again at once.
  if [ "$lan_lost" = 1 ]; then
    lan_lost=0
    log "router is back, giving it ${LAN_GRACE}s"
    wait_fixed "$LAN_GRACE" && { log "all good"; continue; }
    p=$(problem); [ -n "$p" ] || continue
  fi

  # Confirm: the problem must last the whole confirm time.
  if [ "$p" = dns ]; then c=$CONFIRM_DNS; else c=$CONFIRM_NET; fi
  t=$SECONDS; gone=0
  while [ $((SECONDS - t)) -lt "$c" ]; do
    sleep "$CHECK_EVERY"
    [ -z "$(problem)" ] && { gone=1; break; }
  done
  if [ "$gone" = 1 ]; then log "short glitch ($p, $((SECONDS - t)) s), no action"; continue; fi
  router_up || continue                       # the router went away meanwhile

  down_at=$((SECONDS - c))
  down_clock=$(date -d "@$(( $(date +%s) - c ))" +%H:%M)
  log "problem: $p, rebooting the router"
  n=0
  while :; do
    n=$((n + 1)); w=; i=0
    for x in $WAITS; do i=$((i + 1)); w=$x; [ "$i" -ge "$n" ] && break; done
    if router_up; then
      if router_reboot; then rebooted=1; else rebooted=; log "reboot command failed (attempt $n)"; fi
    else
      rebooted=; log "router does not answer, cannot reboot it (attempt $n)"
    fi
    wait_fixed "$w" && break
    log "still broken ($(problem)) ${w}s after attempt $n"
  done

  took=$((SECONDS - down_at))
  log "all good again, $took s after the problem ($p) started, attempts: $n"
  if [ -n "$rebooted" ]; then
    msg="🌐 О $down_clock $(what "$p"). Приставка перезавантажила роутер, через $(fmt_dur "$took") все працює."
    [ "$n" -gt 1 ] && msg="$msg (спроба $n)"
  else
    msg="🌐 О $down_clock $(what "$p"). Через $(fmt_dur "$took") все знову працює (команда роутеру не пройшла — мабуть, його перезавантажили вручну)."
  fi
  report "$msg"
done
