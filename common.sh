# common.sh: shared helpers, sourced by the other scripts. Does nothing on its own.

# Where logs and snapshots go. Can be overridden (restore.sh's dry-run test uses this).
BACKUPS="${BACKUPS:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/backups}"

die() { echo "ERROR: $*" >&2; exit 1; }

# The Fire TV's address. Set it once per terminal:  export FIRETV_IP=192.168.1.50
need_ip() {
  [ -n "${FIRETV_IP:-}" ] || die "set FIRETV_IP first, e.g.  export FIRETV_IP=192.168.1.50"
  SERIAL="$FIRETV_IP:5555"
}

# Run a shell command on the Fire TV, stripping the \r that adb adds to each line.
dev() { adb -s "$SERIAL" shell "$@" | tr -d '\r'; }

# Connect and make sure the TV has authorised this computer.
need_device() {
  command -v adb >/dev/null || die "adb is not installed (see README.md)"
  need_ip
  adb connect "$SERIAL" >/dev/null 2>&1 || true
  local state
  state=$(adb -s "$SERIAL" get-state 2>&1 || true)
  case "$state" in
    device) ;;
    *unauthorized*) die "the Fire TV has not authorised this computer yet. Accept the 'Allow USB debugging?' prompt on the TV (tick 'Always allow'), then run this again." ;;
    *) die "cannot reach $SERIAL ($state). Is the TV on, on the same Wi-Fi, with ADB debugging enabled?" ;;
  esac
}

# Fire OS family from the Android SDK level.
fireos_family() {
  case "$1" in
    30) echo "Fire OS 8 (Android 11)" ;;
    28) echo "Fire OS 7 (Android 9)" ;;
    25) echo "Fire OS 6 (Android 7.1)" ;;
    22) echo "Fire OS 5 (Android 5.1), pm disable-user does not work here" ;;
    *)  echo "unknown (sdk $1)" ;;
  esac
}

# A changing ID that tells us the TV really restarted.
boot_id() { dev cat /proc/sys/kernel/random/boot_id 2>/dev/null || true; }

# Wait until the TV has rebooted (new boot ID) and finished booting.
wait_for_boot() {
  local before=$1 i
  echo "Waiting for the Fire TV to restart (usually 1-2 minutes)..."
  sleep 20
  for i in $(seq 1 48); do
    adb connect "$SERIAL" >/dev/null 2>&1 || true
    if [ "$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] &&
       [ "$(boot_id)" != "$before" ]; then
      echo "The Fire TV is back up."
      return 0
    fi
    sleep 5
  done
  die "the Fire TV did not come back within about 4 minutes. Check the TV, then run: adb connect $SERIAL"
}
