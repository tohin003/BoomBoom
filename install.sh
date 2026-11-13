#!/bin/bash
# Installs BoomBoom on your iPhone from your Mac. The whole guide is in INSTALL.md.
#
#   bash -c "$(curl -fsSL https://raw.githubusercontent.com/tohin003/BoomBoom/main/install.sh)"
#
# Run the same line again any time to get the newest version. It is safe to run as often as you like.
#
# What it does: checks that Xcode is ready and signed in with your Apple ID, finds your iPhone, builds the app with
# your own (free) signing, puts it on the phone, and adds a small menu-bar item that renews the app's 7-day signing
# by itself. Nothing is sent anywhere: your Apple ID stays inside Xcode, and this script never sees your password.
#
#   install.sh --check    only look at what is ready and what is missing; build and install nothing
set -u

REPOSITORY="tohin003/BoomBoom"
BRANCH="main"
HOME_FOLDER="$HOME/BoomBoom"
export PATH="/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin"

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
say() { printf '%s\n' "$*"; }
step() { printf '\n\033[1;33m%s\033[0m\n' "$*"; }
stop() {
  printf '\n\033[1m%s\033[0m\n' "$1"
  shift
  for line in "$@"; do printf '  %s\n' "$line"; done
  printf '\nWhen that is done, run the same line again.\n'
  exit 1
}

check_only=0
[ "${1:-}" = "--check" ] && check_only=1

# ---- 0. The app's files ------------------------------------------------------------------------------------------
# Run from a copy of the project, this script uses that copy. Run straight from the internet, it fetches the project
# into ~/BoomBoom first (keeping your signing settings from an earlier install) and carries on from there.
here="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" 2>/dev/null && pwd)"
if [ ! -f "$here/scripts/renew.sh" ] || [ ! -d "$here/Packages" ]; then
  step "Getting BoomBoom..."
  work="$(mktemp -d)" || stop "Could not make a temporary folder." "Check that your Mac's disk is not full."
  if ! curl -fsSL "https://github.com/$REPOSITORY/archive/refs/heads/$BRANCH.zip" -o "$work/boomboom.zip"; then
    rm -rf "$work"
    stop "Could not download BoomBoom." "Check your internet connection."
  fi
  if ! /usr/bin/unzip -q "$work/boomboom.zip" -d "$work"; then
    rm -rf "$work"
    stop "The download did not unpack." "Try again in a minute."
  fi
  unpacked="$(/usr/bin/find "$work" -mindepth 1 -maxdepth 1 -type d | /usr/bin/head -n 1)"
  mkdir -p "$HOME_FOLDER"
  # Everything is replaced by the new version except this Mac's own signing settings and build folders.
  /usr/bin/rsync -a --delete --exclude 'Config/Local.xcconfig' --exclude 'build/' --exclude '.build/' \
    "$unpacked/" "$HOME_FOLDER/" || { rm -rf "$work"; stop "Could not copy BoomBoom into $HOME_FOLDER."; }
  rm -rf "$work"
  args=()
  [ "$check_only" -eq 1 ] && args+=(--check)
  exec /bin/bash "$HOME_FOLDER/install.sh" ${args[@]+"${args[@]}"}
fi
cd "$here" || exit 1

bold "BoomBoom installer"
say "This takes 5 to 15 minutes the first time (most of it is your Mac building the app)."

# ---- 1. Xcode ----------------------------------------------------------------------------------------------------
step "1 of 4  Xcode"
if [ ! -d /Applications/Xcode.app ]; then
  open "macappstore://apps.apple.com/app/xcode/id497799835" 2>/dev/null
  stop "Xcode is not installed yet." \
    "The App Store has just opened on Xcode: click Get, then Install. It is free and large, so it takes a while." \
    "When it has finished, open Xcode once. When it asks which platforms to add, tick iOS and let it finish."
fi
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
if ! xcodebuild -checkFirstLaunchStatus >/dev/null 2>&1 || ! xcodebuild -license check >/dev/null 2>&1; then
  open -a Xcode 2>/dev/null
  stop "Xcode has not finished setting itself up." \
    "Xcode has just opened. Agree to its licence if it asks, and let it install its components." \
    "When it asks which platforms to add, tick iOS."
fi
say "Xcode is ready ($(xcodebuild -version 2>/dev/null | /usr/bin/head -n 1))."

# ---- 2. Your Apple ID in Xcode -----------------------------------------------------------------------------------
step "2 of 4  Your Apple ID"
# Xcode keeps the teams of the Apple IDs signed in to it in its settings. A free Apple ID has one "Personal Team".
team_line="$(defaults export com.apple.dt.Xcode - 2>/dev/null | /usr/bin/python3 -I -c '
import plistlib, sys
try:
    settings = plistlib.loads(sys.stdin.buffer.read())
except Exception:
    sys.exit(0)
teams = []
for key in ("IDEProvisioningTeamByIdentifier", "IDEProvisioningTeams"):
    for entries in (settings.get(key) or {}).values():
        for team in entries or []:
            if isinstance(team, dict) and team.get("teamID"):
                teams.append(team)
# A Personal Team (free) first, as that is what this guide is about; otherwise whatever there is.
teams.sort(key=lambda team: 0 if team.get("isFreeProvisioningTeam") or "ersonal" in str(team.get("teamName", "")) else 1)
if teams:
    print(teams[0]["teamID"], str(teams[0].get("teamName", "your team")).replace("\n", " "))
' 2>/dev/null)"
local_config="Config/Local.xcconfig"
saved_team=""
[ -f "$local_config" ] && saved_team="$(/usr/bin/awk -F'= *' '/^DEVELOPMENT_TEAM/ { print $2 }' "$local_config" | /usr/bin/tr -d ' \r')"
team="${BOOMBOOM_TEAM:-${team_line%% *}}"
[ -z "$team" ] && team="$saved_team"
if [ -z "$team" ]; then
  open -a Xcode 2>/dev/null
  stop "Xcode is not signed in with an Apple ID yet." \
    "Xcode has just opened. In the menu bar choose Xcode > Settings, then the Accounts tab." \
    "Click the + at the bottom left, choose Apple Account, and sign in with your Apple ID (your normal one is fine, it is free)." \
    "You should then see a line called Personal Team on the right."
fi
say "Signed in: ${team_line#* }"

# ---- 3. Your iPhone ----------------------------------------------------------------------------------------------
step "3 of 4  Your iPhone"
devices="$(mktemp)"
xcrun devicectl list devices --quiet --timeout 30 --json-output "$devices" >/dev/null 2>&1
phone="$(/usr/bin/python3 -I - "$devices" <<'PY' 2>/dev/null
import json, sys
try:
    devices = json.load(open(sys.argv[1]))["result"]["devices"]
except Exception:
    devices = []
phones = []
for device in devices:
    hardware = device.get("hardwareProperties", {})
    if hardware.get("reality") != "physical" or hardware.get("deviceType") != "iPhone":
        continue
    connection = device.get("connectionProperties", {})
    properties = device.get("deviceProperties", {})
    reachable = connection.get("tunnelState") in ("connected", "disconnected") and connection.get("pairingState") == "paired"
    phones.append((reachable, connection.get("tunnelState") == "connected", properties.get("developerModeStatus"), properties.get("name", "iPhone")))
if not phones:
    print("NONE")
else:
    phones.sort(reverse=True)
    reachable, connected, developer, name = phones[0]
    print("UNPAIRED" if not reachable else ("NODEVMODE" if developer not in (None, "enabled") else "OK"))
PY
)"
rm -f "$devices"
case "$phone" in
  OK) say "Your iPhone is connected and ready." ;;
  NODEVMODE)
    stop "Developer Mode is off on your iPhone." \
      "On the iPhone open Settings > Privacy & Security, scroll to the bottom and tap Developer Mode. Turn it on." \
      "The iPhone restarts. After it comes back, unlock it and tap Turn On when it asks." ;;
  UNPAIRED)
    stop "Your Mac can see an iPhone but is not allowed to talk to it yet." \
      "Unlock the iPhone and connect it to the Mac with its cable. When the iPhone asks 'Trust This Computer?', tap Trust and enter your passcode." ;;
  *)
    stop "No iPhone found." \
      "Unlock your iPhone and connect it to the Mac with its cable." \
      "When the iPhone asks 'Trust This Computer?', tap Trust and enter your passcode." \
      "Then on the iPhone: Settings > Privacy & Security > Developer Mode (at the very bottom) > turn it on. The iPhone restarts; unlock it and tap Turn On." ;;
esac

if [ "$check_only" -eq 1 ]; then
  step "Everything needed is in place. Run without --check to install."
  exit 0
fi

# ---- 4. Build and install ----------------------------------------------------------------------------------------
step "4 of 4  Building BoomBoom and putting it on your iPhone"
if [ ! -f "$local_config" ]; then
  # An app identifier of your own: two people cannot sign the same identifier.
  identifier="io.github.boomboom.t$(printf '%s' "$team" | /usr/bin/tr '[:upper:]' '[:lower:]')"
  printf '// This Mac%ss signing values, written by install.sh. Not shared with anyone.\nDEVELOPMENT_TEAM = %s\nBOOM_BUNDLE_ID = %s\n' \
    "'" "$team" "$identifier" > "$local_config"
fi
say "Keep the iPhone unlocked and near the Mac. This is the long part; the Mac may get warm."

install_app() { /bin/bash scripts/renew.sh --from-tree; }
install_app
result=$?
build_log="$HOME/Library/Application Support/BoomBoomRenewer/build.log"
if [ "$result" -eq 76 ] && /usr/bin/grep -Eqi 'platform.*(is )?not installed|iOS [0-9.]+ is not installed' "$build_log" 2>/dev/null; then
  say ""
  say "Xcode still needs its iPhone support files. Downloading them now (several GB, this can take a long time)..."
  if xcodebuild -downloadPlatform iOS; then
    install_app
    result=$?
  fi
fi
case "$result" in
  0) ;;
  69) stop "The iPhone went away during the install." "Unlock it, keep it connected by cable, and leave it unlocked until the install has finished." ;;
  74) open -a Xcode 2>/dev/null
      stop "Xcode could not sign the app with your Apple ID." \
        "Xcode has just opened. Choose Xcode > Settings > Accounts and check that your Apple ID is there and shows no warning." \
        "If it asks you to sign in again or to agree to something, do that." ;;
  77) stop "The app was built but the iPhone did not take it." \
        "Unlock the iPhone and keep it unlocked." \
        "Check that Developer Mode is on: Settings > Privacy & Security > Developer Mode." \
        "If you already have three apps installed this way, remove one: a free Apple ID allows three." ;;
  78) stop "The build took too long and was stopped." "Close other apps on the Mac to give it room, then try again; the second try carries on where this one stopped." ;;
  *) stop "The build did not finish (code $result)." \
       "The details are in: $build_log" \
       "Send that file to whoever gave you BoomBoom." ;;
esac

# The menu-bar item that renews the signing every week by itself.
say ""
say "Adding the menu-bar item that keeps BoomBoom signed..."
if swift build --package-path Renewer -c release --product BoomBoomRenewer >/dev/null 2>&1 && /bin/bash Renewer/install.sh >/dev/null 2>&1; then
  say "Done. Look for BoomBoom's days-left counter in the menu bar at the top of the screen."
else
  say "The menu-bar item could not be added. BoomBoom still works; run this installer again within 7 days to renew it."
fi

step "BoomBoom is on your iPhone."
say "One last thing, on the iPhone, the first time only:"
say "  Settings > General > VPN & Device Management > tap your Apple ID under Developer App > Trust."
say "Then open BoomBoom."
say ""
say "The app is signed for 7 days at a time. The menu-bar item renews it by itself whenever the iPhone is near the Mac"
say "(same Wi-Fi or cable) and unlocked now and then. To update BoomBoom, run the same line again."
