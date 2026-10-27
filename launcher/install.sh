#!/usr/bin/env bash
# install.sh: installs the Shelf launcher and gives it the remote's Home button.
# Adapted from dumbtv's launcher/install.sh and scripts/home.sh (MIT, see LICENSE-dumbtv).
#
# Changes it makes on the Fire TV (all undone by --undo):
#   1. installs build/shelf.apk                       (adb install)
#   2. allows it to run in the background             (dumpsys deviceidle whitelist)
#   3. makes alarms fire immediately: min_futurity=0  (so Home switches with no 5 s delay;
#      the old value is saved on the TV in settings global shelf_min_futurity)
#   4. turns on the "Shelf home button" accessibility service, keeping any others you have
# It never disables anything. Amazon's launcher stays installed and enabled.
#
# Fire OS 6 instead lets Shelf watch for Amazon's home through usage access (appops).
#
# Usage:  ./install.sh            build (if needed), install and take over Home
#         ./install.sh --undo     hand Home back to Amazon's launcher (Shelf stays installed)
#         ./install.sh --dry-run  print the plan, change nothing
set -euo pipefail
cd "$(dirname "$0")"
source ../common.sh

PKG=tv.shelf.home
GUARD=$PKG/$PKG.Guard
APK=build/shelf.apk
MODE=install
case "${1:-}" in
  "") ;;
  --undo) MODE=undo ;;
  --dry-run) MODE=dry ;;
  *) die "unknown option: $1" ;;
esac

if [ "$MODE" = dry ]; then
  echo "Would: install $APK, whitelist $PKG from battery optimisation,"
  echo "       set alarm min_futurity=0 (saving the old value), enable accessibility service $GUARD."
  echo "Undo:  ./install.sh --undo"
  exit 0
fi

need_device
sh_() { adb -s "$SERIAL" shell "$@" </dev/null | tr -d '\r'; }
sdk=$(sh_ getprop ro.build.version.sdk)
mkdir -p "$BACKUPS"
LOG="$BACKUPS/launcher.txt"

# --- min_futurity: Android 11 and older keep it in settings global alarm_manager_constants,
# Android 12+ in device_config alarm_manager.
get_futurity() {
  if [ "$sdk" -ge 31 ]; then
    sh_ device_config get alarm_manager min_futurity | sed 's/^null$//'
  else
    sh_ settings get global alarm_manager_constants | sed 's/^null$//' |
      tr ',' '\n' | sed -n 's/^min_futurity=//p' | head -1
  fi
}
put_futurity() {   # $1 = value, or empty to remove ours
  local cur
  if [ "$sdk" -ge 31 ]; then
    if [ -n "$1" ]; then sh_ device_config put alarm_manager min_futurity "$1" >/dev/null
    else sh_ device_config delete alarm_manager min_futurity >/dev/null; fi
    return
  fi
  cur=$(sh_ settings get global alarm_manager_constants | sed 's/^null$//' |
        tr ',' '\n' | { grep -v '^min_futurity=' || true; } | grep . | paste -sd, - || true)
  [ -n "$1" ] && cur=${cur:+$cur,}min_futurity=$1
  if [ -n "$cur" ]; then sh_ settings put global alarm_manager_constants "$cur" >/dev/null
  else sh_ settings delete global alarm_manager_constants >/dev/null; fi
}

# --- accessibility services: a colon-separated list; ours is added or removed, others kept.
services_without_ours() {
  sh_ settings get secure enabled_accessibility_services | sed 's/^null$//' |
    tr ':' '\n' | { grep -vxF -e "$GUARD" -e "$PKG/.Guard" || true; } | grep . | paste -sd: - || true
}

# True when the current user's *bound* services include ours. Adapted from dumbtv's
# scripts/accessibility.sh: a label in the Enabled or Binding lists does not count.
guard_bound() {
  awk -v wanted="$1" '
    /User state\[/ { user = ""; inside = 0 }
    /attributes:\{id=[0-9]+[,}]/ { id = $0; sub(/^.*attributes:\{id=/, "", id); sub(/[,}].*$/, "", id); user = id }
    user == wanted && /^[[:space:]]*(Bound services|services):[[:space:]]*\{/ { inside = 1; sub(/^[^{]*\{/, "") }
    inside {
      if (index($0, "Service[label=Shelf home button,")) found = 1
      if (index($0, "Service[label=tv.shelf.home.Guard,")) found = 1
      if (index($0, "}")) inside = 0
    }
    END { exit (found ? 0 : 1) }
  '
}

if [ "$MODE" = undo ]; then
  echo "Handing Home back to Amazon's launcher..."
  others=$(services_without_ours)
  if [ -n "$others" ]; then
    sh_ settings put secure enabled_accessibility_services "$others"
  else
    sh_ settings put secure enabled_accessibility_services '""'
    sh_ settings put secure accessibility_enabled 0
  fi
  saved=$(sh_ settings get global shelf_min_futurity | sed 's/^null$//')
  put_futurity "$saved"
  [ -n "$saved" ] && sh_ settings delete global shelf_min_futurity >/dev/null
  sh_ dumpsys deviceidle whitelist -$PKG >/dev/null || true
  [ "$sdk" -lt 28 ] && sh_ appops set $PKG GET_USAGE_STATS default
  sh_ am force-stop $PKG
  sh_ am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
  echo "$(date '+%Y-%m-%d %H:%M')	undo" >> "$LOG"
  echo "Done. Amazon's home is back. To remove Shelf completely:  adb -s $SERIAL uninstall $PKG"
  exit 0
fi

[ -f "$APK" ] || ./build.sh
echo "1/4 Installing $APK..."
out=$(adb -s "$SERIAL" install -r "$APK" 2>&1 | tr -d '\r')
grep -q Success <<<"$out" || die "adb install failed: $(tail -1 <<<"$out")"

echo "2/4 Allowing Shelf to run in the background..."
sh_ dumpsys deviceidle whitelist +$PKG >/dev/null

if [ "$sdk" -lt 28 ]; then
  # Fire OS 6 refuses third-party accessibility services; Shelf's Keeper uses usage access.
  echo "3/4 Fire OS 6: granting usage access so Shelf can return after Amazon's home opens..."
  sh_ appops set $PKG GET_USAGE_STATS allow
  echo "4/4 (no accessibility service on Fire OS 6)"
else
  echo "3/4 Making Home switch instantly (min_futurity=0)..."
  old=$(get_futurity)
  if [ -n "$old" ] && [ "$old" != 0 ] && [ "$(sh_ settings get global shelf_min_futurity)" = null ]; then
    sh_ settings put global shelf_min_futurity "$old"
  fi
  put_futurity 0

  echo "4/4 Turning on the 'Shelf home button' accessibility service..."
  others=$(services_without_ours)
  echo "$(date '+%Y-%m-%d %H:%M')	accessibility before: ${others:-none}" >> "$LOG"
  sh_ settings put secure enabled_accessibility_services "${others:+$others:}$GUARD"
  sh_ settings put secure accessibility_enabled 1

  # Fire OS decides whether the service actually runs; wait for it to be bound.
  bound=0
  for _ in $(seq 1 15); do
    if sh_ dumpsys accessibility | guard_bound "$(sh_ am get-current-user)"; then bound=1; break; fi
    sleep 1
  done
  if [ "$bound" = 0 ]; then
    echo "Fire OS did not start the Shelf home button service. Undoing steps 3 and 4." >&2
    "$0" --undo
    exit 1
  fi
fi

echo "$(date '+%Y-%m-%d %H:%M')	installed" >> "$LOG"
sh_ input keyevent KEYCODE_WAKEUP >/dev/null || true
sh_ am start -n $PKG/.Home >/dev/null
echo
echo "Shelf is installed. Press Home on the remote: Shelf should appear."
echo "Undo any time:  ./launcher/install.sh --undo"
