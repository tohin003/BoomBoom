# Put BoomBoom on your iPhone

No programming needed. You do five small things once; after that the app looks after itself.

**You need:** a Mac, an iPhone (iOS 26 or later), the iPhone's cable, and an Apple ID (the free one you already
have). Allow about an hour the first time; nearly all of it is waiting for a download.

---

## 1. Get Xcode (the long wait, once)

On the Mac open the **App Store**, search for **Xcode**, click **Get**. It is free and very large.

When it has finished, **open Xcode once**. If it asks which platforms to add, tick **iOS** and let it finish.

## 2. Sign in to Xcode with your Apple ID

In Xcode, in the menu bar at the top: **Xcode › Settings › Accounts**. Click the **+** at the bottom left, choose
**Apple Account**, and sign in. You should then see a line called **Personal Team**.

Your password goes to Apple, inside Apple's own app. BoomBoom's installer never sees it.

## 3. Get the iPhone ready

1. Connect the iPhone to the Mac with its cable and unlock it. When it asks **Trust This Computer?**, tap
   **Trust** and enter your passcode.
2. On the iPhone: **Settings › Privacy & Security**, scroll to the very bottom, tap **Developer Mode**, turn it on.
   The iPhone restarts. Unlock it and tap **Turn On**.

   (If you do not see Developer Mode, leave the iPhone connected with Xcode open for a minute, then look again.)

## 4. Install

On the Mac press **⌘ Space**, type **Terminal**, press **Return**. Copy the line below, paste it into the window
that opened, and press **Return**:

```
bash -c "$(curl -fsSL https://raw.githubusercontent.com/tohin003/BoomBoom/main/install.sh)"
```

Keep the iPhone unlocked and connected. The installer checks steps 1 to 3 and tells you in plain words if one of
them is not done yet; do what it says and paste the line again. Building takes 5 to 15 minutes.

## 5. Trust yourself on the iPhone (once)

On the iPhone: **Settings › General › VPN & Device Management**, tap your Apple ID under **Developer App**, tap
**Trust**.

Open **BoomBoom**. To see your own library, likes and playlists, sign in with your Google account inside the app
(Library, or the account button at the top right). You can add up to three accounts.

---

## Every week: nothing to do

Apple lets a free Apple ID sign an app for 7 days at a time. The installer added a small **BoomBoom item to the
Mac's menu bar** that shows the days left and renews the app by itself. It only needs the Mac to be on and the
iPhone to be near it now and then (same Wi-Fi, or the cable) and unlocked at some point.

If the menu-bar item says it could not renew, connect the iPhone by cable, unlock it, and choose **Renew Now**.

## Getting a newer version

Paste the same line from step 4 again. Your settings and sign-in on the iPhone stay.

## If something does not work

| What you see | What to do |
|---|---|
| "Untrusted Developer" when opening the app | Step 5. |
| The installer says no iPhone was found | Unlock the iPhone, use the cable, tap Trust. |
| Developer Mode is not in Settings | Keep the iPhone connected with Xcode open for a minute, then look again at the bottom of Privacy & Security. |
| "Unable to install" or a message about the number of apps | A free Apple ID allows three apps installed this way. Remove one you no longer use. |
| After a week the app will not open ("no longer available") | The Mac was off or the iPhone away for the whole week. Connect by cable and choose Renew Now in the menu bar, or paste the line from step 4 again. Nothing is lost. |
| Xcode asks about platforms or components | Let it install them (tick iOS), then paste the line again. |
| Anything else | The installer prints where its log is. Send that file to the person who gave you BoomBoom. |

## Removing it

Delete the app on the iPhone as usual. On the Mac, in Terminal: `bash ~/BoomBoom/Renewer/uninstall.sh`, then
put the `BoomBoom` folder in your home folder in the Bin.

## Good to know

- BoomBoom is a personal project and has nothing to do with YouTube or Google. It plays YouTube Music without ads
  and in the background, which YouTube's terms do not allow. Use it for your own listening and at your own risk.
- With a free Google account you get YouTube's normal stream (about 130 to 160 kbps). With YouTube Premium you get
  the 256 kbps one. The player's details line shows what you are getting.
- The app never sees your Google or Apple password: Google's is typed into Google's own page, Apple's into Xcode.
