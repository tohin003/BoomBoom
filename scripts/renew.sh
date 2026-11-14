#!/bin/bash
# Build BoomBoom for the iPhone, sign it with the Personal Team and install it. A free Apple ID's signing lasts 7 days;
# building again with -allowProvisioningUpdates renews it, and installing over the old app keeps its data.
#
#   scripts/renew.sh              rebuild the snapshot last installed on purpose and install it (what the menu-bar
#                                 renewer, Renewer/, runs). The snapshot's build folder is kept, so builds are incremental.
#   scripts/renew.sh --from-tree  copy this working tree into the snapshot, build and install from it. Use it for the
#                                 first install and whenever the iPhone should get new code. It works without git.
#   scripts/renew.sh --device ID  install on this devicectl identifier (default: the iPhone last installed on; the
#                                 first time, the one connected iPhone)
#   scripts/renew.sh --time-limit SECONDS   stop after this long (default 2100)
#   scripts/renew.sh --dry-run    print the steps; copy, build and install nothing
#
# So the iPhone only ever gets what was last installed with --from-tree: half-finished edits in the working tree never
# reach it through a renewal.
#
# Needs Xcode signed in with the person's Apple ID, XcodeGen (/opt/homebrew/bin/xcodegen), and the iPhone paired with
# this Mac (cable or the same Wi-Fi, Developer Mode on). Xcode is chosen through DEVELOPER_DIR for this run only (never
# the global xcode-select); builds run at nice 19 with two jobs.
#
# After every successful install it records an owner-only state file, state.json, in
# ~/Library/Application Support/BoomBoomRenewer/ (BOOMBOOM_RENEWER_DIR moves the whole folder): version, installedAt,
# snapshot (first 12 hex digits of a hash over the snapshot's files), snapshotAt, builtFrom ("tree" or "snapshot"),
# device, bundleId and profileCreated/profileExpires. Nothing else of the signing profile is kept.
#
# The last line of a successful run's script output reads:
#   renew.sh: installed. This build expires Fri 06 Nov 2026, 02:07 (in 5.0 days). Run scripts/renew.sh again before then.
#
# Exit codes (Renewer/Sources/RenewerCore/RenewScript.swift reads exactly these):
#   0 done
#   1 built, but the app or its end date was not found
#   64 usage
#   66 no project: nothing installed yet (use --from-tree), or the saved copy has no project.yml
#   69 iPhone not reachable, not paired, or several to choose from
#   71 Xcode is not installed
#   73 snapshot failed: the working tree could not be copied
#   74 signing failed: Xcode is not signed in, or has no profile
#   75 busy: another renewal is running
#   76 build failed
#   77 install failed (the iPhone was reached)
#   78 time limit reached
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
XCODE_DEV=/Applications/Xcode.app/Contents/Developer
export PATH="/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin"
RENEWER_DIR="${BOOMBOOM_RENEWER_DIR:-$HOME/Library/Application Support/BoomBoomRenewer}"
STATE="$RENEWER_DIR/state.json"
SNAPSHOT="$RENEWER_DIR/snapshot"
PREVIOUS="$RENEWER_DIR/snapshot.prev"
LOCK="$RENEWER_DIR/renew.lock"
BUILD_LOG="$RENEWER_DIR/build.log"
SNAPSHOT_MARK=".snapshot-id"
# Test doubles for xcrun and xcodegen (tests/renew-contract in RenewerCoreTests); the defaults are the real tools.
XCRUN="${BOOMBOOM_RENEW_XCRUN:-/usr/bin/xcrun}"
XCODEGEN="${BOOMBOOM_RENEW_XCODEGEN:-/opt/homebrew/bin/xcodegen}"
RETRY_WAIT="${BOOMBOOM_RENEW_RETRY_WAIT:-10}"
SCHEME=BoomBoom
PROJECT=BoomBoom.xcodeproj
APP_NAME=BoomBoom.app

from_tree=0
dry=0
device=""
limit=2100
locked=0
restore_needed=0
tmp=""
started=$SECONDS

say() { printf 'renew.sh: %s\n' "$*"; }
die() { local code=$1; shift; say "$*"; exit "$code"; }

usage() { /usr/bin/awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "$0"; }

# Puts the previously installed sources back when a --from-tree run did not get as far as installing.
restore_snapshot() {
  if [ -d "$PREVIOUS" ]; then
    /usr/bin/rsync -a --delete --exclude /build/ --exclude "/$SNAPSHOT_MARK" "$PREVIOUS/" "$SNAPSHOT/" || true
    if [ -f "$PREVIOUS/$SNAPSHOT_MARK" ]; then /bin/cp -p "$PREVIOUS/$SNAPSHOT_MARK" "$SNAPSHOT/$SNAPSHOT_MARK"; fi
    /bin/rm -rf "${PREVIOUS:?}"
  fi
}

cleanup() {
  local status=$?
  trap - EXIT
  /usr/bin/pkill -TERM -P $$ 2>/dev/null || true
  if [ "$restore_needed" -eq 1 ] && [ "$locked" -eq 1 ]; then
    say "putting the previous snapshot back, since this one was not installed."
    restore_snapshot
  fi
  if [ -n "$tmp" ]; then /bin/rm -rf "$tmp"; fi
  if [ "$locked" -eq 1 ]; then /bin/rm -rf "${LOCK:?}"; fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 143' TERM INT HUP

while [ $# -gt 0 ]; do
  case "$1" in
    --from-tree) from_tree=1 ;;
    --dry-run) dry=1 ;;
    --device)
      if [ $# -lt 2 ] || ! [[ "$2" =~ ^[A-Za-z0-9][A-Za-z0-9-]{0,63}$ ]]; then
        die 64 "--device needs a devicectl identifier (letters, digits and hyphens)"
      fi
      device="$2"
      shift
      ;;
    --time-limit)
      if [ $# -lt 2 ] || ! [[ "$2" =~ ^[0-9]{1,6}$ ]] || [ "$2" -lt 1 ]; then die 64 "--time-limit needs a number of seconds"; fi
      limit="$2"
      shift
      ;;
    -h|--help) usage; exit 0 ;;
    *) die 64 "unknown option: $1 (see --help)" ;;
  esac
  shift
done

# Xcode for this run only, never through the global xcode-select.
if [ -z "${DEVELOPER_DIR:-}" ] && [ -d "$XCODE_DEV" ]; then export DEVELOPER_DIR="$XCODE_DEV"; fi

seconds_left() {
  local left=$((limit - (SECONDS - started)))
  if [ "$left" -lt 1 ]; then left=1; fi
  echo "$left"
}
out_of_time() { [ $((SECONDS - started)) -ge "$limit" ]; }

# Runs a command with a time limit: renew.sh's own limit is the last word on a build that hangs. Output goes to the
# file. Returns 124 when the limit stopped it. (A watcher kills the command; Xcode stops its build on SIGTERM.)
run_limited() {
  local secs=$1 output=$2 pid watcher rc=0 mark
  shift 2
  mark="$tmp/timed-out"
  /bin/rm -f "$mark"
  "$@" > "$output" 2>&1 &
  pid=$!
  ( /bin/sleep "$secs" && { : > "$mark"; kill -TERM "$pid" 2>/dev/null; /bin/sleep 15; kill -KILL "$pid" 2>/dev/null; } ) \
    > /dev/null 2>&1 &
  watcher=$!
  wait "$pid" || rc=$?
  # Its sleep first (a killed sleep ends the watcher without it killing anything), then the watcher itself.
  /usr/bin/pkill -TERM -P "$watcher" 2>/dev/null || true
  kill "$watcher" 2>/dev/null || true
  { wait "$watcher"; } 2>/dev/null || true
  if [ -e "$mark" ]; then return 124; fi
  return "$rc"
}

# One renewal at a time (the menu-bar renewer and a run by hand could meet in the snapshot).
take_lock() {
  local holder
  /bin/mkdir -p "$RENEWER_DIR"
  /bin/chmod 700 "$RENEWER_DIR"
  until /bin/mkdir "$LOCK" 2>/dev/null; do
    holder=$(/bin/cat "$LOCK/pid" 2>/dev/null || true)
    if [ -z "$holder" ]; then
      /bin/sleep 2  # its owner may be writing its pid right now
      holder=$(/bin/cat "$LOCK/pid" 2>/dev/null || true)
    fi
    if [ -z "$holder" ] || ! kill -0 "$holder" 2>/dev/null; then
      /bin/rm -rf "${LOCK:?}"
      continue
    fi
    die 75 "another renewal is running (pid $holder); try again when it is done."
  done
  locked=1
  echo $$ > "$LOCK/pid"
}

# state.json's field $1, but only when it is well-formed (a snapshot is 7 to 64 hex digits, a device a plain id).
state_field() {
  /usr/bin/python3 -I - "$STATE" "$1" <<'PY'
import json
import re
import sys

try:
    with open(sys.argv[1]) as handle:
        value = json.load(handle).get(sys.argv[2])
except Exception:
    value = None
patterns = {'snapshot': r'[0-9a-fA-F]{7,64}', 'device': r'[A-Za-z0-9][A-Za-z0-9-]{0,63}',
            'profileExpires': r'[0-9T:Z-]{20}'}
if isinstance(value, str) and re.fullmatch(patterns.get(sys.argv[2], r'.*'), value):
    print(value)
PY
}

# A short identity for the sources in folder $1: a hash over every file's path and bytes, leaving out the build products
# and the generated Xcode project (so the same sources always give the same id, before and after a build).
snapshot_id() {
  /usr/bin/python3 -I - "$1" <<'PY'
import hashlib
import os
import sys

root = sys.argv[1]
skip_dirs = {'build', '.build', 'DerivedData', '.git', '.swiftpm'}
digest = hashlib.sha256()
for folder, names, files in os.walk(root):
    names[:] = sorted(n for n in names if n not in skip_dirs and not n.endswith('.xcodeproj'))
    for name in sorted(files):
        if name in ('.DS_Store', '.snapshot-id'):
            continue
        path = os.path.join(folder, name)
        digest.update(os.path.relpath(path, root).encode() + b'\0')
        if os.path.islink(path):
            digest.update(b'link:' + os.readlink(path).encode())
        else:
            with open(path, 'rb') as handle:
                digest.update(hashlib.sha256(handle.read()).digest())
print(digest.hexdigest()[:12])
PY
}

# The iPhone in a `devicectl list devices --json-output` file, chosen as RenewerCore/Devices.swift chooses it: a real
# iPhone (not a Simulator) that is paired; the one recorded in state.json wins and is never replaced by another;
# otherwise the only one the Mac can reach now. Prints "OK <id>", "UNREACHABLE", "NONE", "MANY <names>" or "UNREADABLE".
pick_iphone() {
  /usr/bin/python3 -I - "$1" "$2" <<'PY'
import json
import re
import sys

path, preferred = sys.argv[1:3]
try:
    with open(path, 'rb') as handle:
        raw = handle.read()
    root = json.loads(raw[raw.index(b'{'):])
    items = root['result']['devices']
    assert isinstance(items, list)
except Exception:
    print('UNREADABLE')
    sys.exit(0)


def text(value):
    if isinstance(value, str):
        return value
    if isinstance(value, dict) and isinstance(value.get('value'), str):
        return value['value']
    return None


def lookup(key, properties, alternates):
    if not isinstance(properties, dict):
        return None
    if text(properties.get(key)) is not None:
        return text(properties.get(key))
    for category in sorted(properties):
        fields = properties[category]
        if isinstance(fields, dict) and text(fields.get(key)) is not None:
            return text(fields.get(key))
    for category, field in alternates:
        fields = properties.get(category)
        if isinstance(fields, dict) and text(fields.get(field)) is not None:
            return text(fields.get(field))
    return None


def value(legacy, key, properties, alternates=()):
    found = text(legacy.get(key)) if isinstance(legacy, dict) else None
    return found if found is not None else lookup(key, properties, alternates)


PLAIN = re.compile(r'[A-Za-z0-9][A-Za-z0-9-]{0,63}')
phones = []
for item in items:
    identifier = item.get('identifier') if isinstance(item, dict) else None
    if not isinstance(identifier, str) or not PLAIN.fullmatch(identifier):
        continue
    props = item.get('properties')
    hardware, details, connection = (item.get(k) for k in ('hardwareProperties', 'deviceProperties',
                                                          'connectionProperties'))
    phone = {
        'id': identifier,
        'name': value(details, 'name', props),
        'udid': value(hardware, 'udid', props),
        'platform': value(hardware, 'platform', props),
        'type': value(hardware, 'deviceType', props),
        'pairing': value(connection, 'pairingState', props, [('pairing', 'state')]),
        'tunnel': value(connection, 'tunnelState', props, [('tunnel', 'state'), ('connection', 'state')]),
        'transport': value(connection, 'transportType', props, [('connection', 'transport')]),
    }
    is_iphone = ((phone['platform'] or '').lower() == 'ios' and phone['type'] in (None, 'iPhone')
                 and phone['transport'] != 'sameMachine')
    if is_iphone and phone['pairing'] == 'paired':
        phones.append(phone)


def reachable(phone):
    if phone['tunnel'] == 'unavailable':
        return False
    if phone['transport'] in ('wired', 'localNetwork'):
        return True
    return phone['tunnel'] == 'connected'


def same(phone, wanted):
    return phone['id'].lower() == wanted.lower() or (phone['udid'] or '').lower() == wanted.lower()


if preferred:
    for phone in phones:
        if same(phone, preferred):
            print(('OK ' if reachable(phone) else 'UNREACHABLE ') + phone['id'])
            sys.exit(0)
usable = [p for p in phones if reachable(p)]
if len(usable) == 1:
    print('OK ' + usable[0]['id'])
elif len(usable) > 1:
    print('MANY ' + ', '.join('%s (%s)' % (p['name'] or 'iPhone', p['id']) for p in usable))
else:
    print('UNREACHABLE' if phones else 'NONE')
PY
}

# The profile inside a built app, by plain file parsing: a profile is a signed envelope around an XML property list that
# is stored as text, so the list is cut out of the file's bytes. No Keychain, no `security` tool. Prints
# "<created> <expires> <bundle id>" (UTC, e.g. 2026-11-13T09:14:00Z), or nothing when it cannot be read. Only these
# three things are taken from it.
profile_facts() {
  /usr/bin/python3 -I - "$1" <<'PY'
import datetime
import plistlib
import re
import sys

try:
    with open(sys.argv[1], 'rb') as handle:
        data = handle.read()
    start = data.index(b'<?xml')
    end = data.index(b'</plist>', start) + len(b'</plist>')
    profile = plistlib.loads(data[start:end])
    app_id = profile['Entitlements']['application-identifier']


    def iso(moment):
        if moment.tzinfo is None:
            moment = moment.replace(tzinfo=datetime.timezone.utc)  # plistlib gives naive UTC dates
        return moment.astimezone(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')


    bundle = app_id.split('.', 1)[1]
    if not re.fullmatch(r'[A-Za-z0-9.-]+', bundle):
        raise ValueError('odd bundle id')
    print(iso(profile['CreationDate']), iso(profile['ExpirationDate']), bundle)
except Exception:
    pass
PY
}

# The end date as the person reads it, in local time, with the days left: "Fri 06 Nov 2026, 02:07 (in 5.0 days)".
# RenewScript.expiry(in:) reads this wording back.
expiry_text() {
  /usr/bin/python3 -I - "$1" <<'PY'
import datetime
import sys

end = datetime.datetime.strptime(sys.argv[1], '%Y-%m-%dT%H:%M:%SZ').replace(tzinfo=datetime.timezone.utc)
days = (end - datetime.datetime.now(datetime.timezone.utc)).total_seconds() / 86400
print(end.astimezone().strftime('%a %d %b %Y, %H:%M') + ' (in %.1f days)' % days)
PY
}

# state.json: what is on the iPhone now. Owner-only folder (0700) and file (0600), replaced in one step.
record_state() {
  /usr/bin/python3 -I - "$STATE" "$1" "$2" "$3" "$4" "$5" "$6" "$7" <<'PY'
import datetime
import json
import os
import sys
import tempfile

state_path, snapshot, snapshot_at, built_from, device, created, expires, bundle = sys.argv[1:9]
now = datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
state = {
    'version': 1,
    'installedAt': now,
    'snapshot': snapshot,
    'snapshotAt': snapshot_at or None,
    'builtFrom': built_from,
    'device': device or None,
    'bundleId': bundle or None,
    'profileCreated': created,
    'profileExpires': expires,
}
folder = os.path.dirname(state_path)
os.makedirs(folder, mode=0o700, exist_ok=True)
os.chmod(folder, 0o700)
handle, temporary = tempfile.mkstemp(dir=folder, prefix='.state-', suffix='.json')  # created 0600
with os.fdopen(handle, 'w') as out:
    json.dump(state, out, indent=2, sort_keys=True)
    out.write('\n')
os.replace(temporary, state_path)
PY
}

# ---------------------------------------------------------------------------------------------------------------------

# 1. Xcode.
xcode_dir="${DEVELOPER_DIR:-$(/usr/bin/xcode-select -p 2>/dev/null || true)}"
if [ ! -x "$xcode_dir/usr/bin/xcodebuild" ]; then
  if [ "$dry" -eq 1 ]; then
    say "Xcode is not installed: a real run would stop here (exit 71)."
  else
    die 71 "Xcode is not installed (no xcodebuild under ${xcode_dir:-DEVELOPER_DIR}). Install it and sign in with your Apple ID."
  fi
fi

# 2. Which sources: the working tree for --from-tree, otherwise the snapshot last installed.
if [ "$from_tree" -eq 1 ]; then
  src="$SNAPSHOT"
  if [ "$(cd "$ROOT" && pwd -P)" = "$(cd "$SNAPSHOT" 2>/dev/null && pwd -P || echo none)" ]; then
    die 64 "run scripts/renew.sh from the BoomBoom repository, not from the snapshot it builds in."
  fi
  if [ ! -f "$ROOT/project.yml" ]; then die 66 "no project.yml in $ROOT, so nothing can be built."; fi
  built_from=tree
else
  src="$SNAPSHOT"
  built_from=snapshot
  if [ -z "$(state_field snapshot)" ]; then
    if [ "$dry" -eq 1 ]; then
      say "nothing is recorded as installed yet: a real run would stop here (exit 66). Use --from-tree the first time."
    else
      die 66 "nothing has been installed yet, so there is no snapshot to renew. Run scripts/renew.sh --from-tree first."
    fi
  elif [ ! -f "$SNAPSHOT/project.yml" ]; then
    if [ "$dry" -eq 1 ]; then
      say "the snapshot in $SNAPSHOT has no project.yml: a real run would stop here (exit 66)."
    else
      die 66 "the snapshot in $SNAPSHOT has no project.yml. Run scripts/renew.sh --from-tree to make a new one."
    fi
  fi
fi

if [ "$dry" -eq 1 ]; then
  if [ "$from_tree" -eq 1 ]; then
    say "would copy the working tree $ROOT to the snapshot $SNAPSHOT (rsync, leaving out build/, .build/, DerivedData, .git)."
  else
    say "would rebuild the snapshot $SNAPSHOT (build folder kept, so the build is incremental)."
  fi
  say "would run: xcodegen generate, in $src"
  if [ -n "$device" ]; then
    say "would install on the iPhone $device."
  else
    say "would choose the iPhone: the one recorded in $STATE, otherwise the only connected one (xcrun devicectl list devices)."
  fi
  say "would run: nice -n 19 xcrun xcodebuild -project $PROJECT -scheme $SCHEME -configuration Release -destination generic/platform=iOS -allowProvisioningUpdates -jobs 2 build"
  say "would run: xcrun devicectl device install app --device <iPhone> <$APP_NAME>"
  say "would read the end date from the app's embedded profile and record it in $STATE."
  exit 0
fi

take_lock
tmp=$(/usr/bin/mktemp -d "$RENEWER_DIR/.run.XXXXXX")

# A --from-tree run that was cut off (a crash, a power cut) left the previous sources aside: put them back first.
if [ -d "$PREVIOUS" ]; then
  say "an earlier --from-tree run did not finish; putting its previous snapshot back."
  restore_snapshot
fi

snapshot_at=""
if [ "$from_tree" -eq 1 ]; then
  say "copying the working tree to the snapshot..."
  /bin/mkdir -p "$SNAPSHOT"
  # The sources last installed stay aside until this install works, so a failed --from-tree never changes what
  # automatic renewals build.
  if [ -n "$(state_field snapshot)" ] && [ -f "$SNAPSHOT/project.yml" ]; then
    /bin/rm -rf "${PREVIOUS:?}"
    if ! /usr/bin/rsync -a --exclude /build/ "$SNAPSHOT/" "$PREVIOUS/"; then
      die 73 "could not keep the previous snapshot aside, so the working tree was not copied."
    fi
  fi
  restore_needed=1
  # The Android version (/Android/) is not part of the iPhone build, and its build output runs to a gigabyte.
  if ! /usr/bin/rsync -a --delete \
      --exclude /build/ --exclude .build/ --exclude DerivedData/ --exclude .git/ --exclude .DS_Store \
      --exclude /Android/ \
      --exclude '*.xcodeproj/xcuserdata/' --exclude '*.xcodeproj/project.xcworkspace/xcuserdata/' \
      --exclude "/$SNAPSHOT_MARK" "$ROOT/" "$SNAPSHOT/"; then
    die 73 "could not copy the working tree to $SNAPSHOT."
  fi
  snapshot_at=$(/bin/date -u '+%Y-%m-%dT%H:%M:%SZ')
  new_id=$(snapshot_id "$SNAPSHOT") || die 73 "could not read the copied sources."
  printf '%s %s\n' "$new_id" "$snapshot_at" > "$SNAPSHOT/$SNAPSHOT_MARK"
  say "the snapshot is $new_id."
else
  if [ -f "$SNAPSHOT/$SNAPSHOT_MARK" ]; then snapshot_at=$(/usr/bin/awk '{ print $2 }' "$SNAPSHOT/$SNAPSHOT_MARK"); fi
  new_id=$(snapshot_id "$SNAPSHOT") || die 73 "could not read the snapshot."
  recorded=$(state_field snapshot)
  if [ "$(printf '%s' "$recorded" | /usr/bin/cut -c1-12)" != "$new_id" ]; then
    say "note: the snapshot's files differ from the recorded install ($recorded); installing what is in the snapshot."
  fi
  say "renewing snapshot $new_id."
fi
case "$snapshot_at" in [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]Z) ;; *) snapshot_at="" ;; esac

# 3. The iPhone: --device; else the one last installed on; else (the first time) the one connected iPhone.
if [ -z "$device" ]; then
  "$XCRUN" devicectl list devices --quiet --timeout 30 --json-output "$tmp/devices.json" >/dev/null 2>&1 || true
  pick=$(pick_iphone "$tmp/devices.json" "$(state_field device)")
  case "$pick" in
    "OK "*) device="${pick#OK }" ;;
    "UNREACHABLE "*) die 69 "your iPhone isn't reachable. Unlock it on the same Wi-Fi as this Mac, or connect it with a cable." ;;
    UNREACHABLE) die 69 "no iPhone is reachable. Unlock it on the same Wi-Fi as this Mac, or connect it with a cable." ;;
    NONE) die 69 "no paired iPhone found. Connect it by cable once, turn on Developer Mode and trust this Mac." ;;
    MANY*) die 69 "several iPhones are connected: ${pick#MANY }. Leave only yours connected, or pass --device." ;;
    *) die 69 "the list of devices could not be read (xcrun devicectl list devices)." ;;
  esac
fi
say "the iPhone is $device."

# 4. The Xcode project, generated from project.yml in the snapshot.
# Where XcodeGen is installed the project is made afresh; elsewhere the one kept in the repository is used (it is
# regenerated before every commit that changes project.yml), so nobody needs XcodeGen just to install the app.
if [ -x "$XCODEGEN" ]; then
  if ! (cd "$src" && "$XCODEGEN" generate --quiet) > "$tmp/xcodegen.log" 2>&1; then
    /usr/bin/tail -n 3 "$tmp/xcodegen.log" || true
    die 76 "XcodeGen could not make the Xcode project from project.yml (see above)."
  fi
elif [ ! -d "$src/$PROJECT" ]; then
  die 76 "there is no $PROJECT in the copy and XcodeGen is not installed to make one."
fi

# 5. Build. -allowProvisioningUpdates lets Xcode make a fresh 7-day profile for the Personal Team. Nice 19 and two jobs:
#    the build must not slow the Mac down while someone is using it.
if out_of_time; then die 78 "the time limit was reached before the build started."; fi
say "building for iOS (this renews the signing profile)..."
/bin/mkdir -p "$RENEWER_DIR"
build_status=0
# shellcheck disable=SC2016  # the $1 and $@ belong to the inner bash
run_limited "$(seconds_left)" "$BUILD_LOG" /bin/bash -c 'cd "$1" && shift && exec "$@"' _ "$src" \
  /usr/bin/nice -n 19 "$XCRUN" xcodebuild -project "$PROJECT" -scheme "$SCHEME" -configuration Release \
  -destination 'generic/platform=iOS' -derivedDataPath build/ios -jobs 2 -allowProvisioningUpdates build \
  || build_status=$?
/bin/chmod 600 "$BUILD_LOG" 2>/dev/null || true
if [ "$build_status" -eq 124 ]; then
  die 78 "the time limit ($limit seconds) was reached during the build, so it was stopped."
elif [ "$build_status" -ne 0 ]; then
  /usr/bin/grep -E 'error:' "$BUILD_LOG" | /usr/bin/cut -c1-300 | /usr/bin/tail -n 5 || true
  if /usr/bin/grep -Eiq 'error:.*(no accounts?|signing|provisioning|code ?sign|certificate|development team|apple id|session has expired|sign in)' "$BUILD_LOG"; then
    die 74 "Xcode could not sign the app. Open Xcode › Settings › Accounts and check your Apple ID."
  fi
  die 76 "the build failed (see $BUILD_LOG)."
fi
app=$(/usr/bin/find "$src/build/ios/Build/Products" -maxdepth 2 -type d -name "$APP_NAME" -path '*-iphoneos/*' \
      2>/dev/null | /usr/bin/head -n 1)
if [ -z "$app" ]; then die 1 "the build finished, but no $APP_NAME was found under build/ios/Build/Products/*-iphoneos/."; fi
say "built."

# 6. The end date, from the profile inside the app that is about to be installed.
facts=$(profile_facts "$app/embedded.mobileprovision")
if [ -z "$facts" ]; then die 1 "built, but the end date could not be read from $APP_NAME's embedded.mobileprovision."; fi
read -r profile_created profile_expires bundle_id <<< "$facts"
previous_expires=$(state_field profileExpires)

# 7. Install. A first try sometimes fails while the iPhone wakes up; a second one has worked so far.
install_once() {
  if out_of_time; then return 124; fi
  local wait=$(( $(seconds_left) < 600 ? $(seconds_left) : 600 ))
  run_limited "$wait" "$tmp/install.log" "$XCRUN" devicectl device install app --device "$device" "$app"
}
say "installing on $device..."
install_status=0
install_once || install_status=$?
if [ "$install_status" -ne 0 ] && [ "$install_status" -ne 124 ]; then
  say "the install failed; trying once more in $RETRY_WAIT seconds."
  /bin/sleep "$RETRY_WAIT"
  install_status=0
  install_once || install_status=$?
fi
if [ "$install_status" -eq 124 ]; then
  /usr/bin/tail -n 3 "$tmp/install.log" 2>/dev/null || true
  die 78 "the time limit ($limit seconds) was reached during the install."
elif [ "$install_status" -ne 0 ]; then
  /usr/bin/tail -n 4 "$tmp/install.log" | /usr/bin/cut -c1-300 || true
  if ! /usr/bin/grep -Eiq 'locked|passcode' "$tmp/install.log" \
    && /usr/bin/grep -Eiq "not connected|isn't connected|unavailable|unreachable|(could|can)n?'?t connect|could not connect|failed to connect|no devices? (found|matched)|timed out|not paired|CoreDeviceError error (3002|4000)" "$tmp/install.log"; then
    die 69 "your iPhone isn't reachable. Unlock it on the same Wi-Fi as this Mac, or connect it with a cable."
  fi
  die 77 "the install failed. Unlock the iPhone and keep it nearby, then run this again."
fi
restore_needed=0
/bin/rm -rf "${PREVIOUS:?}"

# 8. The end date as the person reads it, and the state file.
if ! expiry=$(expiry_text "$profile_expires"); then die 1 "installed, but the end date could not be formatted."; fi
say "installed. This build expires $expiry. Run scripts/renew.sh again before then."
if [ -n "$previous_expires" ] && [ "$previous_expires" = "$profile_expires" ]; then
  say "note: the end date did not move; Xcode reused its earlier signing profile."
fi
if record_state "$new_id" "$snapshot_at" "$built_from" "$device" "$profile_created" "$profile_expires" "$bundle_id"; then
  say "recorded this install for the menu-bar renewer in $STATE."
else
  say "could not record this install in $STATE, so the menu-bar renewer still shows the one before."
fi
