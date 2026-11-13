#!/data/data/com.termux/files/usr/bin/bash
# WAN watchdog for Xiaomi Router AX1500 (RD12, fw 1.0.31), runs in Termux on the Mi Box.
# The router does not recover WAN (DHCP) after an ISP outage; only a reboot helps.
# Policy: reboot the router as soon as an outage is confirmed (about 15-25 s), then wait
# for the internet; if it does not come back, reboot again with a growing pause.
#
#   wan-watchdog.sh              run the loop (started by ~/.termux/boot/00-wan-watchdog)
#   wan-watchdog.sh login-test   log in to the router and print WAN status, no reboot
#   wan-watchdog.sh reboot-now   log in and reboot the router immediately
#
# Tunables can be overridden from the environment (used for testing).

export PATH=/data/data/com.termux/files/usr/bin:/system/bin:$PATH

ROUTER=${ROUTER:-192.168.31.1}
HOSTS=${HOSTS:-"1.1.1.1 8.8.8.8"}
CHECK_EVERY=${CHECK_EVERY:-5}        # seconds between checks while online
CONFIRM=${CONFIRM:-3}                # extra failed rounds (3 s apart) before a reboot
GRACE=${GRACE:-30}                   # silent period after the watchdog starts (Wi-Fi coming up)
WAITS=${WAITS:-"240 480 960 1200"}   # wait for the internet after reboot 1, 2, 3, 4+ (seconds)
RECONNECT_WAIT=${RECONNECT_WAIT:-40} # wait for the internet after a WAN reconnect before rebooting

DIR=${DIR:-$HOME/.wan-watchdog}
PASSFILE=${PASSFILE:-$HOME/.wan-watchdog/router_pass}   # router admin password, chmod 600
LOG=$DIR/watchdog.log
PIDFILE=$DIR/pid
mkdir -p "$DIR"

log() {
  echo "$(date '+%F %T') $*" >> "$LOG"
  if [ "$(wc -c < "$LOG")" -gt 200000 ]; then
    tail -n 500 "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
  fi
}

online() {
  local h
  for h in $HOSTS; do
    /system/bin/ping -c1 -W2 "$h" >/dev/null 2>&1 && return 0
  done
  return 1
}

router_up() { /system/bin/ping -c1 -W2 "$ROUTER" >/dev/null 2>&1; }

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

router_reboot() {
  local token rsp wan
  token=$(router_login) || { log "router login failed"; return 1; }
  # What the router thinks about WAN at the moment of the outage (for later diagnosis).
  wan=$(curl -s -m 5 "http://$ROUTER/cgi-bin/luci/;stok=$token/api/xqnetwork/pppoe_status")
  log "router WAN state before reboot: $wan"
  rsp=$(curl -s -m 10 "http://$ROUTER/cgi-bin/luci/;stok=$token/api/xqsystem/reboot?client=web")
  log "reboot sent, router answered: $rsp"
  case "$rsp" in *'"code":0'*) return 0 ;; *) return 1 ;; esac
}

# Lighter action first: stop and start the router's internet connection (the same
# calls as the Disconnect/Connect buttons on its WAN page), no full reboot.
wan_reconnect() {
  local token a b wan
  token=$(router_login) || { log "router login failed"; return 1; }
  wan=$(curl -s -m 5 "http://$ROUTER/cgi-bin/luci/;stok=$token/api/xqnetwork/pppoe_status")
  log "router WAN state before reconnect: $wan"
  a=$(curl -s -m 15 "http://$ROUTER/cgi-bin/luci/;stok=$token/api/xqnetwork/pppoe_stop")
  sleep 3
  b=$(curl -s -m 15 "http://$ROUTER/cgi-bin/luci/;stok=$token/api/xqnetwork/pppoe_start")
  log "WAN reconnect sent: stop=$a start=$b"
  case "$b" in *'"code":0'*) return 0 ;; *) return 1 ;; esac
}

# --- Telegram report to the owner (bot token and chat id in $TGFILE, chmod 600) ---
TGFILE=$DIR/tg
PENDING=$DIR/pending_msg

tg_send() {
  local tok chat rsp
  [ -f "$TGFILE" ] || return 1
  tok=$(sed -n 1p "$TGFILE"); chat=$(sed -n 2p "$TGFILE")
  rsp=$(curl -s -m 15 "https://api.telegram.org/bot$tok/sendMessage" \
        --data-urlencode "chat_id=$chat" --data-urlencode "text=$1") || return 1
  case "$rsp" in *'"ok":true'*) return 0 ;; *) return 1 ;; esac
}

# Keeps the report on disk until it is delivered (internet may still be shaky).
report() {
  printf '%s' "$1" > "$PENDING"
  flush_report
}

flush_report() {
  [ -f "$PENDING" ] || return 0
  if tg_send "$(cat "$PENDING")"; then rm -f "$PENDING"; log "report sent to Telegram"; fi
}

fmt_dur() {
  local s=$1
  if [ "$s" -lt 60 ]; then echo "${s} с"; else echo "$((s / 60)) хв $((s % 60)) с"; fi
}

case "$1" in
  login-test)
    token=$(router_login) || { echo "login FAILED"; exit 1; }
    echo "login OK"
    curl -s -m 10 "http://$ROUTER/cgi-bin/luci/;stok=$token/api/xqnetwork/pppoe_status"; echo
    online && echo "internet: up" || echo "internet: DOWN"
    exit 0 ;;
  reboot-now)
    router_reboot && echo "reboot accepted" || { echo "reboot FAILED"; exit 1; }
    exit 0 ;;
  reconnect-now)
    t=$SECONDS
    wan_reconnect && echo "reconnect accepted" || echo "reconnect FAILED"
    for i in $(seq 1 30); do online && { echo "internet up $((SECONDS - t)) s after reconnect"; exit 0; }; sleep 2; done
    echo "internet still DOWN 60 s after reconnect"; exit 1 ;;
  tg-test)
    tg_send "${2:-🌐 Перевірка: сторож інтернету на Mi Box може писати вам сюди.}" && echo "sent" || { echo "send FAILED"; exit 1; }
    exit 0 ;;
esac

# --- loop ---
if [ -f "$PIDFILE" ] && grep -q wan-watchdog "/proc/$(cat "$PIDFILE")/cmdline" 2>/dev/null; then
  echo "already running, pid $(cat "$PIDFILE")"; exit 0
fi
echo $$ > "$PIDFILE"

log "watchdog started (check every ${CHECK_EVERY}s, confirm $CONFIRM, grace ${GRACE}s, waits: $WAITS)"
sleep "$GRACE"
log "watching"

# True when the outage is confirmed: every extra round fails too.
confirmed_down() {
  local i
  for ((i = 0; i < CONFIRM; i++)); do
    sleep 3
    online && return 1
  done
  return 0
}

# Waits up to $1 seconds for the internet; true when it is back.
wait_online() {
  local until=$((SECONDS + $1))
  while [ "$SECONDS" -lt "$until" ]; do
    online && return 0
    sleep "$CHECK_EVERY"
  done
  return 1
}

lan_lost=0
while :; do
  if online || ! confirmed_down; then
    if [ "$lan_lost" = 1 ]; then log "internet is up again"; lan_lost=0; fi
    flush_report
    sleep "$CHECK_EVERY"
    continue
  fi
  # No internet AND no router: the box itself is off the Wi-Fi (sleep, Wi-Fi off).
  # A reboot is impossible and pointless then; wait until the LAN is back.
  if ! router_up; then
    [ "$lan_lost" = 1 ] || log "box is off the LAN (router does not answer), waiting"
    lan_lost=1
    sleep "$CHECK_EVERY"
    continue
  fi
  if [ "$lan_lost" = 1 ]; then
    # The router (or the box's Wi-Fi) has just come back: give WAN time to get its address,
    # otherwise a router that rebooted on its own would be rebooted again in a loop.
    lan_lost=0
    log "box is back on the LAN, giving WAN ${LAN_GRACE:-90}s"
    if wait_online "${LAN_GRACE:-90}"; then
      log "internet is up"
      continue
    fi
  fi

  down_at=$SECONDS
  down_clock=$(date +%H:%M)
  log "internet is down"
  n=0; fixed=
  while :; do
    # Pick the wait for this attempt: the last value repeats.
    n=$((n + 1)); w=; i=0
    for x in $WAITS; do i=$((i + 1)); w=$x; [ "$i" -ge "$n" ] && break; done

    # Every attempt: reconnect WAN first; only if that does not help, reboot.
    if router_up; then
      log "reconnecting WAN (attempt $n)"
      wan_reconnect || log "WAN reconnect command failed"
      if wait_online "$RECONNECT_WAIT"; then fixed="перепідключення інтернету на роутері"; break; fi
      log "no internet ${RECONNECT_WAIT}s after WAN reconnect, rebooting router (attempt $n)"
    fi
    if router_up; then
      router_reboot || log "reboot command failed"
    else
      log "router does not answer on the LAN, cannot reboot it (attempt $n)"
    fi
    if wait_online "$w"; then fixed="перезавантаження роутера"; break; fi
    log "still no internet ${w}s after attempt $n"
  done

  took=$((SECONDS - down_at))
  log "internet is back, $took s after it went down, attempts: $n, fixed by: $fixed"
  msg="🌐 Інтернет удома зникав о $down_clock, не було $(fmt_dur "$took").
Допомогло: $fixed"
  [ "$n" -gt 1 ] && msg="$msg (спроба $n)"
  report "$msg"
done
