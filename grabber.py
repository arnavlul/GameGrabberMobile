#!/usr/bin/env python3
"""
GameGrabberMobile - Lightweight Offline Browser & HTML Game Downloader
================================================================================
Designed specifically for mobile devices (Termux, Pydroid, iSH, etc.) and PCs.
Pure Python: No browser/Playwright required!

Features:
  * Reads line-separated game links from a file (links.txt) or CLI arguments.
  * Resolves game portal embeds (iframes, direct CDN URLs).
  * Automatically detects game metadata (Title & Version) -> Clean '<Name>-<Version>' folders.
  * Deep HTML & SugarCube / Twine story traversal (prioritizes story order so early scenes download first).
  * Resumable downloads via HTTP Range requests and atomic '.part' files.
  * Self-healing: purges fake captive-portal / firewall blocks on resume.
  * Bandwidth protection: '--limit <MB>' to stop at data cap, '--no-videos' for low data mode.
================================================================================
"""

import os
import sys
import re
import time
import argparse
from urllib.parse import urlparse, urljoin, unquote, quote
from concurrent.futures import ThreadPoolExecutor, as_completed

# Optional third-party packages with graceful fallbacks
try:
    import requests
    from requests.adapters import HTTPAdapter
    from urllib3.util.retry import Retry
    import urllib3
    urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)
except ImportError:
    print("[!] Error: 'requests' is required. Please install it with: pip install requests")
    sys.exit(1)

try:
    from bs4 import BeautifulSoup
except ImportError:
    print("[!] Error: 'beautifulsoup4' is required. Please install it with: pip install beautifulsoup4")
    sys.exit(1)

try:
    from tqdm import tqdm
    HAS_TQDM = True
except ImportError:
    HAS_TQDM = False

# ==============================================================================
# CONFIGURATION & CONSTANTS
# ==============================================================================

DEFAULT_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/124.0.0.0 Mobile Safari/537.36"
    ),
    "Accept": "*/*",
    "Accept-Language": "en-US,en;q=0.9",
}

PORTAL_SIGNATURES = [
    b"web block", b"access denied", b"blocked site", b"sophos",
    b"sonicwall", b"fortigate", b"captive portal", b"login required"
]

VIDEO_EXTENSIONS = ('.mp4', '.webm', '.avi', '.mov', '.mkv', '.m4v')
MEDIA_EXTENSIONS = (
    '.png', '.jpg', '.jpeg', '.gif', '.webp', '.svg', '.bmp', '.ico',
    '.mp4', '.webm', '.ogg', '.ogv', '.mp3', '.wav', '.flac', '.m4a',
    '.js', '.css', '.wasm', '.data', '.json', '.woff', '.woff2', '.ttf', '.otf'
)

# ==============================================================================
# HTTP SESSION BUILDER
# ==============================================================================

def build_session(verify_ssl=False, timeout=15):
    """Creates a requests.Session with connection pooling and automatic retries."""
    session = requests.Session()
    session.verify = verify_ssl
    session.headers.update(DEFAULT_HEADERS)

    retries = Retry(
        total=5,
        backoff_factor=1.0,
        status_forcelist=[429, 500, 502, 503, 504],
        raise_on_status=False
    )
    adapter = HTTPAdapter(max_retries=retries, pool_connections=16, pool_maxsize=16)
    session.mount("http://", adapter)
    session.mount("https://", adapter)
    return session

# ==============================================================================
# PORTAL & IFRAME RESOLVER
# ==============================================================================

AD_DOMAINS = [
    "doubleclick", "googlesyndication", "googleads", "facebook",
    "analytics", "disqus", "twitter", "adnxs", "popads", "adservice"
]

class PortalResolver:
    """Universal game portal & iframe resolver for web game portals and direct embeds."""

    @staticmethod
    def resolve_game_url(session, url, timeout=15):
        clean_url = url.split("?")[0].split("#")[0].lower()
        if clean_url.endswith("/index.html") or clean_url.endswith("/game.html"):
            return url

        print(f"[*] Inspecting entry URL: {url} ...")
        try:
            resp = session.get(url, timeout=timeout)
            resp.raise_for_status()
            html = resp.text

            # If page is already a Twine / SugarCube story or direct HTML5 game, use it
            if "<tw-storydata" in html or "sugarcube" in html.lower() or "<canvas" in html.lower():
                return url

            soup = BeautifulSoup(html, "html.parser")

            # 1. Search for game iframes
            candidates = []
            for ifr in soup.find_all("iframe"):
                src = ifr.get("src") or ifr.get("data-src")
                if not src or src.startswith(("javascript:", "about:")):
                    continue
                if any(ad in src.lower() for ad in AD_DOMAINS):
                    continue

                full_src = urljoin(url, src)
                lower_full = full_src.lower()
                if any(kw in lower_full for kw in ["fgn.cdn", "discretehosting", "flashes", "serverable", "hwcdn.net", "html5", "index.html", "game", "embed"]):
                    print(f"  [+] Found direct game frame: {full_src}")
                    return full_src
                candidates.append(full_src)

            # 2. Search regex inside page source for CDN embeds
            cdn_matches = re.findall(
                r'https?://[^\s"\'<>]*(?:fgn\.cdn|discretehosting|serverable|hwcdn)[^\s"\'<>]*index\.html[^\s"\'<>]*',
                html
            )
            if cdn_matches:
                print(f"  [+] Found CDN embed URL in page script: {cdn_matches[0]}")
                return cdn_matches[0]

            if candidates:
                print(f"  [+] Using candidate frame: {candidates[0]}")
                return candidates[0]

        except Exception as e:
            print(f"  [!] Note while resolving URL: {e}")

        return url

# ==============================================================================
# METADATA & PATH UTILITIES
# ==============================================================================

def sanitize_filename(name):
    """Sanitizes strings for safe cross-platform folder/file names."""
    name = re.sub(r'[\\/*?:"<>|]', "", name)
    name = re.sub(r'[\r\n\t]+', " ", name)
    name = re.sub(r'\s+', " ", name).strip()
    return name

def extract_game_metadata(html_content, fallback_url):
    """Extracts clean game title and version from HTML entry page."""
    soup = BeautifulSoup(html_content, "html.parser")
    raw_title = ""

    if soup.title and soup.title.string:
        raw_title = soup.title.string.strip()

    # Fluff removal
    fluff_patterns = [
        r'[-|]\s*Gamcore.*$',
        r'[-|]\s*Play\s+on\s+Gamcore.*$',
        r'[-|]\s*HTML.*Game.*$',
        r'[-|]\s*Porn\s*Games.*$',
        r'[-|]\s*Free.*$',
        r'[-|]\s*Download.*$'
    ]
    for pat in fluff_patterns:
        raw_title = re.sub(pat, "", raw_title, flags=re.I).strip()

    version = None
    v_match = re.search(r'[\(\[\{]?(?:v(?:er)?\.?\s*|build\s*)([0-9]+(?:\.[0-9a-zA-Z_\-]+)*)[\)\]\}]?', raw_title, re.I)
    if v_match:
        version = "v" + v_match.group(1).lstrip("vV")
        clean_title = raw_title[:v_match.start()] + raw_title[v_match.end():]
        clean_title = re.sub(r'[\(\[\{\)\]\}]', "", clean_title).strip(" -_")
    else:
        # Search HTML body/scripts for version info
        text_snip = html_content[:20000]
        v_body_match = re.search(r'(?:version|ver|build)\s*[:=]\s*["\']?v?([0-9]+(?:\.[0-9a-zA-Z_\-]+)+)', text_snip, re.I)
        if v_body_match:
            version = "v" + v_body_match.group(1)
        else:
            version = "v1.0"
        clean_title = raw_title.strip(" -_")

    if not clean_title or clean_title.lower() in ["untitled", "game", "index"]:
        # Fallback to URL path slug
        path_slug = fallback_url.split("?")[0].rstrip("/").split("/")
        candidate = path_slug[-1]
        if candidate.lower().endswith((".html", ".htm", ".php")) and len(path_slug) > 1:
            candidate = path_slug[-2]
        clean_title = candidate.replace("-", " ").replace("_", " ").title()

    clean_title = sanitize_filename(clean_title)
    folder_name = f"{clean_title.replace(' ', '_')}-{version}"
    return clean_title, version, folder_name

def clean_relative_path(path):
    """
    Sanitizes relative asset paths.
    Maintains support for spaces and parentheses while removing harmful chars.
    """
    path = path.strip('\'" \t\r\n')
    # Reject shell/injection/formatting tokens
    if any(c in path for c in ['<', '>', '$', '{', '}', ';', '+', '*', '\n', '\r', '|', '"']):
        return None
    path = path.split('?')[0].split('#')[0]
    path = path.replace('\\', '/').lstrip('/')
    if not path or '..' in path:
        return None
    # Filter dummy fallbacks
    if path.lower() in ['movie.ogg', 'movie.mp4', 'dummy.mp4']:
        return None
    return path

# ==============================================================================
# HTML & TWINE ASSET EXTRACTOR
# ==============================================================================

class HTMLAssetExtractor:
    """Discovers all static & dynamic assets in HTML / SugarCube games."""

    @staticmethod
    def extract_assets(html_content, base_url):
        discovered = []
        seen = set()

        def add_ref(ref):
            if not ref:
                return
            ref = ref.strip()
            if ref.startswith(('data:', 'blob:', 'javascript:', 'mailto:', '#')):
                return
            cleaned = clean_relative_path(unquote(ref))
            if cleaned and cleaned not in seen:
                # Check extension
                ext = '.' + cleaned.rsplit('.', 1)[-1].lower() if '.' in cleaned else ''
                if ext in MEDIA_EXTENSIONS or '/' in cleaned:
                    seen.add(cleaned)
                    discovered.append(cleaned)

        soup = BeautifulSoup(html_content, "html.parser")

        # 1. Standard HTML tags
        tag_attrs = [
            ('script', 'src'),
            ('link', 'href'),
            ('img', 'src'),
            ('img', 'data-src'),
            ('video', 'src'),
            ('video', 'poster'),
            ('audio', 'src'),
            ('source', 'src'),
            ('track', 'src')
        ]
        for tag, attr in tag_attrs:
            for el in soup.find_all(tag):
                add_ref(el.get(attr))

        # 2. Twine / SugarCube Story Passages
        story_data = soup.find("tw-storydata")
        if story_data:
            print("[*] Twine / SugarCube story detected. Resolving passage sequence...")
            passages = story_data.find_all("tw-passagedata")
            passage_map = {p.get("pid"): p for p in passages}
            name_map = {p.get("name"): p for p in passages}

            start_pid = story_data.get("startnode")
            visited_pids = set()
            queue = []
            if start_pid and start_pid in passage_map:
                queue.append(start_pid)

            # Traverse passages BFS to prioritize earlier story scenes
            while queue:
                pid = queue.pop(0)
                if pid in visited_pids:
                    continue
                visited_pids.add(pid)
                p = passage_map.get(pid)
                if not p:
                    continue

                text = p.get_text()
                # Image syntax [img[...]]
                for m in re.finditer(r'\[img\[(?:[^\|\]]*\|)?([^\]]+)\]\]', text, re.I):
                    add_ref(m.group(1))

                # Media attributes inside passage HTML
                for m in re.finditer(r'(?:src|archive|poster)\s*=\s*["\']([^"\']+)["\']', text, re.I):
                    add_ref(m.group(1))

                # Links [[target]]
                for m in re.finditer(r'\[\[(?:[^\|\]]*\|)?([^\]]+)\]\]', text):
                    target_name = m.group(1).strip()
                    target_p = name_map.get(target_name)
                    if target_p:
                        t_pid = target_p.get("pid")
                        if t_pid and t_pid not in visited_pids:
                            queue.append(t_pid)

            # Also check unvisited passages (orphan branches, secret scenes)
            for p in passages:
                text = p.get_text()
                for m in re.finditer(r'\[img\[(?:[^\|\]]*\|)?([^\]]+)\]\]', text, re.I):
                    add_ref(m.group(1))
                for m in re.finditer(r'(?:src|archive|poster)\s*=\s*["\']([^"\']+)["\']', text, re.I):
                    add_ref(m.group(1))

        # 3. Global Regex extraction for CSS url() and JS paths
        for m in re.finditer(r'url\s*\(\s*["\']?([^"\'\)]+)["\']?\s*\)', html_content, re.I):
            add_ref(m.group(1))

        for m in re.finditer(r'["\']((?:Images|Videos|Audio|img|sound|music|assets)/[^"\'>\s]+?\.(?:png|jpe?g|gif|webp|mp4|webm|ogg|mp3|wav))["\']', html_content, re.I):
            add_ref(m.group(1))

        return discovered

# ==============================================================================
# DOWNLOAD WORKER & SELF-HEALING ENGINE
# ==============================================================================

class DownloadWorker:
    """Handles thread-safe downloads with Range resume and bandwidth cap."""

    def __init__(self, session, base_url, target_folder, limit_mb=None, no_videos=False):
        self.session = session
        self.base_url = base_url
        self.target_folder = target_folder
        self.limit_bytes = int(limit_mb * 1024 * 1024) if limit_mb else None
        self.no_videos = no_videos
        self.total_downloaded = 0
        self.limit_reached = False

    def is_corrupt(self, filepath):
        """Checks if a small file is actually an HTML captive portal / firewall page."""
        try:
            sz = os.path.getsize(filepath)
            if sz == 0:
                return True
            if sz < 4096:
                with open(filepath, "rb") as f:
                    header = f.read(1024).lower()
                if any(sig in header for sig in PORTAL_SIGNATURES) or b"<!doctype html" in header:
                    return True
        except Exception:
            pass
        return False

    def purge_corrupt_files(self):
        """Self-healing scan before resuming download."""
        purged = 0
        for root, _, files in os.walk(self.target_folder):
            for fname in files:
                if fname in ["index.html", "game_meta.json"]:
                    continue
                ext = '.' + fname.rsplit('.', 1)[-1].lower() if '.' in fname else ''
                if ext in MEDIA_EXTENSIONS and ext not in ['.json', '.txt']:
                    fpath = os.path.join(root, fname)
                    if self.is_corrupt(fpath):
                        try:
                            os.remove(fpath)
                            purged += 1
                        except Exception:
                            pass
        if purged > 0:
            print(f"[*] Self-healing: Purged {purged} corrupt / captive-portal files.")

    def download_asset(self, rel_path, pbar=None):
        """Downloads a single asset with Range resumption."""
        if self.limit_reached:
            return (rel_path, False, "Limit reached")

        if self.no_videos and rel_path.lower().endswith(VIDEO_EXTENSIONS):
            return (rel_path, True, "Skipped (video)")

        local_path = os.path.join(self.target_folder, os.path.normpath(rel_path))
        part_path = local_path + ".part"

        # Check existing complete file
        if os.path.exists(local_path):
            if not self.is_corrupt(local_path):
                if pbar:
                    pbar.update(1)
                return (rel_path, True, "Cached")
            else:
                try:
                    os.remove(local_path)
                except Exception:
                    pass

        os.makedirs(os.path.dirname(local_path), exist_ok=True)

        # Range Resume calculation
        existing_size = 0
        if os.path.exists(part_path):
            existing_size = os.path.getsize(part_path)

        safe_rel = quote(unquote(rel_path))
        download_url = urljoin(self.base_url, safe_rel)

        headers = {}
        if existing_size > 0:
            headers["Range"] = f"bytes={existing_size}-"

        try:
            resp = self.session.get(download_url, headers=headers, stream=True, timeout=20)

            # Server doesn't support Range or returned full file
            if resp.status_code == 200:
                mode = "wb"
                existing_size = 0
            elif resp.status_code == 206:
                mode = "ab"
            elif resp.status_code == 416: # Range not satisfiable (file already complete)
                if os.path.exists(part_path):
                    os.replace(part_path, local_path)
                if pbar:
                    pbar.update(1)
                return (rel_path, True, "Completed")
            elif resp.status_code == 404:
                if pbar:
                    pbar.update(1)
                return (rel_path, False, "404 Not Found")
            else:
                resp.raise_for_status()

            with open(part_path, mode) as f:
                for chunk in resp.iter_content(chunk_size=65536):
                    if not chunk:
                        continue
                    f.write(chunk)
                    self.total_downloaded += len(chunk)

                    if self.limit_bytes and self.total_downloaded >= self.limit_bytes:
                        self.limit_reached = True
                        print(f"\n[!] Data limit reached ({self.limit_bytes / (1024*1024):.1f} MB). Stopping cleanly.")
                        break

            # Finished full file
            if not self.limit_reached:
                if os.path.exists(part_path):
                    if not self.is_corrupt(part_path):
                        os.replace(part_path, local_path)
                    else:
                        os.remove(part_path)
                        if pbar:
                            pbar.update(1)
                        return (rel_path, False, "Corrupt stream")

            if pbar:
                pbar.update(1)
            return (rel_path, True, "Downloaded")

        except Exception as e:
            if pbar:
                pbar.update(1)
            return (rel_path, False, str(e))

# ==============================================================================
# MAIN GAME SCRAPER ORCHESTRATOR
# ==============================================================================

def grab_game(url, base_out_dir, threads=4, limit_mb=None, no_videos=False, verify_ssl=False):
    """Processes a single game URL from resolution to download completion."""
    session = build_session(verify_ssl=verify_ssl)

    print("\n" + "=" * 70)
    print(f"[*] Processing: {url}")
    print("=" * 70)

    # 1. Resolve portal to direct game entry point
    game_url = PortalResolver.resolve_game_url(session, url)

    # 2. Fetch Entry HTML
    print(f"[*] Fetching entry HTML: {game_url} ...")
    try:
        resp = session.get(game_url, timeout=20)
        resp.raise_for_status()
        html_content = resp.text
    except Exception as e:
        print(f"[!] Failed to fetch entry HTML: {e}")
        return False

    # 3. Detect Title & Folder
    clean_title, version, folder_name = extract_game_metadata(html_content, game_url)
    target_folder = os.path.join(base_out_dir, folder_name)
    os.makedirs(target_folder, exist_ok=True)

    print(f"[+] Game Title : {clean_title} ({version})")
    print(f"[+] Target Dir : {target_folder}")

    # 4. Save Pristine Entry HTML
    index_path = os.path.join(target_folder, "index.html")
    with open(index_path, "w", encoding="utf-8") as f:
        f.write(html_content)
    print("[+] Saved pristine index.html")

    # 5. Extract Assets
    assets = HTMLAssetExtractor.extract_assets(html_content, game_url)
    print(f"[+] Discovered {len(assets)} media/script assets.")

    if not assets:
        print("[*] No extra assets discovered. Game is fully self-contained in index.html.")
        return True

    # 6. Initialize Downloader & Self-Healing
    worker = DownloadWorker(
        session=session,
        base_url=game_url,
        target_folder=target_folder,
        limit_mb=limit_mb,
        no_videos=no_videos
    )
    worker.purge_corrupt_files()

    # 7. Multi-threaded Download
    print(f"[*] Downloading assets with {threads} parallel workers...")
    start_time = time.time()

    if HAS_TQDM:
        with tqdm(total=len(assets), unit="file", desc="Downloading", ncols=80) as pbar:
            with ThreadPoolExecutor(max_workers=threads) as executor:
                futures = [executor.submit(worker.download_asset, asset, pbar) for asset in assets]
                for future in as_completed(futures):
                    future.result()
    else:
        # Fallback console counter
        completed = 0
        total = len(assets)
        with ThreadPoolExecutor(max_workers=threads) as executor:
            futures = [executor.submit(worker.download_asset, asset) for asset in assets]
            for future in as_completed(futures):
                completed += 1
                if completed % 5 == 0 or completed == total:
                    print(f"  [{completed}/{total}] files processed...")

    elapsed = time.time() - start_time
    mb_down = worker.total_downloaded / (1024 * 1024)
    print(f"[+] Download complete: {mb_down:.1f} MB downloaded in {elapsed:.1f}s.")

    if worker.limit_reached:
        print("[*] Paused at download quota. Run again anytime to resume where you left off!")
        return False

    return True

# ==============================================================================
# CLI & BATCH LINK PARSER
# ==============================================================================

def load_links_from_file(file_path):
    """Loads clean, non-empty, non-comment lines from a text file."""
    if not os.path.exists(file_path):
        return []
    links = []
    with open(file_path, "r", encoding="utf-8") as f:
        for line in f:
            clean = line.strip()
            if clean and not clean.startswith("#"):
                links.append(clean)
    return links

def mark_link_completed(link, completed_file="completed_links.txt"):
    """Appends finished link to a tracker file."""
    try:
        with open(completed_file, "a", encoding="utf-8") as f:
            f.write(link + "\n")
    except Exception:
        pass

def main():
    parser = argparse.ArgumentParser(
        description="GameGrabberMobile: Lightweight offline game scraper for phones and PCs.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Download all games listed in links.txt:
  python grabber.py

  # Download games with a custom links file:
  python grabber.py -l my_games.txt

  # Download specific link directly via CLI:
  python grabber.py "https://game-portal.com/games/space-adventure"

  # Stop after 250 MB (safe for mobile data):
  python grabber.py --limit 250

  # Download text & images only, skipping videos:
  python grabber.py --no-videos
"""
    )
    parser.add_argument("urls", nargs="*", help="Optional URL(s) to download directly.")
    parser.add_argument("-l", "--links", "--file", dest="links_file", default="links.txt",
                        help="Path to line-separated links file (default: links.txt)")
    parser.add_argument("-o", "--out", default=".", help="Base output directory (default: current directory)")
    parser.add_argument("--threads", type=int, default=4, help="Number of download threads (default: 4 for mobile)")
    parser.add_argument("--limit", "--max-mb", dest="limit", type=float, default=None,
                        help="Max download limit in Megabytes (safe for mobile data plans)")
    parser.add_argument("--no-videos", action="store_true", help="Skip large video files (.mp4/.webm)")
    parser.add_argument("--verify", action="store_true", help="Enable strict SSL verification (default: disabled)")

    args = parser.parse_args()

    # Determine links list
    links = []
    if args.urls:
        links.extend(args.urls)
    elif os.path.exists(args.links_file):
        links = load_links_from_file(args.links_file)

    if not links:
        print(f"[!] No URLs provided and '{args.links_file}' is empty or not found.")
        print("[*] Please add game URLs to links.txt (one per line) or pass a URL directly.")
        sys.exit(1)

    print(f"[*] Found {len(links)} link(s) to process.")
    for idx, link in enumerate(links, start=1):
        print(f"\n>>> Starting Job {idx}/{len(links)}: {link}")
        success = grab_game(
            url=link,
            base_out_dir=args.out,
            threads=args.threads,
            limit_mb=args.limit,
            no_videos=args.no_videos,
            verify_ssl=args.verify
        )
        if success:
            mark_link_completed(link)

    print("\n[✓] All jobs processed!")

if __name__ == "__main__":
    main()
