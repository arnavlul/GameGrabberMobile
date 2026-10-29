package com.gamegrabber.mobile;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Environment;
import android.widget.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private EditText editLinks;
    private Button btnPaste, btnClear, btnDownload, btnStop;
    private CheckBox chkSkipVideos, chkSslBypass;
    private EditText editLimitMb, editThreads;
    private TextView txtStatus, txtCounter, txtLog;
    private ProgressBar progressBar;
    private ScrollView scrollLog;

    private ExecutorService mainExecutor;
    private GameDownloader currentDownloader;
    private volatile boolean isRunning = false;

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
    }

    private void appendLog(String message) {
        runOnUiThread(() -> {
            txtLog.append("\n" + message);
            scrollLog.post(() -> scrollLog.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private File getSaveDirectory() {
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File appFolder = new File(downloadDir, "GameGrabber");
        if (!appFolder.exists()) {
            if (!appFolder.mkdirs()) {
                File fallback = new File(getExternalFilesDir(null), "Games");
                fallback.mkdirs();
                return fallback;
            }
        }
        return appFolder;
    }

    private void startDownloadQueue() {
        String raw = editLinks.getText().toString();
        String[] lines = raw.split("\n");
        List<String> urls = new ArrayList<>();
        for (String line : lines) {
            String clean = line.trim();
            if (!clean.isEmpty() && !clean.startsWith("#")) {
                urls.add(clean);
            }
        }

        if (urls.isEmpty()) {
            Toast.makeText(this, "Please enter at least one URL", Toast.LENGTH_SHORT).show();
            return;
        }

        isRunning = true;
        btnDownload.setEnabled(false);
        btnStop.setEnabled(true);
        txtStatus.setText("Status: Downloading...");

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

        final long fLimitBytes = limitBytes;
        final int fThreads = threads;

        mainExecutor.submit(() -> {
            File baseStorage = getSaveDirectory();
            appendLog("[*] Games storage directory: " + baseStorage.getAbsolutePath());

            for (int i = 0; i < urls.size(); i++) {
                if (!isRunning) break;

                String url = urls.get(i);
                appendLog("\n=============================================");
                appendLog("[*] Processing Job (" + (i + 1) + "/" + urls.size() + "): " + url);

                try {
                    // 1. Resolve portal (Gamcore -> iframe/CDN)
                    String gameUrl = PortalResolver.resolveGameUrl(url, sslBypass);
                    if (!gameUrl.equals(url)) {
                        appendLog("[+] Resolved portal iframe: " + gameUrl);
                    }

                    // 2. Fetch Entry HTML
                    appendLog("[*] Fetching entry HTML...");
                    URL u = new URL(gameUrl);
                    HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36");
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(15000);

                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String l;
                    while ((l = reader.readLine()) != null) {
                        sb.append(l).append("\n");
                    }
                    reader.close();
                    String html = sb.toString();

                    // 3. Metadata & Target Folder
                    TwineParser.GameMeta meta = TwineParser.extractMetadata(html, gameUrl);
                    File gameFolder = new File(baseStorage, meta.folderName);
                    gameFolder.mkdirs();

                    appendLog("[+] Game Title : " + meta.title + " (" + meta.version + ")");
                    appendLog("[+] Output Dir : " + gameFolder.getName());

                    // 4. Save Pristine Entry HTML
                    File indexPath = new File(gameFolder, "index.html");
                    try (FileWriter fw = new FileWriter(indexPath)) {
                        fw.write(html);
                    }
                    appendLog("[+] Saved pristine index.html");

                    // 5. Extract Assets
                    List<String> assets = TwineParser.extractAssets(html);
                    appendLog("[+] Discovered " + assets.size() + " assets in story graph.");

                    if (assets.isEmpty()) {
                        appendLog("[✓] Self-contained game! Ready to play offline.");
                        continue;
                    }

                    // 6. Download Assets
                    currentDownloader = new GameDownloader(
                        gameUrl, gameFolder, fLimitBytes, skipVideos, fThreads,
                        new GameDownloader.DownloadListener() {
                            @Override
                            public void onLog(String message) {
                                appendLog(message);
                            }

                            @Override
                            public void onProgress(int completed, int total, long bytesDownloaded) {
                                runOnUiThread(() -> {
                                    int pct = (int) ((completed / (float) total) * 100);
                                    progressBar.setProgress(pct);
                                    double mb = bytesDownloaded / (1024.0 * 1024.0);
                                    txtCounter.setText(completed + " / " + total + " files (" + String.format(Locale.US, "%.1f", mb) + " MB)");
                                });
                            }

                            @Override
                            public void onComplete(boolean success, String message) {
                                appendLog("[*] " + message);
                            }
                        }
                    );

                    currentDownloader.execute(assets);
                    appendLog("[✓] Finished: " + meta.title);

                } catch (Exception e) {
                    appendLog("[!] Error processing " + url + ": " + e.getMessage());
                }
            }

            runOnUiThread(() -> {
                isRunning = false;
                btnDownload.setEnabled(true);
                btnStop.setEnabled(false);
                txtStatus.setText("Status: Completed / Ready");
                appendLog("\n[✓] All jobs processed!");
            });
        });
    }

    private void stopDownload() {
        if (currentDownloader != null) {
            currentDownloader.stop();
        }
        isRunning = false;
        runOnUiThread(() -> {
            btnDownload.setEnabled(true);
            btnStop.setEnabled(false);
            txtStatus.setText("Status: Paused / Stopped");
            appendLog("[!] Stopped by user.");
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (currentDownloader != null) {
            currentDownloader.stop();
        }
        if (mainExecutor != null) {
            mainExecutor.shutdownNow();
        }
    }
}
