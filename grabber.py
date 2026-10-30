#!/usr/bin/env python3
"""
GameGrabberMobile - Lightweight Offline Browser & HTML Game Downloader
================================================================================
Designed specifically for mobile devices (Termux, Pydroid, iSH, etc.) and PCs.
Pure Python: No browser/Playwright required!

Features:
  * Reads line-separated game links from a file (links.txt) or CLI arguments.
  * Resolves game portal embeds (iframes, direct CDN URLs) without duplicate fetches.
  * Automatically detects game metadata (Title & Version) -> Clean '<Name>-<Version>' folders.
  * Deep HTML & SugarCube / Twine story traversal (prioritizes story order so early scenes download first).
  * Secondary script/stylesheet asset discovery for multi-file HTML5 games.
  * Resumable downloads via HTTP Range requests, atomic '.part' files, and Content-Length truncation checks.
  * Self-healing: purges fake captive-portal / firewall blocks on resume.
  * Final disk verification audit ensuring 100% file integrity.
  * Bandwidth protection: '--limit <MB>' to stop at data cap, '--no-videos' for low data mode.
================================================================================
"""

import os
import sys
import re
import time
import html
import argparse
import threading
from urllib.parse import urlparse, urljoin, unquote, quote
from concurrent.futures import ThreadPoolExecutor, as_completed

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
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/124.0.0.0 Mobile Safari/537.36"
    ),
    "Accept": "*/*",
    "Accept-Language": "en-US,en;q=0.9",
}

PORTAL_SIGNATURES = [
    b"web block", b"access denied", b"blocked site", b"sophos",
    b"sonicwall", b"fortigate", b"captive portal", b"login required",
    b"<!doctype html", b"<html", b"<error><code"
]

VIDEO_EXTENSIONS = ('.mp4', '.webm', '.avi', '.mov', '.mkv', '.m4v', '.ogv')
MEDIA_EXTENSIONS = (
    '.png', '.jpg', '.jpeg', '.gif', '.webp', '.svg', '.bmp', '.ico', '.avif',
    '.mp4', '.webm', '.ogg', '.ogv', '.mp3', '.wav', '.flac', '.m4a', '.aac', '.mov', '.mkv', '.avi',
    '.js', '.css', '.wasm', '.data', '.json', '.woff', '.woff2', '.ttf', '.otf', '.eot',
    '.rpgmvp', '.rpgmvo', '.rpgmvm', '.png_', '.ogg_', '.m4a_'
)

AD_DOMAINS = [
    "doubleclick", "googlesyndication", "googleads", "facebook",
    "analytics", "disqus", "twitter", "adnxs", "popads", "adservice",
    "exoclick", "juicyads", "trafficjunky", "realsrv", "magsrv"
]

# ==============================================================================
# HTTP SESSION & URL UTILITIES
# ==============================================================================

def build_session(verify_ssl=False):
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
    adapter = HTTPAdapter(max_retries=retries, pool_connections=20, pool_maxsize=20)
    session.mount("http://", adapter)
    session.mount("https://", adapter)
    return session

def normalize_base_game_url(url):
    """Ensures directory URLs without a file extension end with '/' for proper urljoin resolution."""
    if not url:
        return ""
    url = url.strip()
    parts = url.split("?", 1)
    path_part = parts[0]
    query_part = ("?" + parts[1]) if len(parts) > 1 else ""

    parsed = urlparse(path_part)
    path = parsed.path or ""
    if not path:
        path_part += "/"
    elif not path.endswith("/"):
        last_seg = path.rsplit("/", 1)[-1]
        if "." not in last_seg:
            path_part += "/"
    return path_part + query_part

def encode_url_path(rel_path):
    """Encodes relative asset path while keeping slashes and standard safe path characters intact."""
    decoded = unquote(rel_path.replace("+", "%2B"))
    return quote(decoded, safe="/()'!~,-_.@")

# ==============================================================================
# PORTAL & IFRAME RESOLVER
# ==============================================================================

class PortalResolver:
    """Universal game portal & iframe resolver for web game portals and direct embeds."""

    @staticmethod
    def resolve(session, url, timeout=20):
        """
        Returns (resolved_game_url, portal_title, portal_version, pre_fetched_html).
        Avoids fetching the entry HTML twice if initial URL is already the game page.
        """
        clean_url = url.split("?")[0].split("#")[0].lower()
        if clean_url.endswith(("/index.html", "/game.html", ".html", ".htm")):
            return normalize_base_game_url(url), None, None, None

        print(f"[*] Inspecting entry URL: {url} ...")
        try:
            resp = session.get(url, timeout=timeout)
            resp.raise_for_status()
            final_url = normalize_base_game_url(resp.url)
            page_html = resp.text

            p_title, p_ver, _ = extract_game_metadata(page_html, final_url)

            lower_html = page_html.lower()
            if any(sig in lower_html for sig in [
                "<tw-storydata", "sugarcube", 'id="store-area"', 'id="storearea"',
                "tiddler=", "renpy.js", "renpy.wasm", "game.zip",
                "c2runtime.js", "c3runtime.js", "rpg_core.js", "rmmz_core.js"
            ]):
                return final_url, p_title, p_ver, page_html

            # Check for <meta http-equiv="refresh" content="...url=...">
            m_ref = re.search(r'<meta[^>]+http-equiv=[\'"]?refresh[\'"]?[^>]+content=[\'"]?[^\'"]*url=([^\'"\s>]+)', page_html, re.I)
            if m_ref:
                redir_url = urljoin(final_url, html.unescape(m_ref.group(1)).strip())
                return PortalResolver.resolve(session, redir_url, timeout)

            # Check for window.location = "..."
            m_js = re.search(r'(?:window\.)?location(?:\.href)?\s*=\s*[\'"]([^\'"]+\.(?:html?|php)[^\'"]*)[\'"]', page_html, re.I)
            if m_js:
                js_url = urljoin(final_url, html.unescape(m_js.group(1)).strip())
                return PortalResolver.resolve(session, js_url, timeout)

            soup = BeautifulSoup(page_html, "html.parser")

            # 1. Search for game iframes / embeds
            candidates = []
            for tag in soup.find_all(["iframe", "embed", "frame"]):
                src = tag.get("src") or tag.get("data-src") or tag.get("data-lazy-src")
                if not src:
                    continue
                src = html.unescape(src).strip()
                if not src or src.startswith(("javascript:", "about:")):
                    continue
                if any(ad in src.lower() for ad in AD_DOMAINS):
                    continue

                full_src = urljoin(final_url, src)
                lower_full = full_src.lower()
                if any(kw in lower_full for kw in [
                    "fgn.cdn", "discretehosting", "flashes", "serverable",
                    "hwcdn.net", "itch.zone", "ungrounded.net", "html5",
                    "index.html", "game", "embed"
                ]):
                    print(f"  [+] Found direct game frame: {full_src}")
                    return normalize_base_game_url(full_src), p_title, p_ver, None
                candidates.append(full_src)

            # 2. Search regex inside page source for CDN embeds
            cdn_matches = re.findall(
                r'(?:https?:)?//[^\s"\'<>]*(?:fgn\.cdn|discretehosting|serverable|hwcdn\.net|itch\.zone)[^\s"\'<>]*index\.html[^\s"\'<>]*',
                page_html, re.I
            )
            if cdn_matches:
                resolved_cdn = urljoin(final_url, html.unescape(cdn_matches[0]))
                print(f"  [+] Found CDN embed URL in page script: {resolved_cdn}")
                return normalize_base_game_url(resolved_cdn), p_title, p_ver, None

            if candidates:
                print(f"  [+] Using candidate frame: {candidates[0]}")
                return normalize_base_game_url(candidates[0]), p_title, p_ver, None

            return final_url, p_title, p_ver, page_html

        except Exception as e:
            print(f"  [!] Note while resolving URL: {e}")

        return normalize_base_game_url(url), None, None, None

# ==============================================================================
# METADATA & PATH UTILITIES
# ==============================================================================

def sanitize_filename(name):
    """Sanitizes strings for safe cross-platform folder/file names."""
    name = re.sub(r'[\\/*?:"<>|]', "", name)
    name = re.sub(r'[\r\n\t]+', " ", name)
    name = re.sub(r'\s+', " ", name).strip(" -_.")
    return name

def extract_game_metadata(html_content, fallback_url, portal_title=None, portal_version=None):
    """Extracts clean game title and version from HTML entry page."""
    raw_title = ""
    m_title = re.search(r"<title[^>]*>(.*?)</title>", html_content, re.I | re.DOTALL)
    if m_title:
        raw_title = html.unescape(m_title.group(1)).strip()

    if (not raw_title or raw_title.lower() in ["untitled", "index", "twine"]) and portal_title:
        raw_title = html.unescape(portal_title).strip()

    fluff_patterns = [
        r'[-|–]\s*(?:Play\s+)?(?:Free\s+)?Online(?:\s+Game)?.*$',
        r'[-|–]\s*Gamcore.*$',
        r'[-|–]\s*Play\s+on\s+Gamcore.*$',
        r'[-|–]\s*HTML.*Game.*$',
        r'[-|–]\s*Porn\s*Games.*$',
        r'[-|–]\s*Free.*$',
        r'[-|–]\s*Download.*$',
        r'\|.*$'
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
        clean_title = raw_title.strip(" -_")

    if not version and portal_version and portal_version != "v1.0":
        version = portal_version

    # Search full HTML content for version variables or JSON version fields
    if not version and html_content:
        m_var = re.search(
            r'(?:version|game_version|gameVersion|build_num)\s*[:=]\s*["\']v?([0-9]+(?:\.[0-9a-zA-Z_\-]+)*)["\']',
            html_content, re.I
        )
        if m_var:
            version = "v" + m_var.group(1).lstrip("vV")
        if not version:
            m_pkg = re.search(r'"version"\s*:\s*"v?([0-9]+(?:\.[0-9a-zA-Z_\-]+)*)"', html_content)
            if m_pkg:
                version = "v" + m_pkg.group(1).lstrip("vV")

    # Check URL slug for version pattern
    if not version and fallback_url:
        u_match = re.search(r'[-_](?:v|ver)?([0-9]+(?:\.[0-9a-zA-Z_\-]+)+)', fallback_url, re.I)
        if u_match:
            version = "v" + u_match.group(1).lstrip("vV")

    if not version:
        version = "v1.0"

    clean_title = sanitize_filename(clean_title)
    if not clean_title or clean_title.lower() in ["untitled", "untitled game", "gamcore", "game", "index"]:
        path_slug = fallback_url.split("?")[0].rstrip("/").split("/")
        candidate = re.sub(r'\.(?:html?|php)$', '', path_slug[-1], flags=re.I)
        if candidate.lower() == "index" and len(path_slug) > 1:
            candidate = path_slug[-2]
        clean_title = sanitize_filename(candidate.replace("-", " ").replace("_", " ").title())

    folder_name = f"{clean_title.replace(' ', '_')}-{version}"
    return clean_title, version, folder_name

def clean_relative_path(path):
    """
    Sanitizes relative asset paths.
    Preserves spaces, parentheses, and '+' signs while rejecting invalid tokens and dummy placeholders.
    """
    if not path:
        return None
    path = html.unescape(str(path)).strip('\'" \t\r\n')
    if not path:
        return None

    lower_start = path.lower()
    if lower_start.startswith(('data:', 'blob:', 'javascript:', 'mailto:', '#', 'about:', 'http://', 'https://', '//')):
        return None

    if any(c in path for c in ['<', '>', '$', '{', '}', ';', '*', '\n', '\r', '|', '"']):
        return None

    path = path.split('?')[0].split('#')[0].strip()
    path = path.replace('\\', '/')
    if not path:
        return None

    # Safe percent-decoding preserving '+'
    path = unquote(path.replace('+', '%2B')).replace('\\', '/').strip()
    norm_segs = []
    for seg in path.split('/'):
        if not seg or seg == '.':
            continue
        if seg == '..':
            if norm_segs:
                norm_segs.pop()
        else:
            norm_segs.append(seg)
    if not norm_segs:
        return None
    path = '/'.join(norm_segs)

    lower = path.lower()
    if lower in ['movie.ogg', 'movie.mp4', 'dummy.mp4', 'video.ogg'] or lower.endswith(('/movie.ogg', '/movie.mp4')):
        return None

    if '.' not in lower:
        return None
    ext = '.' + lower.rsplit('.', 1)[-1]
    if ext not in MEDIA_EXTENSIONS:
        return None

    return path

# ==============================================================================
# HTML & TWINE ASSET EXTRACTOR
# ==============================================================================

class HTMLAssetExtractor:
    """Discovers all static & dynamic assets in HTML / SugarCube games in playable story order."""

    @staticmethod
    def extract_assets(html_content, base_url=""):
        discovered = []
        seen_lower = set()

        def add_ref(ref):
            cleaned = clean_relative_path(ref)
            if cleaned:
                key = cleaned.lower()
                if key not in seen_lower:
                    seen_lower.add(key)
                    discovered.append(cleaned)

        unescaped_html = html.unescape(html_content)

        image_prefix = "images/"
        sound_prefix = "sounds/"
        m_img = re.search(r'setup\.ImagePath\s*=\s*(?:setup\.Path\s*\+\s*)?[\'"]([^\'"]*)[\'"]', unescaped_html)
        if m_img:
            image_prefix = m_img.group(1).lstrip('/')
        m_snd = re.search(r'setup\.SoundPath\s*=\s*(?:setup\.Path\s*\+\s*)?[\'"]([^\'"]*)[\'"]', unescaped_html)
        if m_snd:
            sound_prefix = m_snd.group(1).lstrip('/')

        # 1. Extract top-level CSS url() and head/body tags before <tw-passagedata> first
        first_passage_idx = html_content.lower().find("<tw-passagedata")
        header_html = html_content[:first_passage_idx] if first_passage_idx > 0 else html_content
        for m in re.finditer(r'<(?:script|link|img|video|audio|source|track)[^>]+(?:src|href|poster|data-src)\s*=\s*["\']([^"\']+)["\']', header_html, re.I):
            add_ref(m.group(1))
        for m in re.finditer(r'url\s*\(\s*["\']?([^"\'\)]+)["\']?\s*\)', header_html, re.I):
            add_ref(m.group(1))

        # 2. Twine / SugarCube Story Passages
        story_match = re.search(r'<tw-storydata([^>]*)>(.*?)</tw-storydata>', html_content, re.I | re.DOTALL)
        if story_match:
            print("[*] Twine / SugarCube story detected. Resolving passage sequence...")
            story_attrs = story_match.group(1)
            story_body = story_match.group(2)

            start_m = re.search(r'\bstartnode\s*=\s*["\']([^"\']+)["\']', story_attrs, re.I)
            start_pid = start_m.group(1) if start_m else "1"

            pid_to_name = {}
            name_to_body = {}
            story_graph = {}

            for idx, pm in enumerate(re.finditer(r'<tw-passagedata([^>]*)>(.*?)</tw-passagedata>', story_body, re.I | re.DOTALL), 1):
                p_attrs = pm.group(1)
                p_raw_body = pm.group(2)

                pid_m = re.search(r'\bpid\s*=\s*["\']([^"\']*)["\']', p_attrs, re.I)
                name_m = re.search(r'\bname\s*=\s*["\']([^"\']*)["\']', p_attrs, re.I)
                if not name_m:
                    continue
                pid = html.unescape(pid_m.group(1)) if pid_m else str(idx)
                name = html.unescape(name_m.group(1))
                p_unescaped = html.unescape(p_raw_body)

                pid_to_name[pid] = name
                name_to_body[name] = p_unescaped

                links = []
                # [[Link|Target]], [[Link->Target]], [[Target<-Link]], [[Target][Setter]]
                for lm in re.finditer(r'\[\[([^\]]+)\]\]', p_unescaped):
                    inner = lm.group(1)
                    if inner.lower().startswith("img["):
                        continue
                    main_part = inner.split("][")[0]
                    if "|" in main_part:
                        target = main_part.split("|", 1)[1]
                    elif "->" in main_part:
                        target = main_part.rsplit("->", 1)[1]
                    elif "<-" in main_part:
                        target = main_part.split("<-", 1)[0]
                    else:
                        target = main_part
                    links.append(target.strip())

                for m_link in re.finditer(r'<<(?:link|button)\s+[\'"][^\'"]+[\'"](?:\s+[\'"]([^\'"]+)[\'"])?', p_unescaped):
                    if m_link.group(1):
                        links.append(m_link.group(1).strip())

                for m_data in re.finditer(r'data-passage\s*=\s*[\'"]([^\'"]+)[\'"]', p_unescaped, re.I):
                    links.append(m_data.group(1).strip())

                story_graph[name] = links

            ordered_passages = []
            visited_passages = set()

            special_passages = ["StoryInit", "StoryBanner", "StoryCaption", "StoryMenu", "PassageHeader", "PassageFooter"]
            for sp in special_passages:
                if sp in name_to_body and sp not in visited_passages:
                    visited_passages.add(sp)
                    ordered_passages.append(sp)

            start_name = pid_to_name.get(start_pid, "Lets Go")
            queue = []
            if start_name in name_to_body and start_name not in visited_passages:
                visited_passages.add(start_name)
                queue.append(start_name)

            while queue:
                curr = queue.pop(0)
                ordered_passages.append(curr)
                for nxt in story_graph.get(curr, []):
                    if nxt in name_to_body and nxt not in visited_passages:
                        visited_passages.add(nxt)
                        queue.append(nxt)

            for p_name in name_to_body:
                if p_name not in visited_passages:
                    visited_passages.add(p_name)
                    ordered_passages.append(p_name)

            # Extract assets per passage (Images/Audio first, then Videos)
            for p_name in ordered_passages:
                p_text = name_to_body[p_name]
                p_imgs = []
                p_vids = []

                def collect_ref(raw_ref):
                    c = clean_relative_path(raw_ref)
                    if c:
                        if c.lower().endswith(VIDEO_EXTENSIONS):
                            p_vids.append(c)
                        else:
                            p_imgs.append(c)

                for m in re.finditer(r'(?:src|href|poster|archive|data-src)\s*=\s*["\']([^"\']+)["\']', p_text, re.I):
                    collect_ref(m.group(1))

                for raw in re.findall(r'\[img\[(.*?)\]\]', p_text, re.I):
                    first_block = raw.split('][')[0]
                    if '|' in first_block:
                        first_block = first_block.split('|', 1)[1]
                    c = clean_relative_path(first_block)
                    if c:
                        if '/' not in c and not c.lower().startswith(('images/', 'videos/', 'sounds/', 'audio/')):
                            c = clean_relative_path(image_prefix + c)
                        if c:
                            if c.lower().endswith(VIDEO_EXTENSIONS):
                                p_vids.append(c)
                            else:
                                p_imgs.append(c)

                for m in re.finditer(r'<<cacheaudio\s+[\'"][^\'"]+[\'"]\s+[\'"]([^\'"]+)[\'"]', p_text):
                    raw_aud = m.group(1)
                    if '/' not in raw_aud:
                        raw_aud = sound_prefix + raw_aud
                    collect_ref(raw_aud)

                for m in re.finditer(r'setup\.ImagePath\s*\+\s*[\'"]([^\'"]+)[\'"]', p_text):
                    collect_ref(image_prefix + m.group(1).lstrip('/'))
                for m in re.finditer(r'setup\.SoundPath\s*\+\s*[\'"]([^\'"]+)[\'"]', p_text):
                    collect_ref(sound_prefix + m.group(1).lstrip('/'))

                for m in re.finditer(r'url\s*\(\s*["\']?([^"\'\)]+)["\']?\s*\)', p_text, re.I):
                    collect_ref(m.group(1))

                for img in p_imgs:
                    add_ref(img)
                for vid in p_vids:
                    add_ref(vid)
        else:
            # Twine 1 format (Sugarcane / Jonah / Responsive): <div tiddler="...">...</div>
            for tm in re.finditer(r'<div[^>]+tiddler=[\'"]([^\'"]+)[\'"][^>]*>(.*?)</div>', html_content, re.I | re.DOTALL):
                p_text = html.unescape(tm.group(2))
                for m in re.finditer(r'(?:src|href|poster|archive|data-src)\s*=\s*["\']([^"\']+)["\']', p_text, re.I):
                    add_ref(m.group(1))
                for raw in re.findall(r'\[img\[(.*?)\]\]', p_text, re.I):
                    first_block = raw.split('][')[0]
                    if '|' in first_block:
                        first_block = first_block.split('|', 1)[1]
                    add_ref(first_block)

        # Ren'Py Web engine assets
        lower_html_full = html_content.lower()
        if "renpy" in lower_html_full or "game.zip" in lower_html_full:
            renpy_files = [
                "game.zip", "game.zip.001", "game.zip.002",
                "renpy.js", "renpy-pre.js", "renpy.wasm", "renpy.data",
                "pyapp-data.js", "pythonhome-data.js",
                "package.json", "progressive_download.txt",
                "web-presplash.png", "web-presplash.jpg",
                "renpy.png", "icons/icon-192x192.png", "icons/icon-512x512.png"
            ]
            for rf in renpy_files:
                add_ref(rf)

        # RPG Maker MV/MZ engine assets
        if any(kw in lower_html_full for kw in ["rpg_core.js", "rmmz_core.js", "data/system.json"]):
            rpg_data = [
                "data/System.json", "data/Actors.json", "data/Classes.json", "data/Skills.json",
                "data/Items.json", "data/Weapons.json", "data/Armors.json", "data/Enemies.json",
                "data/Troops.json", "data/States.json", "data/Animations.json", "data/Tilesets.json",
                "data/CommonEvents.json", "data/MapInfos.json", "js/plugins.js"
            ]
            for rdf in rpg_data:
                add_ref(rdf)

        # 3. Global scan across full unescaped HTML
        HTMLAssetExtractor.extract_from_secondary_text(unescaped_html, add_ref)

        return discovered

    @staticmethod
    def extract_from_secondary_text(content, add_ref_callback=None):
        results = []
        seen = set()

        def local_add(ref):
            if add_ref_callback:
                add_ref_callback(ref)
            else:
                c = clean_relative_path(ref)
                if c and c.lower() not in seen:
                    seen.add(c.lower())
                    results.append(c)

        text = html.unescape(content)
        for m in re.finditer(r'<(?:script|link|img|video|audio|source|track)[^>]+(?:src|href|poster|data-src)\s*=\s*["\']([^"\']+)["\']', text, re.I):
            local_add(m.group(1))
        for m in re.finditer(r'url\s*\(\s*["\']?([^"\'\)]+)["\']?\s*\)', text, re.I):
            local_add(m.group(1))
        for m in re.finditer(r'["\']((?:Images|Videos|Audio|Sounds|Music|Media|Movies|Assets|img|video|videos|audio|sounds|music|media|assets|data|fonts|css|js|scripts)/[^"\'<>\\r\\n]+?\.[a-zA-Z0-9]{2,5})["\']', text, re.I):
            local_add(m.group(1))
        return results

# ==============================================================================
# DOWNLOAD WORKER & SELF-HEALING ENGINE
# ==============================================================================

class DownloadWorker:
    """Handles thread-safe downloads with Range resume, truncation verification, and bandwidth cap."""

    def __init__(self, session, base_url, target_folder, limit_mb=None, no_videos=False):
        self.session = session
        self.base_url = normalize_base_game_url(base_url)
        self.target_folder = target_folder
        self.limit_bytes = int(limit_mb * 1024 * 1024) if limit_mb else None
        self.no_videos = no_videos
        self.total_downloaded = 0
        self.limit_reached = False
        self.lock = threading.Lock()
        self.not_found_assets = set()

    def is_corrupt(self, filepath):
        """Checks if a small file is an HTML/XML captive portal or firewall error page."""
        try:
            if not os.path.exists(filepath):
                return True
            sz = os.path.getsize(filepath)
            if sz == 0:
                return True
            if sz < 8192:
                if filepath.lower().endswith(('.html', '.htm')):
                    return False
                with open(filepath, "rb") as f:
                    header = f.read(1024).lower().strip()
                if any(sig in header for sig in PORTAL_SIGNATURES):
                    return True
        except Exception:
            pass
        return False

    def purge_corrupt_files(self):
        """Self-healing scan before resuming download."""
        purged = 0
        for root, _, files in os.walk(self.target_folder):
            for fname in files:
                if fname in ["index.html", "game_meta.json", "run_game.py", "run_game.bat"] or fname.endswith(".part"):
                    continue
                ext = '.' + fname.rsplit('.', 1)[-1].lower() if '.' in fname else ''
                if ext in MEDIA_EXTENSIONS:
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
        """Downloads a single asset with Range resumption, mid-stream retry, and Content-Length check."""
        if self.limit_reached:
            if pbar:
                pbar.update(1)
            return (rel_path, False, "Limit reached")

        if self.no_videos and rel_path.lower().endswith(VIDEO_EXTENSIONS):
            if pbar:
                pbar.update(1)
            return (rel_path, True, "Skipped (video)")

        local_path = os.path.join(self.target_folder, os.path.normpath(rel_path))
        canonical_target = os.path.abspath(self.target_folder)
        canonical_local = os.path.abspath(local_path)
        if not canonical_local.startswith(canonical_target + os.sep):
            if pbar:
                pbar.update(1)
            return (rel_path, False, "Path traversal rejected")

        part_path = local_path + ".part"

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
        download_url = urljoin(self.base_url, encode_url_path(rel_path))

        max_attempts = 5
        last_err = ""

        for attempt in range(1, max_attempts + 1):
            if self.limit_reached:
                break

            existing_size = os.path.getsize(part_path) if os.path.exists(part_path) else 0
            headers = {"Referer": self.base_url}
            if existing_size > 0:
                headers["Range"] = f"bytes={existing_size}-"

            try:
                resp = self.session.get(download_url, headers=headers, stream=True, timeout=(15, 30))
                code = resp.status_code
                content_len = int(resp.headers.get("Content-Length", 0) or 0)
                expected_total = -1

                if code == 200:
                    mode = "wb"
                    existing_size = 0
                    if content_len > 0:
                        expected_total = content_len
                elif code == 206:
                    mode = "ab"
                    cr = resp.headers.get("Content-Range", "")
                    if "/" in cr:
                        try:
                            cr_total = int(cr.rsplit("/", 1)[1].strip())
                            if cr_total > 0:
                                expected_total = cr_total
                        except Exception:
                            pass
                    if expected_total <= 0 and content_len > 0:
                        expected_total = existing_size + content_len
                elif code == 416:
                    if os.path.exists(part_path) and not self.is_corrupt(part_path):
                        os.replace(part_path, local_path)
                        if pbar:
                            pbar.update(1)
                        return (rel_path, True, "Completed")
                    elif os.path.exists(part_path):
                        os.remove(part_path)
                        continue
                elif code == 404:
                    with self.lock:
                        self.not_found_assets.add(rel_path)
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
                        with self.lock:
                            self.total_downloaded += len(chunk)
                            if self.limit_bytes and self.total_downloaded >= self.limit_bytes:
                                self.limit_reached = True
                                print(f"\n[!] Data limit reached ({self.limit_bytes / (1024*1024):.1f} MB). Stopping cleanly.")
                                break
                    f.flush()

                if self.limit_reached:
                    break

                # Verify stream was not truncated prematurely
                if expected_total > 0 and os.path.exists(part_path):
                    actual_size = os.path.getsize(part_path)
                    if actual_size < expected_total:
                        raise IOError(f"Truncated stream ({actual_size}/{expected_total} bytes)")

                if os.path.exists(part_path):
                    if not self.is_corrupt(part_path):
                        os.replace(part_path, local_path)
                        if pbar:
                            pbar.update(1)
                        return (rel_path, True, "Downloaded")
                    else:
                        os.remove(part_path)
                        raise IOError("Corrupt / block page response")

            except Exception as e:
                last_err = str(e)
                if attempt < max_attempts and not self.limit_reached:
                    time.sleep(attempt)

        if pbar:
            pbar.update(1)
        return (rel_path, False, last_err)

# ==============================================================================
# MAIN GAME SCRAPER ORCHESTRATOR
# ==============================================================================

def generate_offline_runners(target_folder):
    """Generates run_game.bat and run_game.py for instant local playback on PC."""
    try:
        bat_path = os.path.join(target_folder, "run_game.bat")
        if not os.path.exists(bat_path):
            with open(bat_path, "w", encoding="utf-8") as f:
                f.write("@echo off\npython run_game.py\npause\n")

        py_path = os.path.join(target_folder, "run_game.py")
        if not os.path.exists(py_path):
            with open(py_path, "w", encoding="utf-8") as f:
                f.write(
                    "import os, mimetypes, http.server, socketserver, webbrowser\n\n"
                    "class RangeRequestHandler(http.server.SimpleHTTPRequestHandler):\n"
                    "    def end_headers(self):\n"
                    "        self.send_header('Accept-Ranges', 'bytes')\n"
                    "        super().end_headers()\n"
                    "    def do_GET(self):\n"
                    "        rel = self.path.split('?')[0].split('#')[0].lstrip('/') or 'index.html'\n"
                    "        lp = os.path.join(os.getcwd(), rel)\n"
                    "        if not os.path.isfile(lp): return super().do_GET()\n"
                    "        sz = os.path.getsize(lp)\n"
                    "        ct = mimetypes.guess_type(lp)[0] or 'application/octet-stream'\n"
                    "        rh = self.headers.get('Range')\n"
                    "        if rh and rh.startswith('bytes='):\n"
                    "            try:\n"
                    "                r = rh[6:].split('-')\n"
                    "                s = int(r[0]) if r[0] else 0\n"
                    "                e = min(int(r[1]) if r[1] else sz - 1, sz - 1)\n"
                    "                l = e - s + 1\n"
                    "                self.send_response(206)\n"
                    "                self.send_header('Content-Type', ct)\n"
                    "                self.send_header('Content-Range', f'bytes {s}-{e}/{sz}')\n"
                    "                self.send_header('Content-Length', str(l))\n"
                    "                self.end_headers()\n"
                    "                with open(lp, 'rb') as f:\n"
                    "                    f.seek(s)\n"
                    "                    rem = l\n"
                    "                    while rem > 0:\n"
                    "                        c = f.read(min(rem, 65536))\n"
                    "                        if not c: break\n"
                    "                        self.wfile.write(c)\n"
                    "                        rem -= len(c)\n"
                    "                return\n"
                    "            except Exception: pass\n"
                    "        return super().do_GET()\n\n"
                    "class ThreadingTCPServer(socketserver.ThreadingMixIn, socketserver.TCPServer):\n"
                    "    daemon_threads = True\n"
                    "    allow_reuse_address = True\n\n"
                    "port = 8080\n"
                    "for p in range(8080, 8100):\n"
                    "    try:\n"
                    "        httpd = ThreadingTCPServer(('', p), RangeRequestHandler)\n"
                    "        port = p; break\n"
                    "    except OSError: continue\n"
                    "print(f'Serving game at http://localhost:{port}/index.html')\n"
                    "webbrowser.open(f'http://localhost:{port}/index.html')\n"
                    "httpd.serve_forever()\n"
                )
    except Exception:
        pass

def grab_game(url, base_out_dir, threads=4, limit_mb=None, no_videos=False, verify_ssl=False):
    """Processes a single game URL from resolution to download completion and disk verification."""
    session = build_session(verify_ssl=verify_ssl)

    print("\n" + "=" * 70)
    print(f"[*] Processing: {url}")
    print("=" * 70)

    # 1. Resolve portal to direct game entry point
    game_url, portal_title, portal_ver, pre_fetched_html = PortalResolver.resolve(session, url)

    # 2. Fetch Entry HTML (if not already fetched by resolver)
    if pre_fetched_html:
        html_content = pre_fetched_html
    else:
        print(f"[*] Fetching entry HTML: {game_url} ...")
        try:
            resp = session.get(game_url, headers={"Referer": url}, timeout=25)
            resp.raise_for_status()
            game_url = normalize_base_game_url(resp.url)
            html_content = resp.text
        except Exception as e:
            print(f"[!] Failed to fetch entry HTML: {e}")
            return False

    # 3. Detect Title & Folder
    clean_title, version, folder_name = extract_game_metadata(html_content, game_url, portal_title, portal_ver)
    target_folder = os.path.join(base_out_dir, folder_name)
    os.makedirs(target_folder, exist_ok=True)

    print(f"[+] Game Title : {clean_title} ({version})")
    print(f"[+] Target Dir : {target_folder}")

    # 4. Save Pristine Entry HTML & Offline Runners
    index_path = os.path.join(target_folder, "index.html")
    with open(index_path, "w", encoding="utf-8") as f:
        f.write(html_content)
    generate_offline_runners(target_folder)
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

    # 7. Phase 1: Pre-fetch external .css, .js, .json to discover any nested assets
    known_lower = {a.lower() for a in assets}
    secondary_files = [a for a in assets if a.lower().endswith(('.css', '.js', '.json'))]
    for s_asset in secondary_files:
        if worker.limit_reached:
            break
        _, ok, _ = worker.download_asset(s_asset)
        if ok:
            s_path = os.path.join(target_folder, os.path.normpath(s_asset))
            if os.path.exists(s_path) and os.path.getsize(s_path) < 5 * 1024 * 1024:
                try:
                    with open(s_path, "r", encoding="utf-8", errors="ignore") as f:
                        nested = HTMLAssetExtractor.extract_from_secondary_text(f.read())
                    for na in nested:
                        if na.lower() not in known_lower:
                            known_lower.add(na.lower())
                            assets.append(na)
                except Exception:
                    pass

    # 8. Phase 2: Multi-threaded Download
    print(f"[*] Downloading {len(assets)} assets with {threads} parallel workers...")
    start_time = time.time()
    failed = []

    if HAS_TQDM:
        with tqdm(total=len(assets), unit="file", desc="Downloading", ncols=80) as pbar:
            with ThreadPoolExecutor(max_workers=threads) as executor:
                futures = [executor.submit(worker.download_asset, asset, pbar) for asset in assets]
                for future in as_completed(futures):
                    rel, ok, msg = future.result()
                    if not ok and rel not in worker.not_found_assets:
                        failed.append(rel)
    else:
        completed = 0
        total = len(assets)
        with ThreadPoolExecutor(max_workers=threads) as executor:
            futures = [executor.submit(worker.download_asset, asset) for asset in assets]
            for future in as_completed(futures):
                rel, ok, msg = future.result()
                if not ok and rel not in worker.not_found_assets:
                    failed.append(rel)
                completed += 1
                if completed % 5 == 0 or completed == total:
                    print(f"  [{completed}/{total}] files processed...")

    # 9. Phase 3: Automatic recovery pass on transient failures
    if failed and not worker.limit_reached:
        print(f"[*] Retrying {len(failed)} interrupted assets...")
        for rel in list(failed):
            if worker.limit_reached:
                break
            _, ok, _ = worker.download_asset(rel)
            if ok:
                failed.remove(rel)

    elapsed = time.time() - start_time
    mb_down = worker.total_downloaded / (1024 * 1024)
    print(f"[+] Transfer complete: {mb_down:.1f} MB downloaded in {elapsed:.1f}s.")

    if worker.limit_reached:
        print("[*] Paused at download quota. Run again anytime to resume where you left off!")
        return False

    # 10. Phase 4: Final Disk Verification Audit
    verified_count = 0
    skipped_count = 0
    missing_count = 0
    for rel in assets:
        if no_videos and rel.lower().endswith(VIDEO_EXTENSIONS):
            skipped_count += 1
            continue
        if rel in worker.not_found_assets:
            skipped_count += 1
            continue
        lp = os.path.join(target_folder, os.path.normpath(rel))
        if os.path.exists(lp) and not worker.is_corrupt(lp):
            verified_count += 1
        else:
            missing_count += 1

    expected_valid = len(assets) - skipped_count
    print(f"[AUDIT] Verified on disk: {verified_count}/{expected_valid} files intact.")
    if missing_count > 0:
        print(f"[!] Warning: {missing_count} files incomplete. Run again to resume.")
        return False

    return True

# ==============================================================================
# CLI & BATCH LINK PARSER
# ==============================================================================

def parse_clean_links(lines):
    """Deduplicates and sanitizes input URLs."""
    seen = set()
    links = []
    for raw_line in lines:
        clean = re.sub(r'[\u200b-\u200d\ufeff\u00a0]', ' ', raw_line).strip()
        if not clean or clean.startswith("#"):
            continue
        clean = re.sub(r'^[-*•>]+\s*', '', clean).strip('\'" ')
        for token in clean.split():
            t = token.strip('\'" ')
            if not t:
                continue
            if not t.startswith(("http://", "https://")):
                if "." in t and "/" in t:
                    t = "https://" + t
                else:
                    continue
            if t not in seen:
                seen.add(t)
                links.append(t)
    return links

def load_links_from_file(file_path):
    """Loads clean, non-empty, non-comment lines from a text file."""
    if not os.path.exists(file_path):
        return []
    with open(file_path, "r", encoding="utf-8") as f:
        return parse_clean_links(f.readlines())

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

    links = []
    if args.urls:
        links.extend(parse_clean_links(args.urls))
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

    print("\n[+] All jobs processed!")

if __name__ == "__main__":
    main()
