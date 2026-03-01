# Crimson Launcher (codename: crimson)

This is a forked version; the original developer continues work on the upstream Olauncher separately. I wanted to gamify my days and keep Daily, Timed, and Timeless tasks visible on the home screen. Other apps were noisy or easy to forget to open. I loved Olauncher’s simplicity, so I integrated todos directly into the launcher to keep the remaining tasks always in sight. That is why this app exists.

## Features
- Home-pinned checklist with Daily, Timed, and Timeless tasks.
- Configurable daily reset time with streak tracking and day summaries.
- Templates to swap daily task sets swiftly.
- Hardcore mode: gate the app drawer until today’s tasks are done.
- Progress logging to JSONL with selectable folder and on/off toggle.
- Backup/restore (tasks + templates) via Storage Access Framework.
- Hidden-app handling, rename, swipe gestures, status bar/date toggles, text scale.

## User Manual (swift)
- Add tasks: on the home list, add Daily/Timed/Timeless items; set times for Timed.
- Complete/uncomplete: tap the checkbox; streak and stats update accordingly.
- Templates: on the left page, use Save/Template to store or apply a daily set.
- Reset time: Settings → Other → Daily reset time; choose your rollover.
- Hardcore mode: Settings → Other → Hardcore; when on, finish today’s tasks before opening the drawer.
- Logging: Settings → Progress Logs; toggle on/off and choose a log folder.
- Backup/Restore: Settings → Other → Backup/Restore; uses SAF pickers.
- Hidden apps & rename: open the app drawer, long-press an item to hide/show or rename.
- Gestures: swipe up/down/left/right on home as configured; search is always ready.

## License
- GPLv3. See `LICENSE`.
- Forked from Olauncher © 2020–2026 Tanuj (tanujnotes); the original developer continues upstream work.
- Current maintainer: © 2026 Yuvraj Mahilange (crimson-genesis).
- Design assistance: [notarkhit](https://github.com/notarkhit), [AbraraliS](https://github.com/AbraraliS), [AJAjith0503](https://github.com/AJAjith0503).
