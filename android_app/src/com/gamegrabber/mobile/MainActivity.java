package com.gamegrabber.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public class MainActivity extends Activity {

    private EditText editLinks;
    private Button btnPaste, btnClear, btnDownload, btnStop;
    private CheckBox chkSkipVideos, chkSslBypass;
    private EditText editLimitMb, editThreads;
    private TextView txtStatus, txtCounter, txtLog;
    private ProgressBar progressBar;
    private ScrollView scrollLog;

    private ExecutorService mainExecutor;
    private volatile GameDownloader currentDownloader;
    private volatile boolean isRunning = false;
    private final java.util.concurrent.atomic.AtomicInteger currentRunId = new java.util.concurrent.atomic.AtomicInteger(0);
    private PowerManager.WakeLock wakeLock;

    private final LinkedList<String> logLines = new LinkedList<>();
    private static final int MAX_LOG_LINES = 250;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        editLinks = findViewById(R.id.edit_links);
        btnPaste = findViewById(R.id.btn_paste);
        btnClear = findViewById(R.id.btn_clear);
        btnDownload = findViewById(R.id.btn_download);
        btnStop = findViewById(R.id.btn_stop);

        chkSkipVideos = findViewById(R.id.chk_skip_videos);
        chkSslBypass = findViewById(R.id.chk_ssl_bypass);
        editLimitMb = findViewById(R.id.edit_limit_mb);
        editThreads = findViewById(R.id.edit_threads);

        txtStatus = findViewById(R.id.txt_status);
        txtCounter = findViewById(R.id.txt_counter);
        txtLog = findViewById(R.id.txt_log);
        progressBar = findViewById(R.id.progress_bar);
        scrollLog = findViewById(R.id.scroll_log);

        logLines.add("[Ready] Enter links above (one per line) and tap START DOWNLOAD.");
        logLines.add("Games are saved to Downloads/GameGrabber/");

        btnPaste.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence text = cm.getPrimaryClip().getItemAt(0).getText();
                if (text != null) {
                    String existing = editLinks.getText().toString();
                    if (!existing.isEmpty() && !existing.endsWith("\n")) {
                        existing += "\n";
                    }
                    editLinks.setText(existing + text.toString().trim() + "\n");
                    editLinks.setSelection(editLinks.getText().length());
                }
            }
        });

        btnClear.setOnClickListener(v -> editLinks.setText(""));
        btnDownload.setOnClickListener(v -> startDownloadQueue());
        btnStop.setOnClickListener(v -> stopDownload());

        mainExecutor = Executors.newSingleThreadExecutor();

        PortalResolver.enableSSLBypass();
        ensureStoragePermissions();
        tryAutoLoadLinksFile();
    }

    private void ensureStoragePermissions() {
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File testFolder = new File(downloadDir, "GameGrabber");
        if (canWriteToDirectory(testFolder)) {
            return;
        }

        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                appendLog("[*] Requesting storage access to save directly in Downloads/GameGrabber/...");
                Toast.makeText(this, "Please allow file access to save games in Downloads/", Toast.LENGTH_LONG).show();
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    try {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        startActivity(intent);
                    } catch (Exception ignored) {}
                }
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }, 101);
            }
        }
    }

    private boolean canWriteToDirectory(File dir) {
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                return false;
            }
            File probe = new File(dir, ".probe_" + System.currentTimeMillis() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(probe)) {
                fos.write(1);
            }
            boolean exists = probe.exists();
            probe.delete();
            return exists;
        } catch (Exception e) {
            return false;
        }
    }

    private File getSaveDirectory() {
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File appFolder = new File(downloadDir, "GameGrabber");
        if (canWriteToDirectory(appFolder)) {
            return appFolder;
        }
        File fallback = new File(getExternalFilesDir(null), "Games");
        fallback.mkdirs();
        return fallback;
    }

    private void tryAutoLoadLinksFile() {
        if (!editLinks.getText().toString().trim().isEmpty()) return;
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File[] candidates = new File[]{
            new File(new File(downloadDir, "GameGrabber"), "links.txt"),
            new File(downloadDir, "links.txt"),
            new File(getExternalFilesDir(null), "links.txt")
        };
        for (File f : candidates) {
            if (f.exists() && f.canRead()) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        String clean = line.trim();
                        if (!clean.isEmpty() && !clean.startsWith("#")) {
                            sb.append(clean).append("\n");
                        }
                    }
                    if (sb.length() > 0) {
                        editLinks.setText(sb.toString());
                        appendLog("[+] Loaded links from " + f.getAbsolutePath());
                        return;
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    private void appendLog(String message) {
        runOnUiThread(() -> {
            logLines.add(message);
            while (logLines.size() > MAX_LOG_LINES) {
                logLines.removeFirst();
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < logLines.size(); i++) {
                if (i > 0) sb.append("\n");
                sb.append(logLines.get(i));
            }
            txtLog.setText(sb.toString());
            scrollLog.post(() -> scrollLog.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private List<String> parseInputUrls(String raw) {
        Set<String> uniqueUrls = new LinkedHashSet<>();
        String cleanedRaw = raw.replaceAll("[\\u200B-\\u200D\\uFEFF\\u00A0]", " ");
        String[] lines = cleanedRaw.split("[\\r\\n]+");
        for (String line : lines) {
            String clean = line.trim();
            if (clean.isEmpty() || clean.startsWith("#")) continue;
            // Remove leading list bullets or quotes
            clean = clean.replaceAll("^[-*•>]+\\s*", "").replaceAll("^['\"]+|['\"]+$", "").trim();
            if (clean.isEmpty()) continue;

            // Split if multiple http URLs were pasted on the same line separated by spaces
            String[] tokens = clean.split("\\s+");
            for (String token : tokens) {
                String t = token.trim();
                if (t.isEmpty()) continue;
                if (!t.startsWith("http://") && !t.startsWith("https://")) {
                    if (t.contains(".") && t.contains("/")) {
                        t = "https://" + t;
                    } else {
                        continue;
                    }
                }
                String safe = PortalResolver.sanitizeUrl(t);
                uniqueUrls.add(safe);
            }
        }
        return new ArrayList<>(uniqueUrls);
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GameGrabber::DownloadWakeLock");
                    wakeLock.setReferenceCounted(false);
                }
            }
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(6 * 60 * 60 * 1000L); // up to 6 hours
            }
        } catch (Exception ignored) {}
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {}
    }

    private void startDownloadQueue() {
        if (isRunning) return;

        List<String> urls = parseInputUrls(editLinks.getText().toString());
        if (urls.isEmpty()) {
            Toast.makeText(this, "Please enter at least one valid game URL", Toast.LENGTH_SHORT).show();
            return;
        }

        isRunning = true;
        btnDownload.setEnabled(false);
        btnStop.setEnabled(true);
        progressBar.setProgress(0);
        txtStatus.setText("Status: Initializing (" + urls.size() + " game" + (urls.size() > 1 ? "s" : "") + ")...");

        boolean skipVideos = chkSkipVideos.isChecked();
        boolean sslBypass = chkSslBypass.isChecked();

        long limitBytes = 0;
        try {
            String lStr = editLimitMb.getText().toString().trim();
            if (!lStr.isEmpty()) {
                limitBytes = (long) (Double.parseDouble(lStr) * 1024 * 1024);
            }
        } catch (Exception ignored) {}

        int threads = 4;
        try {
            threads = Integer.parseInt(editThreads.getText().toString().trim());
        } catch (Exception ignored) {}

        final long fTotalLimitBytes = limitBytes;
        final int fThreads = Math.max(1, Math.min(threads, 12));
        final int runId = currentRunId.incrementAndGet();

        acquireWakeLock();

        mainExecutor.submit(() -> {
            if (currentRunId.get() != runId) return;
            int jobsSucceeded = 0;
            AtomicLong batchBytesDownloaded = new AtomicLong(0);

            try {
                File baseStorage = getSaveDirectory();
                appendLog("[*] Storage directory: " + baseStorage.getAbsolutePath());

                for (int i = 0; i < urls.size(); i++) {
                    if (!isRunning || currentRunId.get() != runId) break;

                    // Check if overall batch data limit reached
                    long usedBytes = batchBytesDownloaded.get();
                    long jobLimitBytes = 0;
                    if (fTotalLimitBytes > 0) {
                        if (usedBytes >= fTotalLimitBytes) {
                            appendLog("[!] Total batch data limit reached. Stopping queue.");
                            break;
                        }
                        jobLimitBytes = fTotalLimitBytes - usedBytes;
                    }

                    String url = urls.get(i);
                    final int jobNum = i + 1;
                    runOnUiThread(() -> {
                        if (currentRunId.get() == runId) {
                            txtStatus.setText("Status: Job " + jobNum + "/" + urls.size() + " resolving...");
                        }
                    });
                    appendLog("\n=============================================");
                    appendLog("[*] Job (" + jobNum + "/" + urls.size() + "): " + url);

                    try {
                        // 1. Resolve portal or direct game URL
                        PortalResolver.PortalResult resolved = PortalResolver.resolve(url, sslBypass);
                        String gameUrl = resolved.gameUrl;
                        if (!gameUrl.equals(url)) {
                            appendLog("[+] Resolved game URL: " + gameUrl);
                        }

                        // 2. Fetch Entry HTML (or reuse pre-fetched HTML if already downloaded by resolver)
                        String html = resolved.preFetchedHtml;
                        if (html == null || html.isEmpty()) {
                            appendLog("[*] Fetching entry HTML...");
                            HttpURLConnection conn = PortalResolver.openConnectionWithRedirects(
                                gameUrl, url, null, 15000, 25000
                            );
                            gameUrl = PortalResolver.normalizeBaseGameUrl(conn.getURL().toString());
                            html = PortalResolver.readStreamAsString(conn);
                            conn.disconnect();
                        }

                        // 3. Extract Metadata & Create Target Folder
                        TwineParser.GameMeta meta = TwineParser.extractMetadata(
                            html, gameUrl, resolved.portalTitle, resolved.portalVersion
                        );
                        File gameFolder = new File(baseStorage, meta.folderName);
                        gameFolder.mkdirs();

                        appendLog("[+] Title      : " + meta.title + " (" + meta.version + ")");
                        appendLog("[+] Folder     : " + gameFolder.getName());

                        // 4. Save Pristine Entry HTML (UTF-8)
                        File indexPath = new File(gameFolder, "index.html");
                        try (Writer writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(indexPath), StandardCharsets.UTF_8))) {
                            writer.write(html);
                        }
                        appendLog("[+] Saved pristine index.html (" + (html.length() / 1024) + " KB)");

                        // 5. Extract Story & HTML Assets
                        List<String> assets = TwineParser.extractAssets(html);
                        appendLog("[+] Discovered " + assets.size() + " story/game assets.");

                        if (assets.isEmpty()) {
                            appendLog("[+] Self-contained HTML game! Ready for offline play.");
                            jobsSucceeded++;
                            continue;
                        }

                        final String gameTitle = meta.title;
                        runOnUiThread(() -> txtStatus.setText("Downloading (" + jobNum + "/" + urls.size() + "): " + gameTitle));

                        // 6. Execute Resumable Multi-Threaded Download + Verification Audit
                        final long baseUsedBeforeJob = batchBytesDownloaded.get();
                        currentDownloader = new GameDownloader(
                            gameUrl, gameFolder, jobLimitBytes, skipVideos, fThreads,
                            new GameDownloader.DownloadListener() {
                                private long lastUiUpdateMs = 0;

                                @Override
                                public void onLog(String message) {
                                    appendLog(message);
                                }

                                @Override
                                public void onProgress(int completed, int total, long jobBytesDownloaded) {
                                    batchBytesDownloaded.set(baseUsedBeforeJob + jobBytesDownloaded);
                                    long now = System.currentTimeMillis();
                                    if (completed < total && (now - lastUiUpdateMs) < 120) {
                                        return;
                                    }
                                    lastUiUpdateMs = now;
                                    final long totalAllJobsBytes = baseUsedBeforeJob + jobBytesDownloaded;
                                    runOnUiThread(() -> {
                                        int pct = total > 0 ? (int) ((completed / (float) total) * 100) : 100;
                                        progressBar.setProgress(pct);
                                        double mb = totalAllJobsBytes / (1024.0 * 1024.0);
                                        txtCounter.setText(
                                            "Job " + jobNum + "/" + urls.size() + ": " +
                                            completed + " / " + total + " files (" +
                                            String.format(Locale.US, "%.1f", mb) + " MB downloaded)"
                                        );
                                    });
                                }

                                @Override
                                public void onComplete(boolean success, String message) {
                                    appendLog(success ? "[+] " + message : "[!] " + message);
                                }
                            }
                        );

                        boolean jobOk = currentDownloader.execute(assets);
                        if (jobOk) {
                            jobsSucceeded++;
                            appendLog("[+] Completed & verified: " + meta.title);
                        } else if (currentDownloader.isLimitReached()) {
                            break;
                        }

                    } catch (Exception e) {
                        appendLog("[!] Error on job " + jobNum + " (" + url + "): " + e.getMessage());
                    }
                }
            } finally {
                releaseWakeLock();
                final int finalSucceeded = jobsSucceeded;
                final int totalJobs = urls.size();
                runOnUiThread(() -> {
                    if (currentRunId.get() == runId) {
                        isRunning = false;
                        btnDownload.setEnabled(true);
                        btnStop.setEnabled(false);
                        if (finalSucceeded == totalJobs) {
                            txtStatus.setText("Status: All " + totalJobs + " Game(s) Verified & Complete!");
                        } else {
                            txtStatus.setText("Status: " + finalSucceeded + "/" + totalJobs + " Complete (Tap START to resume)");
                        }
                        appendLog("\n[*] Queue finished: " + finalSucceeded + "/" + totalJobs + " games completely verified.");
                    }
                });
            }
        });
    }

    private void stopDownload() {
        currentRunId.incrementAndGet();
        isRunning = false;
        if (currentDownloader != null) {
            currentDownloader.stop();
        }
        releaseWakeLock();
        runOnUiThread(() -> {
            btnDownload.setEnabled(true);
            btnStop.setEnabled(false);
            txtStatus.setText("Status: Paused (Tap START to resume)");
            appendLog("[!] Paused by user. Progress saved in .part files.");
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isRunning = false;
        if (currentDownloader != null) {
            currentDownloader.stop();
        }
        releaseWakeLock();
        if (mainExecutor != null) {
            mainExecutor.shutdownNow();
        }
    }
}
