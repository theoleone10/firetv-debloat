#!/usr/bin/env bash
# debloat.sh: disables the packages listed in packages.txt, at most 10 at a time.
#
# For each batch it:
#   1. shows the packages and what each one is, and asks before doing anything
#   2. runs `pm disable-user --user 0 <package>` on each (the only change it makes)
#   3. logs each disabled package to backups/disabled.txt (restore.sh reads this)
#   4. reboots the Fire TV and waits for it to come back
#   5. asks you to confirm the TV still works before moving on
#
# Run it again to pick up where it left off. Packages that are already disabled
# are skipped and are NOT logged, so restore.sh never switches on anything that
# was off before you started.
#
# Usage:  ./debloat.sh             run the next batch (asks first)
#         ./debloat.sh --dry-run   show the remaining batches, change nothing
#         ./debloat.sh --yes       skip the "disable these?" question
#                                  (it still asks whether the TV works)
set -euo pipefail
cd "$(dirname "$0")"
source ./common.sh

BATCH_SIZE=10
DRY_RUN=0
YES=0
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    --yes|-y)  YES=1 ;;
    *) die "unknown option: $arg" ;;
  esac
done

# Second line of defence: a package whose name contains one of these words is
# refused, even if it is in packages.txt. This covers the launcher, settings,
# Wi-Fi/networking, Bluetooth/remote, input, updates and core system services.
DENY_WORDS="launcher home settings wifi wlan network connectivity bluetooth blepa
pair input ime keyboard keypolicy remote devicecontrol systemui packageinstaller
permissioncontroller providers webview ota update upgrade oobe venezia vizzini
firebat messaging whisper android.phone android.shell"

[ -f packages.txt ] || die "packages.txt is missing"
need_device
[ -f "$BACKUPS/before.txt" ] || die "run ./preflight.sh first, so backups/before.txt exists"
LOG="$BACKUPS/disabled.txt"
touch "$LOG"

# Packages from packages.txt (one per line, "# ..." is the explanation).
list_packages() { sed 's/#.*//' packages.txt | awk 'NF {print $1}'; }
explain() { awk -v p="$1" '$1 == p { sub(/^[^#]*#[ ]*/, ""); print; exit }' packages.txt; }

# Prints why a package must not be disabled, or nothing if it may be.
blocked_reason() {
  local p=$1 w
  if sed 's/#.*//' never-disable.txt | awk 'NF {print $1}' | grep -qxF "$p"; then
    echo "on never-disable.txt"; return
  fi
  for w in $DENY_WORDS; do
    case "$p" in *"$w"*) echo "name contains '$w'"; return ;; esac
  done
}

# Sets TODO to the packages that are still to be disabled, and prints the rest.
work_out_todo() {
  local installed off p reason
  installed=$(dev pm list packages | sed 's/^package://')
  off=$(dev pm list packages -d | sed 's/^package://')
  TODO=""
  for p in $(list_packages); do
    reason=$(blocked_reason "$p")
    if [ -n "$reason" ]; then
      echo "  REFUSED  $p ($reason)"
    elif ! grep -qxF "$p" <<<"$installed"; then
      echo "  absent   $p (not on this Fire TV)"
    elif grep -qxF "$p" <<<"$off"; then
      echo "  off      $p (already disabled)"
    else
      TODO="$TODO $p"
    fi
  done
}

next_batch_number() {
  local last
  last=$(awk -F'\t' '{print $2}' "$LOG" | sort -n | tail -1)
  echo $(( ${last:-0} + 1 ))
}

if [ "$DRY_RUN" = 1 ]; then
  echo "Dry run: nothing will be changed."
  work_out_todo
  n=$(next_batch_number); i=0
  for p in $TODO; do
    if [ $((i % BATCH_SIZE)) = 0 ]; then echo; echo "Batch $n would disable:"; n=$((n + 1)); fi
    printf '  %-45s %s\n' "$p" "$(explain "$p")"
    i=$((i + 1))
  done
  [ -n "$TODO" ] || echo "Nothing left to disable."
  exit 0
fi

while true; do
  work_out_todo
  [ -n "$TODO" ] || { echo "Nothing left to disable. All done."; exit 0; }

  batch=$(next_batch_number)
  this_batch=$(printf '%s\n' $TODO | head -n "$BATCH_SIZE")
  count=$(printf '%s\n' $this_batch | grep -c .)

  echo
  echo "Batch $batch will disable $count package(s):"
  for p in $this_batch; do printf '  %-45s %s\n' "$p" "$(explain "$p")"; done
  if [ "$YES" = 0 ]; then
    read -r -p "Disable these? [y/N] " ans || ans=""
    [ "$ans" = y ] || { echo "Cancelled. Nothing was changed."; exit 0; }
  fi

  for p in $this_batch; do
    out=$(adb -s "$SERIAL" shell pm disable-user --user 0 "$p" 2>&1 | tr -d '\r') || true
    if grep -q "new state: disabled-user" <<<"$out"; then
      printf '%s\t%s\t%s\n' "$p" "$batch" "$(date '+%Y-%m-%d %H:%M')" >> "$LOG"
      echo "  disabled  $p"
    else
      echo "  FAILED    $p"
      echo "            $out"
      echo
      echo "Stopped. The rest of this batch was not attempted and the TV was not rebooted."
      echo "Packages disabled so far are in $LOG."
      echo "To undo this batch:  ./restore.sh --batch $batch"
      exit 1
    fi
  done

  echo "Batch $batch logged to $LOG. Rebooting the Fire TV..."
  before=$(boot_id)
  adb -s "$SERIAL" reboot >/dev/null 2>&1 || true   # the connection drops as it reboots
  wait_for_boot "$before"

  echo
  echo "Please check the TV now:"
  echo "  - the home screen loads and the Home button works"
  echo "  - the remote responds (and the voice button, if you use it)"
  echo "  - Wi-Fi is connected and an app plays video"
  echo "  - Settings opens"
  if [ -t 0 ]; then
    read -r -p "Does everything still work? [y/N] " ans || ans=""
    if [ "$ans" != y ]; then
      echo "Undo this batch with:  ./restore.sh --batch $batch"
      echo "Then narrow it down with ./restore.sh <package>, one package at a time."
      exit 0
    fi
  else
    echo "Not running in an interactive terminal, so stopping here."
    echo "If the TV works, run ./debloat.sh again for the next batch."
    echo "If not:  ./restore.sh --batch $batch"
    exit 0
  fi
done
