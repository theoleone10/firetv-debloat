#!/usr/bin/env bash
# restore.sh: switches packages back on with `pm enable --user 0 <package>`.
# It only touches packages listed in backups/disabled.txt (what debloat.sh disabled).
# Each restored package is removed from disabled.txt and noted in backups/restored.txt.
#
# Usage:  ./restore.sh                 re-enable everything in backups/disabled.txt
#         ./restore.sh <package>       re-enable one package
#         ./restore.sh --batch N       re-enable every package from batch N
#         add --dry-run to any of these to print the commands without running them
set -euo pipefail
cd "$(dirname "$0")"
source ./common.sh

DRY_RUN=0
BATCH=""
PKG=""
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY_RUN=1 ;;
    --batch)   shift; BATCH=${1:-}; [ -n "$BATCH" ] || die "--batch needs a number" ;;
    -*)        die "unknown option: $1" ;;
    *)         PKG=$1 ;;
  esac
  shift
done

LOG="$BACKUPS/disabled.txt"
[ -s "$LOG" ] || die "$LOG is empty or missing, so there is nothing to restore"

# Pick the packages to restore from the log (column 1 = package, column 2 = batch).
if [ -n "$PKG" ]; then
  awk -F'\t' -v p="$PKG" '$1 == p {found=1} END {exit !found}' "$LOG" ||
    die "$PKG is not in $LOG, so this script did not disable it. Leaving it alone."
  targets=$PKG
elif [ -n "$BATCH" ]; then
  targets=$(awk -F'\t' -v b="$BATCH" '$2 == b {print $1}' "$LOG")
  [ -n "$targets" ] || die "no packages from batch $BATCH in $LOG"
else
  targets=$(awk -F'\t' 'NF {print $1}' "$LOG")
fi

if [ "$DRY_RUN" = 1 ]; then
  SERIAL="${FIRETV_IP:-<firetv-ip>}:5555"
  echo "Dry run: these commands would be run, nothing is changed."
  for p in $targets; do echo "  adb -s $SERIAL shell pm enable --user 0 $p"; done
  echo "$(printf '%s\n' $targets | grep -c .) package(s)."
  exit 0
fi

need_device
for p in $targets; do
  out=$(adb -s "$SERIAL" shell pm enable --user 0 "$p" 2>&1 | tr -d '\r') || true
  if grep -q "new state: enabled" <<<"$out"; then
    echo "  enabled  $p"
    # Move the entry from disabled.txt to restored.txt.
    grep -F -m1 "$p	" "$LOG" | sed "s/\$/	restored $(date '+%Y-%m-%d %H:%M')/" >> "$BACKUPS/restored.txt"
    awk -F'\t' -v p="$p" '$1 != p' "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
  else
    echo "  FAILED   $p"
    echo "           $out"
    die "stopped. Packages after $p were not attempted."
  fi
done

echo
echo "Done. Restart the TV so everything starts up fresh:"
echo "  adb -s $SERIAL reboot"
