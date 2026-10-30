# GameGrabberMobile 📱

[![Latest Release](https://img.shields.io/github/v/release/arnavlul/GameGrabberMobile?label=Latest%20Release&color=38BDF8)](https://github.com/arnavlul/GameGrabberMobile/releases/latest)
[![Download APK](https://img.shields.io/badge/Download-GameGrabber.apk%20(v1.3.0)-10B981?style=for-the-badge&logo=android&logoColor=white)](https://github.com/arnavlul/GameGrabberMobile/releases/latest/download/GameGrabber.apk)

Universal offline web game downloader for Android (Native APK & Python CLI). Download and preserve HTML, Twine/SugarCube, Ren'Py Web, and browser games directly on mobile devices with batch URL processing, HTTP Range resuming, stream-truncation verification, and zero heavy browser dependencies.

---

## 📦 Download & Installation

### Option 1: Native Android App (Recommended)
👉 **[Download GameGrabber.apk (Latest v1.3.0)](https://github.com/arnavlul/GameGrabberMobile/releases/latest/download/GameGrabber.apk)**
1. Tap the download link on your phone.
2. Open the downloaded `GameGrabber.apk` and tap **Install**.
3. Open the app, grant file access when prompted, paste your links (or place a `links.txt` in `Downloads/`), and tap **START DOWNLOAD**.

### Option 2: Python CLI (Termux / PC)
For running via Termux on Android or from terminal:
```bash
pkg update && pkg install python git
git clone https://github.com/arnavlul/GameGrabberMobile.git
cd GameGrabberMobile
pip install -r requirements.txt
python grabber.py
```

---

## 🌟 Key Features & Reliability Safeguards

- **Universal Portal & Iframe Resolution:** Paste any portal URL or direct game link. It automatically resolves embedded game iframes, protocol-relative URLs, and cross-protocol redirects while filtering out ad trackers and avoiding duplicate HTML fetches.
- **Full HTML Entity Unescaping & Deep Story Traversal (Twine / SugarCube):**
  - Unescapes HTML-encoded `<tw-passagedata>` blocks (`&quot;`, `&lt;`, `&#39;`, decimal/hex entities).
  - Traverses passage graphs breadth-first starting from UI passages (`StoryBanner`, `StoryInit`) and `startnode`, following `[[...|Target]]`, `[[...->Target]]`, `<<link>>`, `<<button>>`, and `data-passage` links so opening scenes and images download first.
  - Preserves spaces and parentheses in asset paths (`Images/Others/Mr Brightside.jpg`, `Videos/Nia(Miad)/...`).
- **Secondary Script & Stylesheet Discovery:** Automatically downloads external `.css`, `.js`, and `.json` files in Phase 1 and scans them for nested asset references (`url(...)`, relative paths) before starting Phase 2.
- **Resumable Downloads + Truncation Verification (RFC 7233):**
  - Interrupted downloads cleanly resume from the exact byte using `.part` files and `Range: bytes=<offset>-` headers with automatic mid-stream retry and exponential backoff.
  - Verifies final `.part` size against `Content-Length` before renaming to prevent prematurely closed connections from saving half-downloaded videos.
- **Self-Healing Startup & Final Disk Audit:**
  - Automatically purges 0-byte or captive-portal/firewall HTML/XML block files before resuming.
  - Runs a **Final Disk Verification Audit** (`[AUDIT] Verified on storage: X/X files intact`) at the end of every job.
- **Background WakeLock & Scoped Storage Support:**
  - Acquires a CPU `PARTIAL_WAKE_LOCK` and keeps the download alive even when the screen turns off in your pocket.
  - Handles Android 6–15 storage permissions (`MANAGE_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE`) with automatic write-probe verification and `links.txt` auto-loading.
  - Automatically generates `run_game.py` and `run_game.bat` (HTTP 206 Range server) inside each game folder for instant PC/local playback.

---

## 📲 How to Use the Android APK

1. Open **GameGrabber**.
2. Paste your game links into the text box (one per line), or place a `links.txt` file in your phone's `Download/` folder to auto-load on launch.
3. *(Optional)* Select **Skip Videos** for low-data mode, or set a **Limit (MB)** (enforced cumulatively across the whole batch).
4. Tap **START DOWNLOAD**.
5. Wait for the console log to report:
   `[AUDIT] Verified on storage: X/X files intact.`
   `[+] 100% Verified (X files intact on disk)!`

### Playing Offline
Downloaded games are stored in:
```text
/sdcard/Download/GameGrabber/<Game_Name>-<Version>/
```
You can open `index.html` locally or transfer the folder to a PC and double-click `run_game.bat` to launch the built-in multithreaded HTTP 206 Range server.

---

## 💻 Python CLI Reference (`--help`)

```text
usage: grabber.py [-h] [-l LINKS_FILE] [-o OUT] [--threads THREADS]
                  [--limit LIMIT] [--no-videos] [--verify]
                  [urls ...]

positional arguments:
  urls                  Optional URL(s) to download directly.

options:
  -h, --help            show this help message and exit
  -l, --links, --file LINKS_FILE
                        Path to line-separated links file (default: links.txt)
  -o, --out OUT         Base output directory (default: current directory)
  --threads THREADS     Number of download threads (default: 4 for mobile)
  --limit, --max-mb LIMIT
                        Max download limit in Megabytes (safe for mobile data plans)
  --no-videos           Skip large video files (.mp4/.webm)
  --verify              Enable strict SSL verification (default: disabled)
```

---

## 📄 License
MIT License.
