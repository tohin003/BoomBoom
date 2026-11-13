# BoomBoom Renewer

A small Mac menu-bar item that keeps BoomBoom signed. BoomBoom is signed with a free Apple ID, so each install works
for 7 days. The item shows the days left and renews the app on the iPhone: when you choose Renew Now, and by itself.
It starts at every login. Its menu-bar title carries a
small music-note glyph (♪ 5 days).

## What it does

**In the menu bar:** a music note and the days left, such as "♪ 5 days". It shows "Expired" after the end date,
"Renewing…" while a renewal runs, and "No date" when it can't find one.

**In the menu:**

| Line | Example |
|---|---|
| The end date | "Expires Fri 20 Nov at 15:24" |
| Where the date came from, only before renew.sh has installed once | "Date from Xcode's newest profile" |
| What happens next | "Renews in 6 days", "Renews right after it expires", "Renews when your iPhone is reachable" |
| **Renew Now** | Renews right away (it still checks the iPhone first) |
| **Renew Automatically** | A check mark, on by default |
| The last result | "Renewed today at 09:14", or "Couldn't renew today at 09:14" and why: "Your iPhone wasn't reachable. Connect it, then choose Renew Now." |
| **Quit** | It starts again at the next login |

**Where the date comes from:**
- The install last made by `scripts/renew.sh`. After every successful install it writes an owner-only state file,
  `~/Library/Application Support/BoomBoomRenewer/state.json`: the snapshot's identity, the device, and the profile's
  dates and app id. Nothing else of the profile is kept.
- Until renew.sh has installed once, the date comes from the newest Xcode profile for `io.github.tohin003.BoomBoom`,
  if there is one. Profiles are read straight from their files; the Keychain and the `security` tool are never used.

**What it builds: the snapshot.** BoomBoom's repository may have no git history, so the item never builds the working
tree. `scripts/renew.sh --from-tree` copies the working tree into
`~/Library/Application Support/BoomBoomRenewer/snapshot/`, builds that copy, installs it, and only then keeps it as
the snapshot. Every renewal after that (`scripts/renew.sh`, what the item runs) rebuilds the snapshot, keeping its build
folder so builds are incremental. A half-finished edit in the repository therefore never reaches the iPhone: the phone
only ever gets what you last installed on purpose. If a `--from-tree` build or install fails, the old snapshot and the
state stay as they were.
- The copy leaves out `build/`, `.build/`, `DerivedData/`, `.git/`, Xcode's per-user data and `.DS_Store`. The Xcode
  project is made again from `project.yml` with XcodeGen before every build. The snapshot's identity is the first 12
  hex digits of a hash over its files (not counting build products or the generated project). The build folder is
  `snapshot/build/`, and it survives the next `--from-tree`.
- Builds use `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`, `nice -n 19` and `xcodebuild -jobs 2`.
- Two renewals never overlap: renew.sh holds `renew.lock`, and only one copy of the item runs at a time.
- renew.sh stops a renewal after 35 minutes in all (`--time-limit`), and the item stops it after 40 as a last resort.

**Automatic renewal.** It checks at login, on wake (a minute after) and every 30 minutes. It also checks just after the
start of the last day and just after the end date. It renews only when both of these hold:
1. 1 day or less is left, or the app has expired.
2. The iPhone is reachable: `xcrun devicectl list devices` shows it paired and connected, by cable or on the network.
   The iPhone renew.sh last installed on is the one used. Another iPhone never takes its place. A Simulator is never
   taken for the iPhone.

When 1 day or less is left and the iPhone stays out of reach for 20 minutes (twice in a row), it posts **one** Mac
notification for that end date. It says what to do: unlock the iPhone on the same Wi-Fi as this Mac, or connect it
with a cable. An automatic renewal that fails for a reason you can fix, such as Xcode's sign-in, also posts one
notification per end date.

After a failed automatic try it rests 25 minutes, then twice as long after each further failure in a row, up to 6
hours. A lasting problem therefore never means a heavy build every half hour.

**The switch.** Xcode may reuse a cached profile that is still valid. If it does, renewing early doesn't move the end
date. renew.sh then says "the end date did not move", and the item waits for that end date before renewing again. The
switch is `RenewalSwitch.timing` in `Sources/RenewerCore/Policy.swift`:
- `.beforeExpiry` (now): renews once 1 day or less is left.
- `.afterExpiry`: renews right after the end date. The app on the iPhone stops opening from the end date until the
  renewal finishes, usually a few minutes.

## Install and uninstall

First put BoomBoom on the iPhone once, with the iPhone connected and unlocked:

```
scripts/renew.sh --from-tree
```

Then build and install the menu-bar item:

```
swift build --package-path Renewer -c release --product BoomBoomRenewer
Renewer/install.sh --dry-run     # what it will do
Renewer/install.sh
```

- install.sh builds nothing. It puts the app in place, signed ad hoc (no identity, no Keychain), and writes the login
  agent. Then it loads the agent, which starts the item.
- The first time, macOS asks whether it may send notifications: allow them. System Settings › General › Login Items
  lists it under "Allow in the Background".
- To update, build again and run install.sh again. Don't reinstall while the bar says "Renewing…": that stops the
  build.
- To put a newer version of BoomBoom on the iPhone, run `scripts/renew.sh --from-tree` again. An install by other
  means (Xcode's Run button, a bare xcodebuild) doesn't update the snapshot or `state.json`, so the item would renew
  the snapshot recorded before.

**Uninstall:**

```
Renewer/uninstall.sh             # stops it; removes the login agent and the app
Renewer/uninstall.sh --all       # also the snapshot, build folder, state.json, renewer.json and the log
```

## renew.sh

`scripts/renew.sh --help` lists its options. It builds BoomBoom for the iPhone (scheme `BoomBoom`, Release, signed with
the Personal Team by automatic signing, `-allowProvisioningUpdates`), installs it with `xcrun devicectl`, and records
`state.json`. Each kind of failure has its own exit code, which the item reads:

| Code | Meaning |
|---|---|
| 0 | done |
| 1 | no app or no expiry date found |
| 64 | usage |
| 66 | no project: nothing installed yet (run `--from-tree`), or no `project.yml` |
| 69 | iPhone not reachable |
| 71 | Xcode not installed or not selected |
| 73 | the copy of the working tree couldn't be made or put in place |
| 74 | signing failed (Xcode isn't signed in) |
| 75 | another renewal is running |
| 76 | build failed (or XcodeGen couldn't make the project) |
| 77 | install failed (for example the iPhone is locked) |
| 78 | time limit |

`--dry-run` prints every step and writes nothing: no lock, no copy, no build, no install.

## Files

| Path | What |
|---|---|
| `Package.swift` | Its own Swift package (macOS 26, Swift 6). The app, `Packages/` and `Tools/` don't see it. |
| `Sources/RenewerCore/` | Everything that decides, as plain values: days left and the menu's words (`Words.swift`, `Copy.swift`), the decision to renew (`Policy.swift`), devicectl's list (`Devices.swift`), renew.sh's output and exit codes (`RenewScript.swift`), the state file (`InstallState.swift`), the item's record (`RenewerRecord.swift`), profiles' dates (`Profiles.swift`), the log and its redaction (`LogFile.swift`, `Redaction.swift`, `LogWords.swift`) |
| `Sources/BoomBoomRenewer/` | The app: SwiftUI `MenuBarExtra` (`RenewerApp.swift`), the model that schedules and acts (`RenewerModel.swift`), subprocesses (`Runner.swift`), notifications (`Notifier.swift`), `--check` (`CheckCommand.swift`), the entry point and single-copy lock (`Entry.swift`) |
| `Tests/RenewerCoreTests/` | Swift Testing tests for every part of RenewerCore |
| `Support/Info.plist` | The app bundle's Info.plist (`LSUIElement`: no Dock icon). install.sh adds the repository's path. |
| `Support/LaunchAgent.plist` | The login agent's template. install.sh fills in the app's path. |
| `install.sh`, `uninstall.sh` | Put it in place and take it away (both have `--dry-run`) |
| `../scripts/renew.sh` | Builds and installs on the iPhone, keeps the snapshot, writes `state.json` |

Once installed, it uses these places:

| Path | What |
|---|---|
| `~/Library/Application Support/BoomBoomRenewer/` (0700) | `BoomBoom Renewer.app`; `state.json` (written by renew.sh); `renewer.json` (the item's own record: Renew Automatically, the last result, notices posted); `renewer.lock`; `renew.lock`; `snapshot/` (with its `build/`); `build.log` |
| `~/Library/LaunchAgents/io.github.tohin003.BoomBoomRenewer.plist` | Starts it at login. It is started again after a crash, but not after Quit. |
| `~/Library/Logs/BoomBoomRenewer.log` (0600) | One line per event. Over 1 MB it becomes `.log.1`. |

## What it never does

- It never builds the working tree: only the snapshot you installed on purpose.
- It never changes anything on the iPhone except installing BoomBoom through renew.sh. To find the iPhone it only lists
  devices (`devicectl list devices`).
- It never reads or changes the Keychain, signing certificates or Apple ID details. It never removes or edits
  provisioning profiles; it reads only their dates and app id.
- It never logs tokens, team ids, Apple ID e-mail addresses, the names in signing identities, or Personal Team names.
  Every log line is redacted first (`Redaction.swift`). Devices appear only by identifier. The full xcodebuild output
  goes to `build.log` in the owner-only folder, never to the item's log.
- It never runs when nobody is logged in, and never wakes the Mac. A renewal due while the Mac sleeps runs a minute
  after it wakes.
- It never runs twice at once.

## How to check it

- **What it sees and would do now,** without renewing or writing anything. It lists devices and reads the files.

  ```
  "$HOME/Library/Application Support/BoomBoomRenewer/BoomBoom Renewer.app/Contents/MacOS/BoomBoomRenewer" --check
  ```

  It prints the repository, the state file, the snapshot, the installed snapshot's identity, the end date and where it
  came from, the switch, the stage, the iPhone, the decision, and the bar and menu words. Before installing,
  `Renewer/.build/release/BoomBoomRenewer --check` does the same.
- **Running:** `launchctl print gui/$(id -u)/io.github.tohin003.BoomBoomRenewer | head -20` (look for `state = running`).
- **The log:** `tail -30 ~/Library/Logs/BoomBoomRenewer.log`. A check's line is written only when it changes.
- **The state:** `cat ~/Library/Application\ Support/BoomBoomRenewer/state.json`.
- **The switch, once, with the real iPhone (before the end date):** choose Renew Now. If the menu then says "Renewed …;
  the end date didn't move", Xcode reused its cached profile. Set `RenewalSwitch.timing = .afterExpiry`, then build,
  test and install again. If the date moved by about 7 days, keep `.beforeExpiry`.
