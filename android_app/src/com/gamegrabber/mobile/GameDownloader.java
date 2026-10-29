package com.gamegrabber.mobile;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;
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
    private final AtomicLong totalBytesDownloaded = new AtomicLong(0);

    private static final byte[][] PORTAL_SIGNATURES = new byte[][]{
        "web block".getBytes(), "access denied".getBytes(), "blocked site".getBytes(),
        "sophos".getBytes(), "captive portal".getBytes(), "<!doctype html".getBytes()
    };

    public GameDownloader(String baseUrl, File targetFolder, long limitBytes, boolean noVideos, int threadCount, DownloadListener listener) {
        this.baseUrl = baseUrl;
        this.targetFolder = targetFolder;
        this.limitBytes = limitBytes;
        this.noVideos = noVideos;
        this.threadCount = threadCount;
        this.listener = listener;
    }

    public void stop() {
        isStopped.set(true);
    }

    private boolean isCorrupt(File file) {
        if (!file.exists()) return false;
        long sz = file.length();
        if (sz == 0) return true;
        if (sz < 4096) {
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[1024];
                int read = fis.read(buffer);
                if (read > 0) {
                    String head = new String(buffer, 0, read).toLowerCase();
                    for (byte[] sig : PORTAL_SIGNATURES) {
                        if (head.contains(new String(sig))) return true;
                    }
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    public void purgeCorrupt(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                purgeCorrupt(f);
            } else if (!f.getName().equals("index.html")) {
                if (isCorrupt(f)) {
                    f.delete();
                }
            }
        }
    }

    private String encodeUrlPath(String relPath) {
        try {
            String[] segments = relPath.split("/");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < segments.length; i++) {
                if (i > 0) sb.append("/");
                sb.append(URLEncoder.encode(segments[i], "UTF-8").replace("+", "%20"));
            }
            return sb.toString();
        } catch (Exception e) {
            return relPath.replace(" ", "%20");
        }
    }

    public void execute(List<String> assets) {
        targetFolder.mkdirs();
        purgeCorrupt(targetFolder);

        int total = assets.size();
        AtomicInteger completed = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        for (String rel : assets) {
            if (isStopped.get()) break;

            pool.submit(() -> {
                if (isStopped.get()) return;

                String lower = rel.toLowerCase();
                if (noVideos && (lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".avi"))) {
                    int c = completed.incrementAndGet();
                    listener.onProgress(c, total, totalBytesDownloaded.get());
                    return;
                }

                File localFile = new File(targetFolder, rel.replace('/', File.separatorChar));
                File partFile = new File(localFile.getAbsolutePath() + ".part");

                if (localFile.exists() && !isCorrupt(localFile)) {
                    int c = completed.incrementAndGet();
                    listener.onProgress(c, total, totalBytesDownloaded.get());
                    return;
                }

                localFile.getParentFile().mkdirs();

                long existingSize = partFile.exists() ? partFile.length() : 0;
                try {
                    URL downloadUrl = new URL(new URL(baseUrl), encodeUrlPath(rel));
                    HttpURLConnection conn = (HttpURLConnection) downloadUrl.openConnection();
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36");
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(20000);

                    if (existingSize > 0) {
                        conn.setRequestProperty("Range", "bytes=" + existingSize + "-");
                    }

                    int code = conn.getResponseCode();
                    boolean append = false;
                    if (code == 206) {
                        append = true;
                    } else if (code == 200) {
                        append = false;
                    } else if (code == 416) { // Range not satisfiable (file already done)
                        if (partFile.exists()) partFile.renameTo(localFile);
                        int c = completed.incrementAndGet();
                        listener.onProgress(c, total, totalBytesDownloaded.get());
                        return;
                    } else if (code == 404) {
                        int c = completed.incrementAndGet();
                        listener.onProgress(c, total, totalBytesDownloaded.get());
                        return;
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
                                isStopped.set(true);
                                listener.onLog("[!] Download quota reached (" + (limitBytes / (1024*1024)) + " MB). Pausing.");
                                break;
                            }
                        }
                    }

                    if (!isStopped.get() && partFile.exists()) {
                        if (!isCorrupt(partFile)) {
                            partFile.renameTo(localFile);
                        } else {
                            partFile.delete();
                        }
                    }

                } catch (Exception e) {
                    listener.onLog("[!] Error on " + rel + ": " + e.getMessage());
                }

                int c = completed.incrementAndGet();
                listener.onProgress(c, total, totalBytesDownloaded.get());
            });
        }

        pool.shutdown();
        try {
            pool.awaitTermination(24, TimeUnit.HOURS);
        } catch (InterruptedException ignored) {}

        if (isStopped.get()) {
            listener.onComplete(false, "Download paused/stopped.");
        } else {
            listener.onComplete(true, "All assets downloaded successfully!");
        }
    }
}
