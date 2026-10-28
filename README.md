# firetv-debloat

Safe, reversible tools for an Amazon Fire TV Stick over ADB, plus **Shelf**, a home screen that
replaces Amazon's ad-filled one.

- The debloat scripts only ever use `pm disable-user --user 0` to switch a package off, and
  `pm enable` to switch it back on. They never uninstall, clear or hide system apps, and they
  never write to `/system`.
- Shelf is a normal app. It disables nothing, and Amazon's launcher stays installed.

Built for and tested on a **Fire TV Stick AFTSS ("sheldon"), Fire OS 7.7.1.7**, from a Mac.

## Before you start

1. On the TV: **Settings → My Fire TV → About**, press OK on the device name 7 times. Then
   **My Fire TV → Developer Options → ADB debugging: ON**.
2. On the Mac: `brew install --cask android-platform-tools` (gives you `adb`).
3. Find the TV's IP in **Settings → My Fire TV → About → Network**, then in each terminal:
   ```sh
   export FIRETV_IP=192.168.2.11      # your TV's address
   adb connect $FIRETV_IP:5555        # accept "Allow USB debugging?" on the TV, tick Always allow
   adb devices                        # the line must end in "device", not "unauthorized"
   ```

## The scripts, in order

| Script | Changes the TV? | What it does |
|---|---|---|
| `preflight.sh [file]` | No | Saves the model, Fire OS and Android version, and the lists of enabled and disabled packages to `backups/before.txt` (or the file you name). Never overwrites an existing snapshot. |
| `launcher/install.sh` | Yes | Installs Shelf and gives it the Home button. See [Shelf](#shelf-the-home-screen). |
| `debloat.sh` | Yes | Disables the packages in `packages.txt`, at most 10 per batch. Shows what each one is and asks first, logs each disable to `backups/disabled.txt`, reboots the TV, waits for it, and stops so you can check the TV still works. Run it again for the next batch. `--dry-run` shows the batches without changing anything. |
| `restore.sh` | Yes | Turns packages back on with `pm enable`. Only touches packages listed in `backups/disabled.txt`. `--dry-run` prints the commands without running them. |
| `preflight.sh after.txt` | No | The "after" snapshot, to compare with `before.txt`. |

`never-disable.txt` lists packages `debloat.sh` refuses to touch: the launcher, settings, inputs,
remote and Bluetooth pairing, setup, the app store, webview, system UI and the OTA updater. It
also refuses any package whose name suggests launcher, settings, Wi-Fi or network, Bluetooth or
remote, input, updates or core system.

## Undo

**One package:**
```sh
./restore.sh com.amazon.tv.acr
```

**One batch** (do this first if something breaks after a batch, then re-disable one package at
a time to find the culprit):
```sh
./restore.sh --batch 1
```

**Everything the scripts disabled:**
```sh
./restore.sh               # add --dry-run first to see the commands
adb -s $FIRETV_IP:5555 reboot
```

**Shelf:** `./launcher/install.sh --undo` hands the Home button back to Amazon's home screen.
Then, to remove Shelf completely: `adb -s $FIRETV_IP:5555 uninstall tv.shelf.home`.

**If the TV won't work at all:** a factory reset brings back every package and Amazon's home
screen. On the TV: **Settings → My Fire TV → Reset to Factory Defaults**. If you can't reach
Settings, hold **Back + Right** on the remote for 10 seconds and confirm the reset prompt. A
reset erases your apps and sign-ins.

## What happened on this TV (2026-10-27)

- **Preflight:** AFTSS, Fire OS 7.7.1.7 (PS7717.5741N), Android 9. 259 packages enabled; only
  `com.android.nfc` disabled, which is how Amazon ships it.
- **Shelf** installed, and the Home button opens it.
- **Batch 1** (7 ad, tracking and telemetry packages) disabled cleanly, but **Fire OS put all
  seven back to enabled at the next reboot**. The owner chose to stop there. The entries are
  kept in `backups/reverted-by-fireos.txt`, and `packages.txt` has the lists commented out.
- **After:** 260 enabled (the 259 plus Shelf); still only `com.android.nfc` disabled.

### Where the package list came from

[humblemuzzu/dumbtv](https://github.com/humblemuzzu/dumbtv), `data/fireos/packages.txt`
(commit 755991b, 2026-10-27). Its catalog entry `fireos-amazon-aftss.yaml` was tested on this
exact build. dumbtv keeps separate lists for Fire OS 6 and for Fire OS 7/8; Fire OS 5 refuses
`pm disable-user` entirely.

Each package was then checked on this TV with `adb shell dumpsys package <name>`. One was kept
only if its name and the components it declares agreed it was ads, tracking or telemetry.

### Left enabled on purpose

| Package | Why |
|---|---|
| `com.amazon.tv.forcedotaupdater.v2`, `com.amazon.tv.easyupgrade` | Updates are left alone. |
| `com.amazon.connectivitydiag`, `com.amazon.wirelessmetrics.service` | Networking-related. |
| `com.amazon.logan` | Declares high-contrast text, Kids profile and HUD components, not just logging. |
| `com.amazon.tigris` | Declares a home activity, Appstore and upgrade-restore parts. |
| `com.amazon.media.recommendations` | Runs as the system user and includes Amazon's notification manager. |
| `com.amazon.hedwig` | Is Fire TV Channels on this build, not only a watch tracker. |
| `com.amazon.katoch`, `ale`, `perfc`, `dp.logger`, `privacypassservice`, `systemnotices` | Purpose could not be confirmed on this TV. |
| `com.amazon.device.metrics`, `crashmanager`, `rdmapplication`, `ftvads.deeplinking`, `tv.csapp`, `device.software.ota`(`.override`) | Protected by Amazon: `pm disable-user` is refused without root. |
| Alexa, Amazon Kids (`tahoe`), Luna (`spiderpork`) | Features, not ads or telemetry. |

## Shelf, the home screen

A home screen with CleanFrame's look and an Apple TV-style grid:

- A full-screen photograph, the time, and three quiet icons: **Wallpaper** (next photo),
  **Options** and **Settings**.
- Your first row of apps sits on a frosted-glass dock. Every other installed app follows in rows
  of 16:9 tiles showing each app's own banner. Scrolling down blurs and darkens the photo.
- **Hold OK** (or press Menu) on a tile: Move, Rename, Hide, App info. Moving works across the
  dock and the grid, so you choose what sits on the dock.
- **Back** jumps to the top. **Options** has the background, photo, tile size, app order, clock
  and a child lock.
- No internet permission, no ads, no tracking.

### Build and install

Needs JDK 17 (`brew install openjdk@17`; newer JDKs crash the Android dex tool) and the Android
SDK command-line tools with `platforms;android-25` and `build-tools;34.0.0`.

```sh
cd launcher
./build.sh            # → build/shelf.apk
./install.sh          # installs and takes over Home; --dry-run shows the plan; --undo reverts
```

`install.sh` makes four changes, all undone by `--undo`:
1. installs the APK
2. allows it to run in the background (battery optimisation allowlist)
3. sets alarm `min_futurity` to 0 so Home switches without a 5-second delay (the old value is
   saved on the TV and put back)
4. turns on the "Shelf home button" accessibility service, which waits for Amazon's home
   screen to appear and replaces it with Shelf

**Keep `launcher/release.keystore`.** It signs the app, and updates must be signed with the same
key. It is git-ignored on purpose.

### Credits

- Code forked from [dumbtv home](https://github.com/humblemuzzu/dumbtv) (MIT), see
  `launcher/LICENSE-dumbtv`.
- Look adapted from [CleanFrame](https://github.com/nthnclrk/CleanFrame) by Nathan Clark
  (MIT), see `launcher/LICENSE-CleanFrame`.
- Photographs from Unsplash (Unsplash License): Misty coast by Ganapathy Kumar, Quiet cove by
  Joshua Wordel, Forest mist by Dejan Zakic, Desert stars by Mohammad Alizade. Full list in
  `launcher/assets/CREDITS.txt`.
- Geist font, SIL Open Font License, see `launcher/assets/fonts/Geist-OFL.txt`.
