package com.gamegrabber.mobile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class PortalResolver {

    public static final String USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    private static final String[] AD_DOMAINS = new String[]{
        "doubleclick", "googlesyndication", "googleads", "facebook",
        "analytics", "disqus", "twitter", "adnxs", "popads", "adservice",
        "exoclick", "juicyads", "trafficjunky", "realsrv", "magsrv"
    };

    public static class PortalResult {
        public final String gameUrl;
        public final String portalTitle;
        public final String portalVersion;
        public final String preFetchedHtml;

        public PortalResult(String gameUrl, String portalTitle, String portalVersion, String preFetchedHtml) {
            this.gameUrl = normalizeBaseGameUrl(gameUrl);
            this.portalTitle = portalTitle;
            this.portalVersion = portalVersion;
            this.preFetchedHtml = preFetchedHtml;
        }
    }

    public static void enableSSLBypass() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
        } catch (Exception ignored) {}
    }

    public static String normalizeBaseGameUrl(String url) {
        if (url == null) return "";
        url = url.trim();
        String[] qSplit = url.split("\\?", 2);
        String pathPart = qSplit[0];
        String queryPart = qSplit.length > 1 ? "?" + qSplit[1] : "";

        // Ensure directory URLs without a file extension end with '/' so relative URLs resolve inside the directory
        try {
            URL u = new URL(pathPart);
            String path = u.getPath();
            if (path == null || path.isEmpty()) {
                pathPart = pathPart + "/";
            } else if (!path.endsWith("/")) {
                String lastSeg = path.substring(path.lastIndexOf('/') + 1);
                if (!lastSeg.contains(".")) {
                    pathPart = pathPart + "/";
                }
            }
        } catch (Exception ignored) {}

        return pathPart + queryPart;
    }

    public static HttpURLConnection openConnectionWithRedirects(String urlStr, String referer, Map<String, String> extraHeaders, int connectTimeoutMs, int readTimeoutMs) throws Exception {
        String currentUrl = urlStr;
        for (int redirectCount = 0; redirectCount < 6; redirectCount++) {
            URL u = new URL(currentUrl);
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            if (referer != null && !referer.isEmpty()) {
                conn.setRequestProperty("Referer", referer);
            }
            if (extraHeaders != null) {
                for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);

            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.isEmpty()) {
                    throw new Exception("Redirect (" + code + ") without Location header");
                }
                currentUrl = new URL(u, location).toString();
                continue;
            }
            return conn;
        }
        throw new Exception("Too many redirects for URL: " + urlStr);
    }

    public static String readStreamAsString(HttpURLConnection conn) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[16384];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        }
    }

    private static boolean isAdUrl(String url) {
        String lower = url.toLowerCase();
        for (String ad : AD_DOMAINS) {
            if (lower.contains(ad)) return true;
        }
        return false;
    }

    public static PortalResult resolve(String initialUrl, boolean sslBypass) {
        if (sslBypass) {
            enableSSLBypass();
        }

        String cleanUrl = initialUrl.split("\\?")[0].split("#")[0].toLowerCase();
        if (cleanUrl.endsWith("/index.html") || cleanUrl.endsWith("/game.html") || cleanUrl.endsWith(".html") || cleanUrl.endsWith(".htm")) {
            return new PortalResult(initialUrl, null, null, null);
        }

        try {
            HttpURLConnection conn = openConnectionWithRedirects(initialUrl, null, null, 15000, 20000);
            String finalUrl = conn.getURL().toString();
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                String htmlStr = readStreamAsString(conn);
                conn.disconnect();

                TwineParser.GameMeta portalMeta = TwineParser.extractMetadata(htmlStr, finalUrl);

                // 1. Check if the page is already a Twine / SugarCube story or direct HTML game
                String lowerHtml = htmlStr.toLowerCase();
                if (lowerHtml.contains("<tw-storydata") || lowerHtml.contains("sugarcube") ||
                    lowerHtml.contains("id=\"store-area\"") || lowerHtml.contains("id=\"storearea\"") ||
                    lowerHtml.contains("c2runtime.js") || lowerHtml.contains("c3runtime.js") ||
                    lowerHtml.contains("rpg_core.js") || lowerHtml.contains("rmmz_core.js")) {
                    return new PortalResult(finalUrl, portalMeta.title, portalMeta.version, htmlStr);
                }

                URL baseUrlObj = new URL(finalUrl);

                // 2. Search for game iframes / embeds
                Pattern iframePattern = Pattern.compile("<(?:iframe|embed|frame)[^>]+(?:src|data-src|data-lazy-src)\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
                Matcher m = iframePattern.matcher(htmlStr);
                List<String> candidates = new ArrayList<>();

                while (m.find()) {
                    String src = TwineParser.unescapeHtml(m.group(1)).trim();
                    if (src.isEmpty() || src.startsWith("javascript:") || src.startsWith("about:") || isAdUrl(src)) {
                        continue;
                    }
                    try {
                        URL resolved = new URL(baseUrlObj, src);
                        String full = resolved.toString();
                        String lowerFull = full.toLowerCase();
                        if (lowerFull.contains("fgn.cdn") || lowerFull.contains("serverable") ||
                            lowerFull.contains("discretehosting") || lowerFull.contains("hwcdn.net") ||
                            lowerFull.contains("itch.zone") || lowerFull.contains("ungrounded.net") ||
                            lowerFull.contains("html5") || lowerFull.contains("index.html") ||
                            lowerFull.contains("flashes") || lowerFull.contains("game") || lowerFull.contains("embed")) {
                            return new PortalResult(full, portalMeta.title, portalMeta.version, null);
                        }
                        candidates.add(full);
                    } catch (Exception ignored) {}
                }

                // 3. Search regex inside page source for direct CDN embeds
                Pattern cdnPattern = Pattern.compile("(?:https?:)?//[^\"'\\s<>]+(?:fgn\\.cdn|discretehosting|serverable|hwcdn\\.net|itch\\.zone)[^\"'\\s<>]*index\\.html[^\"'\\s<>]*", Pattern.CASE_INSENSITIVE);
                Matcher m2 = cdnPattern.matcher(htmlStr);
                if (m2.find()) {
                    String rawMatch = TwineParser.unescapeHtml(m2.group(0));
                    String resolvedCdn = new URL(baseUrlObj, rawMatch).toString();
                    return new PortalResult(resolvedCdn, portalMeta.title, portalMeta.version, null);
                }

                // 4. Fallback to first non-ad candidate iframe if present
                if (!candidates.isEmpty()) {
                    return new PortalResult(candidates.get(0), portalMeta.title, portalMeta.version, null);
                }

                // 5. No iframe found -> treat initial page itself as the HTML game
                return new PortalResult(finalUrl, portalMeta.title, portalMeta.version, htmlStr);
            }
        } catch (Exception ignored) {}

        return new PortalResult(initialUrl, null, null, null);
    }

    public static String resolveGameUrl(String initialUrl, boolean sslBypass) {
        return resolve(initialUrl, sslBypass).gameUrl;
    }
}
