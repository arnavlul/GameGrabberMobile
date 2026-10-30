package com.gamegrabber.mobile;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class GameDownloader {

    public interface DownloadListener {
        void onLog(String message);
        void onProgress(int completed, int total, long bytesDownloaded);
        void onComplete(boolean success, String message);
    }

    private final String baseUrl;
    private final File targetFolder;
    private final long limitBytes;
    private final boolean noVideos;
    private final int threadCount;
    private final DownloadListener listener;

    private final AtomicBoolean isStopped = new AtomicBoolean(false);
    private final AtomicBoolean limitReached = new AtomicBoolean(false);
    private final AtomicLong totalBytesDownloaded = new AtomicLong(0);

    private static final String[] PORTAL_SIGNATURES = new String[]{
        "web block", "access denied", "blocked site", "sophos",
        "sonicwall", "fortigate", "captive portal", "<!doctype html",
        "<html", "<error><code"
    };

    public GameDownloader(String baseUrl, File targetFolder, long limitBytes, boolean noVideos, int threadCount, DownloadListener listener) {
        this.baseUrl = PortalResolver.normalizeBaseGameUrl(baseUrl);
        this.targetFolder = targetFolder;
        this.limitBytes = limitBytes;
        this.noVideos = noVideos;
        this.threadCount = Math.max(1, Math.min(threadCount, 12));
        this.listener = listener;
    }

    public void stop() {
        isStopped.set(true);
    }

    public boolean isLimitReached() {
        return limitReached.get();
    }

    public boolean isCorrupt(File file) {
        if (file == null || !file.exists()) return true;
        long sz = file.length();
        if (sz == 0) return true;
        if (sz < 8192) {
            String nameLower = file.getName().toLowerCase();
            if (nameLower.endsWith(".html") || nameLower.endsWith(".htm")) {
                return false;
            }
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[(int) Math.min(sz, 1024)];
                int read = fis.read(buffer);
                if (read > 0) {
                    String head = new String(buffer, 0, read, StandardCharsets.UTF_8).toLowerCase().trim();
                    for (String sig : PORTAL_SIGNATURES) {
                        if (head.contains(sig)) return true;
                    }
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    public int purgeCorrupt(File dir) {
        int purged = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            if (f.isDirectory()) {
                purged += purgeCorrupt(f);
            } else {
                String name = f.getName();
                if (name.equals("index.html") || name.equals("run_game.py") || name.equals("run_game.bat") || name.endsWith(".part")) {
                    continue;
                }
                if (isCorrupt(f)) {
                    if (f.delete()) purged++;
                }
            }
        }
        return purged;
    }

    public static String encodeUrlPath(String relPath) {
        try {
            String[] segments = relPath.split("/");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < segments.length; i++) {
                if (i > 0) sb.append("/");
                String encoded = URLEncoder.encode(segments[i], "UTF-8")
                    .replace("+", "%20")
                    .replace("%28", "(")
                    .replace("%29", ")")
                    .replace("%27", "'")
                    .replace("%21", "!")
                    .replace("%2C", ",")
                    .replace("%7E", "~");
                sb.append(encoded);
            }
            return sb.toString();
        } catch (Exception e) {
            return relPath.replace(" ", "%20");
        }
    }

    private boolean safeRename(File partFile, File localFile) {
        if (localFile.exists()) {
            localFile.delete();
        }
        if (partFile.renameTo(localFile)) {
            return true;
        }
        // Fallback stream copy for Android FUSE edge cases
        try (FileInputStream in = new FileInputStream(partFile);
             FileOutputStream out = new FileOutputStream(localFile)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
        } catch (Exception e) {
            return false;
        }
        partFile.delete();
        return localFile.exists() && localFile.length() > 0;
    }

    private boolean downloadSingleAsset(String rel, Set<String> notFoundAssets) {
        if (isStopped.get()) return false;

        String lower = rel.toLowerCase();
        if (noVideos && (lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".avi") || lower.endsWith(".mov") || lower.endsWith(".mkv"))) {
            return true;
        }

        File localFile = new File(targetFolder, rel.replace('/', File.separatorChar));
        File partFile = new File(localFile.getAbsolutePath() + ".part");

        if (localFile.exists() && !isCorrupt(localFile)) {
            return true;
        }

        File parentDir = localFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        int maxAttempts = 5;
        String lastError = "";

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (isStopped.get()) return false;

            long existingSize = partFile.exists() ? partFile.length() : 0;
            HttpURLConnection conn = null;
            try {
                URL downloadUrl = new URL(new URL(baseUrl), encodeUrlPath(rel));
                Map<String, String> extraHeaders = new HashMap<>();
                if (existingSize > 0) {
                    extraHeaders.put("Range", "bytes=" + existingSize + "-");
                }

                conn = PortalResolver.openConnectionWithRedirects(
                    downloadUrl.toString(), baseUrl, extraHeaders, 15000, 30000
                );

                int code = conn.getResponseCode();
                boolean append = false;
                long contentLen = conn.getContentLengthLong();
                long expectedTotalSize = -1;

                if (code == 206) {
                    append = true;
                    if (contentLen > 0) {
                        expectedTotalSize = existingSize + contentLen;
                    }
                } else if (code == 200) {
                    append = false;
                    existingSize = 0;
                    if (contentLen > 0) {
                        expectedTotalSize = contentLen;
                    }
                } else if (code == 416) {
                    // Range not satisfiable: .part file is already complete
                    if (partFile.exists() && !isCorrupt(partFile)) {
                        safeRename(partFile, localFile);
                        return true;
                    } else if (partFile.exists()) {
                        partFile.delete();
                        continue;
                    }
                } else if (code == 404) {
                    notFoundAssets.add(rel);
                    return false;
                } else {
                    throw new IOException("HTTP " + code);
                }

                try (InputStream in = conn.getInputStream();
                     FileOutputStream fos = new FileOutputStream(partFile, append)) {
                    byte[] buffer = new byte[65536];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        if (isStopped.get()) break;
                        fos.write(buffer, 0, bytesRead);
                        long currentTotal = totalBytesDownloaded.addAndGet(bytesRead);

                        if (limitBytes > 0 && currentTotal >= limitBytes) {
                            limitReached.set(true);
                            isStopped.set(true);
                            listener.onLog("[!] Data limit reached (" + (limitBytes / (1024 * 1024)) + " MB). Pausing cleanly.");
                            break;
                        }
                    }
                    fos.flush();
                }

                if (isStopped.get()) {
                    return false;
                }

                // Verify stream was not prematurely truncated by server/network
                if (expectedTotalSize > 0 && partFile.length() < expectedTotalSize) {
                    throw new IOException("Truncated stream (" + partFile.length() + "/" + expectedTotalSize + " bytes)");
                }

                if (partFile.exists()) {
                    if (!isCorrupt(partFile)) {
                        if (safeRename(partFile, localFile)) {
                            return true;
                        }
                    } else {
                        partFile.delete();
                        throw new IOException("Corrupt / block page response");
                    }
                }

            } catch (Exception e) {
                lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                if (attempt < maxAttempts && !isStopped.get()) {
                    try {
                        Thread.sleep(1000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }

        if (!isStopped.get()) {
            listener.onLog("[!] Failed after retries: " + rel + " (" + lastError + ")");
        }
        return false;
    }

    private void generateOfflineRunners() {
        try {
            File batFile = new File(targetFolder, "run_game.bat");
            if (!batFile.exists()) {
                try (FileWriter fw = new FileWriter(batFile)) {
                    fw.write("@echo off\r\npython run_game.py\r\npause\r\n");
                }
            }
            File pyFile = new File(targetFolder, "run_game.py");
            if (!pyFile.exists()) {
                try (FileWriter fw = new FileWriter(pyFile)) {
                    fw.write(
                        "import os, mimetypes, http.server, socketserver, webbrowser\n\n" +
                        "class RangeRequestHandler(http.server.SimpleHTTPRequestHandler):\n" +
                        "    def end_headers(self):\n" +
                        "        self.send_header('Accept-Ranges', 'bytes')\n" +
                        "        super().end_headers()\n" +
                        "    def do_GET(self):\n" +
                        "        rel = self.path.split('?')[0].split('#')[0].lstrip('/') or 'index.html'\n" +
                        "        lp = os.path.join(os.getcwd(), rel)\n" +
                        "        if not os.path.isfile(lp): return super().do_GET()\n" +
                        "        sz = os.path.getsize(lp)\n" +
                        "        ct = mimetypes.guess_type(lp)[0] or 'application/octet-stream'\n" +
                        "        rh = self.headers.get('Range')\n" +
                        "        if rh and rh.startswith('bytes='):\n" +
                        "            try:\n" +
                        "                r = rh[6:].split('-')\n" +
                        "                s = int(r[0]) if r[0] else 0\n" +
                        "                e = min(int(r[1]) if r[1] else sz - 1, sz - 1)\n" +
                        "                l = e - s + 1\n" +
                        "                self.send_response(206)\n" +
                        "                self.send_header('Content-Type', ct)\n" +
                        "                self.send_header('Content-Range', f'bytes {s}-{e}/{sz}')\n" +
                        "                self.send_header('Content-Length', str(l))\n" +
                        "                self.end_headers()\n" +
                        "                with open(lp, 'rb') as f:\n" +
                        "                    f.seek(s)\n" +
                        "                    rem = l\n" +
                        "                    while rem > 0:\n" +
                        "                        c = f.read(min(rem, 65536))\n" +
                        "                        if not c: break\n" +
                        "                        self.wfile.write(c)\n" +
                        "                        rem -= len(c)\n" +
                        "                return\n" +
                        "            except Exception: pass\n" +
                        "        return super().do_GET()\n\n" +
                        "class ThreadingTCPServer(socketserver.ThreadingMixIn, socketserver.TCPServer):\n" +
                        "    daemon_threads = True\n" +
                        "    allow_reuse_address = True\n\n" +
                        "port = 8080\n" +
                        "for p in range(8080, 8100):\n" +
                        "    try:\n" +
                        "        httpd = ThreadingTCPServer(('', p), RangeRequestHandler)\n" +
                        "        port = p; break\n" +
                        "    except OSError: continue\n" +
                        "print(f'Serving game at http://localhost:{port}/index.html')\n" +
                        "webbrowser.open(f'http://localhost:{port}/index.html')\n" +
                        "httpd.serve_forever()\n"
                    );
                }
            }
        } catch (Exception ignored) {}
    }

    public boolean execute(List<String> initialAssets) {
        targetFolder.mkdirs();
        int purged = purgeCorrupt(targetFolder);
        if (purged > 0) {
            listener.onLog("[*] Self-healing: Purged " + purged + " corrupt files.");
        }
        generateOfflineRunners();

        // Keep an ordered set of all assets
        List<String> allAssets = new ArrayList<>(initialAssets);
        Set<String> knownLower = new HashSet<>();
        for (String a : allAssets) knownLower.add(a.toLowerCase());

        Set<String> notFoundAssets = ConcurrentHashMap.newKeySet();
        List<String> failedAssets = Collections.synchronizedList(new ArrayList<>());

        // Phase 1: Download any external .css, .js, .json files first & scan them for nested asset references
        List<String> scriptAndStyleAssets = new ArrayList<>();
        for (String a : allAssets) {
            String l = a.toLowerCase();
            if (l.endsWith(".css") || l.endsWith(".js") || l.endsWith(".json")) {
                scriptAndStyleAssets.add(a);
            }
        }

        if (!scriptAndStyleAssets.isEmpty() && !isStopped.get()) {
            for (String sAsset : scriptAndStyleAssets) {
                if (isStopped.get()) break;
                if (downloadSingleAsset(sAsset, notFoundAssets)) {
                    File f = new File(targetFolder, sAsset.replace('/', File.separatorChar));
                    if (f.exists() && f.length() < 5 * 1024 * 1024) {
                        try {
                            StringBuilder sb = new StringBuilder();
                            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                                char[] buf = new char[16384];
                                int n;
                                while ((n = br.read(buf)) != -1) sb.append(buf, 0, n);
                            }
                            List<String> nested = TwineParser.extractAssetsFromSecondaryFile(sb.toString());
                            for (String na : nested) {
                                if (!knownLower.contains(na.toLowerCase())) {
                                    knownLower.add(na.toLowerCase());
                                    allAssets.add(na);
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        }

        final int total = allAssets.size();
        AtomicInteger completed = new AtomicInteger(0);

        // Phase 2: Main parallel download pass
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (String rel : allAssets) {
            if (isStopped.get()) break;
            pool.submit(() -> {
                if (isStopped.get()) return;
                boolean ok = downloadSingleAsset(rel, notFoundAssets);
                if (!ok && !notFoundAssets.contains(rel) && !isStopped.get()) {
                    failedAssets.add(rel);
                }
                int c = completed.incrementAndGet();
                listener.onProgress(c, total, totalBytesDownloaded.get());
            });
        }

        pool.shutdown();
        try {
            pool.awaitTermination(24, TimeUnit.HOURS);
        } catch (InterruptedException ignored) {}

        // Phase 3: Automatic recovery pass on any transient failures
        if (!isStopped.get() && !failedAssets.isEmpty()) {
            listener.onLog("[*] Retrying " + failedAssets.size() + " interrupted assets...");
            List<String> retryList = new ArrayList<>(failedAssets);
            failedAssets.clear();
            for (String rel : retryList) {
                if (isStopped.get()) break;
                if (!downloadSingleAsset(rel, notFoundAssets)) {
                    if (!notFoundAssets.contains(rel)) {
                        failedAssets.add(rel);
                    }
                }
            }
        }

        if (isStopped.get()) {
            String msg = limitReached.get()
                ? "Paused at MB limit. Tap START DOWNLOAD anytime to resume."
                : "Download paused. Tap START DOWNLOAD anytime to resume.";
            listener.onComplete(false, msg);
            return false;
        }

        // Phase 4: Final Disk Verification Audit
        int verifiedCount = 0;
        int skippedCount = 0;
        int missingCount = 0;

        for (String rel : allAssets) {
            String lower = rel.toLowerCase();
            if (noVideos && (lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".avi") || lower.endsWith(".mov") || lower.endsWith(".mkv"))) {
                skippedCount++;
                continue;
            }
            if (notFoundAssets.contains(rel)) {
                skippedCount++;
                continue;
            }
            File localFile = new File(targetFolder, rel.replace('/', File.separatorChar));
            if (localFile.exists() && !isCorrupt(localFile)) {
                verifiedCount++;
            } else {
                missingCount++;
            }
        }

        int expectedValid = total - skippedCount;
        listener.onLog("[AUDIT] Verified on storage: " + verifiedCount + "/" + expectedValid + " files intact" +
            (notFoundAssets.isEmpty() ? "." : " (" + notFoundAssets.size() + " optional/404 skipped)."));

        if (missingCount == 0) {
            listener.onComplete(true, "100% Verified (" + verifiedCount + " files intact on disk)!");
            return true;
        } else {
            listener.onComplete(false, missingCount + " files incomplete. Tap START DOWNLOAD to resume.");
            return false;
        }
    }
}
