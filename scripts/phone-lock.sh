#!/bin/bash
# One phone, many agents: whoever drives the phone (install, run, screenshot) holds this lock.
#   ANDROID_SERIAL=<serial> scripts/phone-lock.sh acquire <who>   blocks until that phone is free (default phone: DEFAULT_SERIAL, below)
#   scripts/phone-lock.sh release <who>
#   scripts/phone-lock.sh status
#   FINDR_REMOTE_ADB=1 ANDROID_SERIAL=<serial> scripts/phone-lock.sh ...   a phone plugged into another machine (adb through an ssh tunnel)
#   scripts/phone-lock.sh run <who> <command…>   acquire, run, and always release (even on failure or Ctrl-C)
# mkdir is atomic, so two agents can't both get it; waiters queue first come, first served (a holder that releases
# and re-acquires goes to the back). A lock older than 45 min, or whose `run` process has died, is treated as abandoned.
# One lock per phone, picked by ANDROID_SERIAL.
#
# Your phones are NOT in this file. They live in a git-ignored scripts/phones.local (copy scripts/phones.local.example): the default
# phone, phones that need an explicit go before they may be locked, the lock folder, a remote adb host. See the example for the
# search order. This script never contains a serial number.
DEFAULT_SERIAL=; PERSONAL_SERIALS=""; LOCK_DIR=""; REMOTE_ADB_HOST=""; REMOTE_ADB_PORT=5038
HERE=$(cd "$(dirname "$0")" && pwd)
MAIN_SCRIPTS=""   # the main checkout's scripts/ folder, when this script runs from a linked worktree
_common=$(cd "$HERE" && command git rev-parse --git-common-dir 2>/dev/null) && MAIN_SCRIPTS="$(cd "$HERE" && cd "$_common/.." 2>/dev/null && pwd)/scripts"
for f in "${FINDR_PHONES_FILE:-}" "$HERE/phones.local" "$MAIN_SCRIPTS/phones.local" "$HOME/.config/trawl/phones.local"; do
  if [[ -n "$f" && -f "$f" ]]; then source "$f"; break; fi
done
SERIAL=${ANDROID_SERIAL:-$DEFAULT_SERIAL}
[[ -n "$SERIAL" ]] || { echo "Name the phone: ANDROID_SERIAL=<serial> (or set DEFAULT_SERIAL in scripts/phones.local)." >&2; exit 2; }
LOCK_DIR=${LOCK_DIR:-${TMPDIR:-/tmp}/trawl-phone-locks}
mkdir -p "$LOCK_DIR"
# The default phone keeps the short lock file names (findr-phone.lock) so tools that started before a second phone existed still
# share the right lock; every other phone gets its serial in the name.
if [[ -n "$DEFAULT_SERIAL" && "$SERIAL" == "$DEFAULT_SERIAL" ]]; then
  LOCK=$LOCK_DIR/findr-phone.lock
  QUEUE=$LOCK_DIR/findr-phone.queue   # first come, first served: one ticket per waiter
else
  LOCK=$LOCK_DIR/findr-phone-$SERIAL.lock
  QUEUE=$LOCK_DIR/findr-phone-$SERIAL.queue
fi
mkdir -p "$QUEUE"
# A phone plugged into another machine: FINDR_REMOTE_ADB=1 reaches that machine's adb server through an ssh tunnel
# (localhost:REMOTE_ADB_PORT -> its port 5037, started here if it is down; the remote adb server is started, never killed) and every
# adb call below (and the command a `run` starts) goes through it. The lock is still a local file per serial, so one agent at a time
# holds the phone, whichever machine it hangs off. REMOTE_ADB_HOST comes from phones.local.
if [[ "${FINDR_REMOTE_ADB:-}" == 1 ]]; then
  [[ -n "$REMOTE_ADB_HOST" ]] || { echo "FINDR_REMOTE_ADB=1 needs REMOTE_ADB_HOST in scripts/phones.local." >&2; exit 2; }
  if ! (exec 3<>/dev/tcp/localhost/$REMOTE_ADB_PORT) 2>/dev/null; then
    ssh -o BatchMode=yes -o ConnectTimeout=10 "$REMOTE_ADB_HOST" 'adb start-server >/dev/null 2>&1; echo ok' >/dev/null 2>&1
    ssh -f -N -L "$REMOTE_ADB_PORT:localhost:5037" -o ExitOnForwardFailure=yes -o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=20 "$REMOTE_ADB_HOST" >/dev/null 2>&1
    for _ in 1 2 3 4 5 6 7 8 9 10; do (exec 3<>/dev/tcp/localhost/$REMOTE_ADB_PORT) 2>/dev/null && break; sleep 0.5; done
  fi
  export ADB_SERVER_SOCKET="tcp:localhost:$REMOTE_ADB_PORT"
fi
# A phone that is not a test device (PERSONAL_SERIALS in phones.local): tests only, no pulls, no screenshots, no pushes into shared
# storage, no settings or battery changes, no UI taps, nothing of the owner's data leaves the phone. Only a run that sets
# FINDR_S24_OK=1 (after reading the project's protocol for that phone) may lock it.
if [[ " $PERSONAL_SERIALS " == *" $SERIAL "* && "$1" =~ ^(acquire|run)$ && "${FINDR_S24_OK:-}" != 1 ]]; then
  echo "REFUSED: $SERIAL is a protected personal phone. Read the project's protocol for it and set FINDR_S24_OK=1." >&2
  exit 3
fi
# Side-by-side test builds (com.craneai.findr.<suffix>) index in the background and skew everyone's timings (a trace
# once showed .quick burning 81 CPU-s during a 98 s scroll run). Every `run` window force-stops them at both ends:
# force-stop keeps their data, and Android runs none of their jobs until they are launched again. A window that needs its
# own side build starts it itself. The main app, com.craneai.findr, is left alone.
quiet_side_apps() {
  local pkgs
  pkgs=$(adb -s "$SERIAL" shell pm list packages com.craneai.findr. 2>/dev/null | tr -d '\r' | sed 's/^package://' | grep -v '^com\.craneai\.findr$')
  for p in $pkgs; do adb -s "$SERIAL" shell am force-stop "$p" >/dev/null 2>&1; done
  [[ -n "$pkgs" ]] && echo "side apps stopped: $(echo $pkgs)"
  # A window that faked the battery (dumpsys battery set/unplug, for charging-policy tests) must not leave the phone
  # reporting fake values. reset is a no-op when nothing is overridden. Never on a personal phone (none is overridden there).
  [[ " $PERSONAL_SERIALS " != *" $SERIAL "* ]] && adb -s "$SERIAL" shell dumpsys battery reset >/dev/null 2>&1
  return 0
}
case "$1" in
  acquire)
    ticket="$QUEUE/$(date +%s)$(printf %06d $((RANDOM * 30 + RANDOM % 30)))-$2"
    touch "$ticket"
    trap 'rm -f "$ticket"' EXIT
    for _ in $(seq 1 2880); do   # up to 4 h: six agents share one phone at night, and a waiter keeps its place in line
      find "$QUEUE" -type f -mmin +60 -delete 2>/dev/null   # a waiter that died an hour ago
      [[ "$(ps -o ppid= -p $$ | tr -d " ")" == 1 ]] && { echo "waiter orphaned (its caller exited): leaving the queue" >&2; exit 1; }   # never take a lock nobody will use
      touch "$ticket"
      head=$(ls "$QUEUE" | sort | head -1)
      if [[ "$QUEUE/$head" == "$ticket" ]] && mkdir "$LOCK" 2>/dev/null; then
        echo "$2 $(date +%s) ${PHONE_LOCK_PID:-}" > "$LOCK/owner"; rm -f "$ticket"; trap - EXIT; echo "phone locked by $2"; exit 0
      fi
      # A `run` holder records its pid: if that process is gone (killed with -9, so no trap ran), the lock is orphaned.
      pid=$(awk '{print $3}' "$LOCK/owner" 2>/dev/null)
      if [[ -n "$pid" ]] && ! kill -0 "$pid" 2>/dev/null; then
        echo "holder $(awk '{print $1}' "$LOCK/owner") (pid $pid) is gone, breaking its lock"; rm -rf "$LOCK"; continue
      fi
      if [[ -f "$LOCK/owner" ]] && (( $(date +%s) - $(awk '{print $2}' "$LOCK/owner") > 2700 )); then
        echo "stale lock from $(awk '{print $1}' "$LOCK/owner"), breaking it"; rm -rf "$LOCK"; continue
      fi
      sleep 5
    done
    echo "phone still busy after 4 h: $(cat "$LOCK/owner" 2>/dev/null)"; exit 1 ;;
  release) [[ "$(awk '{print $1}' "$LOCK/owner" 2>/dev/null)" == "$2" ]] && rm -rf "$LOCK" && echo "phone released by $2" ;;
  status) cat "$LOCK/owner" 2>/dev/null || echo free; ls "$QUEUE" 2>/dev/null | sed 's/^[0-9]*-/  waiting: /' ;;
  run)
    who=$2; shift 2
    PHONE_LOCK_PID=$$ "$0" acquire "$who" || exit 1
    quiet_side_apps
    # Release on any exit, including being killed (TERM/INT/HUP): a killed job once left a phone locked for 45 min.
    trap 'quiet_side_apps >/dev/null; "$0" release "$who" >/dev/null' EXIT
    trap 'exit 130' INT TERM HUP
    "$@" ;;
  *) sed -n 2,6p "$0"; exit 1 ;;
esac
