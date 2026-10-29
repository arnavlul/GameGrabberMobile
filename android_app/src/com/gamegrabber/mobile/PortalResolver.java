package com.gamegrabber.mobile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class PortalResolver {

    private static final String[] AD_DOMAINS = new String[]{
        "doubleclick", "googlesyndication", "googleads", "facebook",
        "analytics", "disqus", "twitter", "adnxs", "popads", "adservice"
    };

    public static void enableSSLBypass() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
            };
            SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
        } catch (Exception ignored) {}
    }

    private static boolean isAdUrl(String url) {
        String lower = url.toLowerCase();
        for (String ad : AD_DOMAINS) {
            if (lower.contains(ad)) return true;
        }
        return false;
    }

    public static String resolveGameUrl(String initialUrl, boolean sslBypass) {
        if (sslBypass) {
            enableSSLBypass();
        }

        // If URL already ends with a direct HTML game entry point, use it directly
        String cleanUrl = initialUrl.split("\\?")[0].split("#")[0].toLowerCase();
        if (cleanUrl.endsWith("/index.html") || cleanUrl.endsWith("/game.html")) {
            return initialUrl;
        }

        try {
            URL u = new URL(initialUrl);
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            int code = conn.getResponseCode();
            if (code >= 200 && code < 400) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder html = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    html.append(line).append("\n");
                }
                reader.close();

                String htmlStr = html.toString();

                // 1. Check if the page is already a Twine / SugarCube story or HTML game directly
                if (htmlStr.contains("<tw-storydata") || htmlStr.contains("SugarCube") || htmlStr.contains("<canvas")) {
                    return initialUrl;
                }

                // 2. Search for game iframes
                Pattern iframePattern = Pattern.compile("<iframe[^>]+(?:src|data-src)=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
                Matcher m = iframePattern.matcher(htmlStr);
                List<String> candidates = new ArrayList<>();

                while (m.find()) {
                    String src = m.group(1).trim();
                    if (src.isEmpty() || src.startsWith("javascript:") || src.startsWith("about:") || isAdUrl(src)) {
                        continue;
                    }
                    try {
                        URL resolved = new URL(u, src);
                        String full = resolved.toString();
                        // Prioritize strong game embed indicators
                        String lowerFull = full.toLowerCase();
                        if (lowerFull.contains("fgn.cdn") || lowerFull.contains("serverable") ||
                            lowerFull.contains("discretehosting") || lowerFull.contains("hwcdn.net") ||
                            lowerFull.contains("html5") || lowerFull.contains("index.html") ||
                            lowerFull.contains("game") || lowerFull.contains("embed")) {
                            return full;
                        }
                        candidates.add(full);
                    } catch (Exception ignored) {}
                }

                // 3. Search regex inside page source for direct CDN embeds (Gamcore, etc.)
                Pattern cdnPattern = Pattern.compile("https?://[^\"'\\s<>]+(?:fgn\\.cdn|discretehosting|serverable|hwcdn)[^\"'\\s<>]*index\\.html[^\"'\\s<>]*", Pattern.CASE_INSENSITIVE);
                Matcher m2 = cdnPattern.matcher(htmlStr);
                if (m2.find()) {
                    return m2.group(0);
                }

                // Fallback to first non-ad candidate iframe if present
                if (!candidates.isEmpty()) {
                    return candidates.get(0);
                }
            }
        } catch (Exception ignored) {}

        return initialUrl;
    }
}
