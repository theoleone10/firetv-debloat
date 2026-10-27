#!/usr/bin/env bash
# preflight.sh: READ-ONLY. Changes nothing on the Fire TV.
#
# Prints the model, Fire OS and Android version, plus the enabled and disabled
# package lists, and saves all of it to a file in backups/.
#
# Usage:  ./preflight.sh             saves backups/before.txt
#         ./preflight.sh after.txt   saves backups/after.txt (run this at the end)
set -euo pipefail
cd "$(dirname "$0")"
source ./common.sh

OUT="$BACKUPS/${1:-before.txt}"

need_device
mkdir -p "$BACKUPS"
# Never overwrite a snapshot: before.txt is the record of how the TV started.
[ ! -e "$OUT" ] || die "$OUT already exists and will not be overwritten. Move it away or pass another file name."

echo "Reading the Fire TV (read-only)..."
sdk=$(dev getprop ro.build.version.sdk)
enabled=$(dev pm list packages -e | sed 's/^package://' | sort)
disabled=$(dev pm list packages -d | sed 's/^package://' | sort)

{
  echo "# Fire TV preflight, $(date '+%Y-%m-%d %H:%M')"
  echo "model:           $(dev getprop ro.product.model) ($(dev getprop ro.product.manufacturer), $(dev getprop ro.product.device))"
  echo "fire os version: $(dev getprop ro.build.version.name)"
  echo "fire os family:  $(fireos_family "$sdk")"
  echo "build:           $(dev getprop ro.build.display.id)"
  echo "android version: $(dev getprop ro.build.version.release) (sdk $sdk)"
  echo
  echo "## enabled packages (pm list packages -e): $(printf '%s\n' "$enabled" | grep -c .)"
  printf '%s\n' "$enabled"
  echo
  echo "## disabled packages (pm list packages -d): $(printf '%s\n' "$disabled" | grep -c . || true)"
  printf '%s\n' "$disabled"
} > "$OUT"

# Show the header lines and counts; the full lists are in the file.
grep -E '^(#|[a-z ]+:)' "$OUT"
echo
echo "Saved to $OUT"
