# GameGrabberMobile 📱

[![Latest Release](https://img.shields.io/github/v/release/arnavlul/GameGrabberMobile?label=Latest%20Release&color=38BDF8)](https://github.com/arnavlul/GameGrabberMobile/releases/latest)
[![Download APK](https://img.shields.io/badge/Download-GameGrabber.apk%20(~45KB)-10B981?style=for-the-badge&logo=android&logoColor=white)](https://github.com/arnavlul/GameGrabberMobile/releases/latest/download/GameGrabber.apk)

Universal offline web game downloader for Android (Native APK & Python CLI). Download and preserve HTML, Twine, and browser games directly on mobile devices with batch URL processing, HTTP Range resuming, and zero heavy browser dependencies.

---

## 📦 Download & Installation

### Option 1: Native Android App (Recommended)
👉 **[Download GameGrabber.apk (Latest v1.0.0)](https://github.com/arnavlul/GameGrabberMobile/releases/latest/download/GameGrabber.apk)**
1. Tap the download link on your phone.
2. Open the downloaded `GameGrabber.apk` and tap **Install**.
3. Open the app, paste your links, and download games straight to `Downloads/GameGrabber/`!

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

## 🌟 Key Features

- **Universal Portal & Iframe Resolution:** Paste any portal URL or direct game link. It automatically resolves game iframes and CDN content while filtering out ad trackers.
- **Deep Story Traversal (Twine / SugarCube):** Traverses passage graphs sequentially so early scenes and dialogue assets download first.
- **Resumable Downloads (RFC 7233):** Interrupted downloads cleanly resume using `.part` files and HTTP Range headers instead of restarting from scratch.
- **Self-Healing Startup:** Automatically inspects files on resume, purging 0-byte or captive-portal network block files.
- **Data Protection:**
  - **Data Limit (`--limit <MB>`):** Automatically pauses when your mobile data quota is reached.
  - **Skip Videos (`--no-videos`):** Downloads dialogue, audio, and images while skipping heavy `.mp4`/`.webm` videos.

---

## 📲 How to Install & Use the APK

### 1. Installation
1. Download or copy **`GameGrabber.apk`** to your Android device.
2. Open your file manager, tap `GameGrabber.apk`, and select **Install**.

### 2. Downloading Games
1. Open **GameGrabber**.
2. Paste your game links into the text box (one link per line).
3. *(Optional)* Select **Skip Videos** for low-data mode, or set a **Data Limit (MB)**.
4. Tap **START DOWNLOAD**.
5. Live progress and file logs appear in real time in the console.

### 3. Playing Offline
Once downloaded, games are saved in:
```text
/sdcard/Download/GameGrabber/<Game_Name>-<Version>/
```
Simply open `index.html` in Chrome, Firefox, or any offline file viewer to play offline!

---

## 💻 Python CLI Alternative (Termux / PC)

### Setup in Termux
```bash
pkg update && pkg install python git
git clone https://github.com/arnavlul/GameGrabberMobile.git
cd GameGrabberMobile
pip install -r requirements.txt
```

### Usage
Add URLs to `links.txt` (one per line) and run:
```bash
python grabber.py
```

Or pass links directly via CLI:
```bash
# Basic run:
python grabber.py "https://game-portal.com/games/space-adventure"

# Limit data to 200 MB:
python grabber.py --limit 200

# Low-data mode (skip videos):
python grabber.py --no-videos
```

### CLI Reference (`--help`)
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

## 📁 Output Directory Structure
```text
GameGrabber/
└── Space_Adventure-v1.0/
    ├── index.html
    ├── Images/
    └── Audio/
```

---

## 📄 License
MIT License.
