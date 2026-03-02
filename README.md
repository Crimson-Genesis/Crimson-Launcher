# Crimson Launcher (codename: crimson)

Crimson Launcher is a forked version of Olauncher with integrated daily task management. This fork was created to gamify your days and keep Daily, Timed, and Timeless tasks visible directly on the home screen. Other task apps were too noisy or easy to forget to open. With Olauncher's simplicity as the foundation, todos were integrated directly into the launcher to keep remaining tasks always in sight.

## Key Features

- **Home-pinned checklist** with Daily, Timed, and Timeless tasks
- **Hardware-level screen time tracking** (includes screen-on even when locked)
- **Unlock count tracking** - know how many times you unlocked your phone
- **Configurable daily reset time** - choose when your tasks reset (default: midnight)
- **Boiler (Template) system** - save and swap sets of daily tasks quickly
- **Hardcore mode** - blocks the app drawer until all today's tasks are completed
- **Progress logging** - JSONL format with selectable storage folder
- **Backup & Restore** - export/import all tasks, templates, and settings to JSON
- **App management** - hide, rename, or uninstall apps directly from the drawer
- **Offline-first** - fully functional without internet; all data stays local

## Screenshots

| Home Page | Right Page | Settings Page |
|-----------|------------|---------------|
| <img src="./photos/1_home_page.jpeg" width="150"> | <img src="./photos/2_right_page.jpeg" width="150"> | <img src="./photos/3_settings_page.jpeg" width="150"> |

| Task Management Page | App Drawer |
|----------------------|------------|
| <img src="./photos/4_left_page.jpeg" width="150"> | <img src="./photos/11_app_drawer.jpeg" width="150"> |

---

## Navigation

Crimson Launcher uses a circular swipe navigation between fragments:

```
Home → Right Page → Settings → Task Management → Home
                  ↺ (and reverse)
```

### Gesture Navigation

| Gesture | Action |
|---------|--------|
| **Swipe Left** (from Home) | Go to Right Page (Upcoming & Completed tasks) |
| **Swipe Right** (from Home) | Go to Task Management Page (Daily task creation) |
| **Swipe Left** (from Right Page) | Go to Settings |
| **Swipe Left** (from Settings) | Go to Task Management Page |
| **Swipe Right** (from Task Management) | Go to Settings |
| **Swipe Right** (from Settings) | Go to Home |
| **Swipe Up** (from bottom of today's tasks) | Open App Drawer |
| **Swipe Down** (from top of today's tasks) | Open Notification Panel or Search (based on settings) |

---

## Fragment Details

### 1. Home Page

The Home Page is the main dashboard displaying your daily overview and remaining tasks.

<img src="./photos/1_home_page.jpeg" width="250" alt="Home Page">

#### Features

**Screen Time & Unlock Count**
- Shows total screen-on time (including when phone is locked)
- Displays unlock count - how many times the phone was unlocked
- Data is fetched at the hardware level using Android's UsageStatsManager
- Format: `Xh Ym   [N unlocks]`

**Clock & Date**
- Displays current time and today's date
- Can be toggled on/off from Settings

**Today's Task List**
- Shows all tasks due today: Daily, Timed, and Timeless tasks
- Tap the checkbox to mark complete/incomplete
- Completed tasks show a strikethrough

#### Editing Tasks

Long-press on any task in the home page to access edit/delete options:

<img src="./photos/7_edit-or-delete_in_home_page.jpeg" width="250" alt="Edit/Delete in Home Page">

The edit section allows you to modify task details:

<img src="./photos/8_edit_section_in_home_page.jpeg" width="250" alt="Edit Section">

---

### 2. Right Page

The Right Page displays timed and timeless tasks that need to be done, along with completed tasks.

<img src="./photos/2_right_page.jpeg" width="250" alt="Right Page">

#### Sections

**Upcoming Tasks**
- Shows only Timed and Timeless tasks (daily tasks are managed separately)
- Tasks are sorted by date/time
- Tap checkbox to mark complete

**Completed Tasks**
- Shows completed Timed and Timeless tasks
- Keeps your view clean by separating upcoming from done

After completing some tasks, the page looks like:

<img src="./photos/9_right_after_some_tast_completed.jpeg" width="250" alt="After Task Completion">

#### Managing Completed Tasks

Long-press on a completed task to delete it:

<img src="./photos/10_showing_only_delete_option_of_completed_section.jpeg" width="250" alt="Delete Completed">

---

### 3. Task Management Page

The Task Management Page (accessible by swiping right from Home) handles daily task creation and boiler/template management.

<img src="./photos/4_left_page.jpeg" width="250" alt="Task Management Page">

#### Upper Section: Task Creation

This section allows you to create new Daily tasks:

| Field | Description |
|-------|-------------|
| **Task Input** | Enter the task description |
| **Weekday Selector** | Select which days the task repeats |
| **From Date** | Start date for the task |
| **To Date** | End date for the task (optional) |
| **From Time** | Start time for the task |
| **To Time** | End time for the task |

**Long-press** on any date/time button to reset it.

#### Action Buttons

| Button | Function |
|--------|----------|
| **Boiler** | Opens the template section to save/load task sets |
| **Save** | Creates the task with selected settings |
| **All** | Toggles all weekdays on/off |

#### Boiler (Template) Section

Press the Boiler button to access saved task templates:

<img src="./photos/5_boiler_section.jpeg" width="250" alt="Boiler Section">

- Save current task set as a template for quick switching
- Templates don't affect Timed or Timeless tasks

#### Lower Section: Task List

Shows all Daily tasks for the currently active boiler. 

**Tap** a task to edit it.

**Long-press** for options:

<img src="./photos/6_copy-or-delete_in_left_page.jpeg" width="250" alt="Copy/Delete">

- **Copy**: Copies the task to the upper section where you can modify it and create a new task with similar settings
- **Delete**: Removes the task

---

### 4. App Drawer

The App Drawer displays all installed applications on your device.

<img src="./photos/11_app_drawer.jpeg" width="250" alt="App Drawer">

#### Features

- Lists all installed apps alphabetically or by recent use
- Search functionality - type to filter apps
- Auto-show keyboard option (configurable in Settings)

#### Long-press Options

Long-press any app to access:

| Option | Description |
|--------|-------------|
| **Uninstall** | Remove the app from your device |
| **Rename** | Change the app's display name in the drawer |
| **Hide** | Hide the app from the drawer (accessible via Settings) |
| **Info** | View app details (permissions, version, etc.) |
| **Close** | Force stop the app if it's running |

---

### 5. Settings Page

The Settings Page contains all customization options for Crimson Launcher.

<img src="./photos/3_settings_page.jpeg" width="250" alt="Settings Page">

---

#### Top Section

| Setting | Description |
|---------|-------------|
| **Crimson Logo** | Tap to access hidden apps list |
| **Change Default Launcher** | Opens system picker to set default home launcher |

---

#### Home Section

| Setting | Description |
|---------|-------------|
| **Show Date Time** | Toggle clock and date display on Home Page |
| **Screen Time** | Toggle screen time and unlock count display on Home Page |

---

#### Appearance Section

| Setting | Description |
|---------|-------------|
| **Auto Show Keyboard** | Automatically show keyboard when App Drawer opens |
| **Status Bar on Top** | Keep status bar visible at the top of the screen |
| **Text Size** | Adjust the font size across the launcher (slider control) |
| **App Alignment** | Choose left or right alignment for clock/date and app drawer items |

---

#### Gestures Section

| Setting | Description |
|---------|-------------|
| **Swipe Down for** | Configure swipe down gesture: **Notification Panel** or **Search** |

---

#### Other Section

| Setting | Description |
|---------|-------------|
| **Hardcore Mode** | When enabled, blocks App Drawer until all today's tasks are completed |
| **Daily Reset Time** | Set the time when daily tasks reset (default: 00:00 midnight) |
| **Backup Data** | Export all settings, tasks, and templates to a JSON file (user selects save location) |
| **Restore Data** | Import from a previously saved JSON backup file. **Note:** Current tasks will be replaced |
| **Clean Slate** | Delete all boilers and tasks - a fresh start |

---

#### Progress Logs Section

| Setting | Description |
|---------|-------------|
| **Logging Enabled** | Toggle to enable/disable event logging (default: off) |
| **Log Storage Folder** | Select where log files are saved |

---

## Installation

### Requirements

1. **Android Device** (Android 8.0+ recommended)
2. **Usage Access Permission** - Required for screen time and unlock tracking
3. **No Internet Required** - Fully functional offline

### Installation Steps

1. Download the latest APK from the [Releases](https://github.com/crimson-genesis/Olauncher/releases) page
2. Transfer the APK to your device
3. **For Google/Pixel phones**: 
   - Go to Settings → Security → Disable "Verify apps over USB" or enable "Install unknown apps"
   - This allows installation of apps outside the Play Store
4. Open the APK file to install
5. Set Crimson Launcher as your default home launcher when prompted

### Permissions

- **Usage Access**: For screen time and unlock count tracking
- **Storage Access**: For backup/restore and log folder selection (uses SAF - no storage permission needed on Android 10+)

---

## Technical Details

### Data Storage

- **Tasks & Templates**: Room Database (SQLite)
- **Settings**: SharedPreferences
- **Backups**: JSON file (user-selected location via Storage Access Framework)
- **Logs**: JSONL files in user-selected folder

### Task Types

| Type | Behavior |
|------|----------|
| **Daily** | Repeats on selected weekdays; managed in Task Management Page |
| **Timed** | One-time task with specific date and time; shown in Right Page |
| **Timeless** | One-time task without specific time; shown in Right Page |

### Daily Reset

- By default, tasks reset at midnight (00:00)
- Configurable via "Daily Reset Time" in Settings
- Resets completed status for Daily tasks

---

## License

- **GPLv3** - See [LICENSE](./LICENSE) file
- **Forked from**: [Olauncher](https://github.com/tanujnotes/Olauncher) © 2020-2026 Tanuj (tanujnotes)
- **Current Maintainer**: © 2026 Yuvraj Mahilange (crimson-genesis)

### Design Assistance

- [notarkhit](https://github.com/notarkhit)
- [AbraraliS](https://github.com/AbraraliS)
- [AJAjith0503](https://github.com/AJAjith0503)

---

## Support

For issues, feature requests, or contributions, please visit the [GitHub Repository](https://github.com/crimson-genesis/Olauncher).
