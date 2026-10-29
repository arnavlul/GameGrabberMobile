package com.gamegrabber.mobile;

import java.net.URLDecoder;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TwineParser {

    public static class GameMeta {
        public String title;
        public String version;
        public String folderName;

        public GameMeta(String title, String version, String folderName) {
            this.title = title;
            this.version = version;
            this.folderName = folderName;
        }
    }

    public static GameMeta extractMetadata(String html, String fallbackUrl) {
        String rawTitle = "";
        Pattern titlePat = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher tm = titlePat.matcher(html);
        if (tm.find()) {
            rawTitle = tm.group(1).trim();
        }

        // Fluff removal
        rawTitle = rawTitle.replaceAll("(?i)[-|]\\s*Gamcore.*$", "");
        rawTitle = rawTitle.replaceAll("(?i)[-|]\\s*Play\\s+on\\s+Gamcore.*$", "");
        rawTitle = rawTitle.replaceAll("(?i)[-|]\\s*HTML.*Game.*$", "");
        rawTitle = rawTitle.replaceAll("(?i)[-|]\\s*Porn\\s*Games.*$", "");
        rawTitle = rawTitle.replaceAll("(?i)[-|]\\s*Free.*$", "");
        rawTitle = rawTitle.replaceAll("(?i)[-|]\\s*Download.*$", "").trim();

        String version = "v1.0";
        Pattern vPat = Pattern.compile("[\\(\\[\\{]?(?:v(?:er)?\\.?\\s*|build\\s*)([0-9]+(?:\\.[0-9a-zA-Z_\\-]+)*)[\\)\\]\\}]?", Pattern.CASE_INSENSITIVE);
        Matcher vm = vPat.matcher(rawTitle);
        String cleanTitle = rawTitle;
        if (vm.find()) {
            version = "v" + vm.group(1).replaceAll("^[vV]", "");
            cleanTitle = rawTitle.substring(0, vm.start()) + rawTitle.substring(vm.end());
            cleanTitle = cleanTitle.replaceAll("[\\(\\[\\{\\)\\]\\}]", "").trim();
        }

        cleanTitle = cleanTitle.replaceAll("[\\\\/*?:\"<>|]", "").trim();
        if (cleanTitle.isEmpty() || cleanTitle.equalsIgnoreCase("untitled") || cleanTitle.equalsIgnoreCase("game")) {
            String[] parts = fallbackUrl.split("\\?")[0].replaceAll("/+$", "").split("/");
            cleanTitle = parts[parts.length - 1].replaceAll("\\.[a-zA-Z0-9]+$", "");
            if (cleanTitle.equalsIgnoreCase("index") && parts.length > 1) {
                cleanTitle = parts[parts.length - 2];
            }
            cleanTitle = cleanTitle.replace("-", " ").replace("_", " ");
        }

        cleanTitle = cleanTitle.replaceAll("\\s+", " ").trim();
        String folderName = cleanTitle.replace(" ", "_") + "-" + version;
        return new GameMeta(cleanTitle, version, folderName);
    }

    public static String cleanRelativePath(String path) {
        if (path == null) return null;
        path = path.trim().replaceAll("^['\"]|['\"]$", "");
        if (path.isEmpty() || path.startsWith("data:") || path.startsWith("blob:") ||
            path.startsWith("javascript:") || path.startsWith("#") || path.startsWith("mailto:")) {
            return null;
        }

        // Reject illegal / injection characters
        for (char c : new char[]{'<', '>', '$', '{', '}', ';', '+', '*', '\n', '\r', '|', '"'}) {
            if (path.indexOf(c) >= 0) return null;
        }

        // Strip query parameters and hash fragments
        path = path.split("\\?")[0].split("#")[0];
        path = path.replace('\\', '/').replaceAll("^/+", "");
        if (path.isEmpty() || path.contains("..")) return null;

        // Skip dummy fallbacks
        String lower = path.toLowerCase();
        if (lower.equals("movie.ogg") || lower.equals("movie.mp4") || lower.equals("dummy.mp4")) {
            return null;
        }

        try {
            path = URLDecoder.decode(path, "UTF-8");
        } catch (Exception ignored) {}

        return path;
    }

    public static List<String> extractAssets(String html) {
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // Helper
        java.util.function.Consumer<String> addRef = (ref) -> {
            String c = cleanRelativePath(ref);
            if (c != null && !seen.contains(c)) {
                String lower = c.toLowerCase();
                if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                    lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".svg") ||
                    lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".ogg") ||
                    lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".js") ||
                    lower.endsWith(".css") || lower.endsWith(".wasm") || lower.endsWith(".data") ||
                    lower.endsWith(".json") || lower.endsWith(".woff") || lower.endsWith(".woff2") ||
                    c.contains("/")) {
                    seen.add(c);
                    result.add(c);
                }
            }
        };

        // 1. Standard HTML tags
        Pattern tagPattern = Pattern.compile("<(?:script|link|img|video|audio|source|track)[^>]+(?:src|href|poster)=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher tm = tagPattern.matcher(html);
        while (tm.find()) {
            addRef.accept(tm.group(1));
        }

        // 2. Twine / SugarCube Story Passages
        Pattern storyDataPat = Pattern.compile("<tw-storydata[^>]*>(.*?)</tw-storydata>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher sdm = storyDataPat.matcher(html);
        if (sdm.find()) {
            String storyContent = sdm.group(1);
            String storyTag = sdm.group(0);

            // Extract startnode
            String startPid = null;
            Matcher snm = Pattern.compile("startnode=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(storyTag);
            if (snm.find()) {
                startPid = snm.group(1);
            }

            // Map passages: pid -> text and name -> pid
            Map<String, String> pidToText = new HashMap<>();
            Map<String, String> nameToPid = new HashMap<>();
            Map<String, List<String>> pidToLinks = new HashMap<>();

            Pattern passPattern = Pattern.compile("<tw-passagedata[^>]*pid=[\"']([^\"']+)[\"'][^>]*name=[\"']([^\"']+)[\"'][^>]*>(.*?)</tw-passagedata>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher pm = passPattern.matcher(storyContent);
            while (pm.find()) {
                String pid = pm.group(1);
                String name = pm.group(2);
                String pText = pm.group(3);
                pidToText.put(pid, pText);
                nameToPid.put(name, pid);

                // Collect links [[target]]
                List<String> links = new ArrayList<>();
                Matcher lm = Pattern.compile("\\[\\[(?:[^\\|\\]]*\\|)?([^\\]]+)\\]\\]").matcher(pText);
                while (lm.find()) {
                    links.add(lm.group(1).trim());
                }
                pidToLinks.put(pid, links);
            }

            // BFS Traversal
            Queue<String> queue = new LinkedList<>();
            Set<String> visitedPids = new HashSet<>();
            if (startPid != null && pidToText.containsKey(startPid)) {
                queue.add(startPid);
            }

            while (!queue.isEmpty()) {
                String pid = queue.poll();
                if (visitedPids.contains(pid)) continue;
                visitedPids.add(pid);

                String pText = pidToText.get(pid);
                if (pText != null) {
                    // Extract [img[...]]
                    Matcher im = Pattern.compile("\\[img\\[(?:[^\\|\\]]*\\|)?([^\\]]+)\\]\\]", Pattern.CASE_INSENSITIVE).matcher(pText);
                    while (im.find()) addRef.accept(im.group(1));

                    // Extract src / archive
                    Matcher am = Pattern.compile("(?:src|archive|poster)=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(pText);
                    while (am.find()) addRef.accept(am.group(1));

                    // Add links
                    List<String> links = pidToLinks.get(pid);
                    if (links != null) {
                        for (String targetName : links) {
                            String targetPid = nameToPid.get(targetName);
                            if (targetPid != null && !visitedPids.contains(targetPid)) {
                                queue.add(targetPid);
                            }
                        }
                    }
                }
            }

            // Remaining unvisited passages
            for (Map.Entry<String, String> entry : pidToText.entrySet()) {
                if (!visitedPids.contains(entry.getKey())) {
                    String pText = entry.getValue();
                    Matcher im = Pattern.compile("\\[img\\[(?:[^\\|\\]]*\\|)?([^\\]]+)\\]\\]", Pattern.CASE_INSENSITIVE).matcher(pText);
                    while (im.find()) addRef.accept(im.group(1));

                    Matcher am = Pattern.compile("(?:src|archive|poster)=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(pText);
                    while (am.find()) addRef.accept(am.group(1));
                }
            }
        }

        // 3. Global CSS url() & JS media paths
        Matcher urlMat = Pattern.compile("url\\s*\\(\\s*[\"']?([^\"'\\)]+)[\"']?\\s*\\)", Pattern.CASE_INSENSITIVE).matcher(html);
        while (urlMat.find()) {
            addRef.accept(urlMat.group(1));
        }

        Matcher pathMat = Pattern.compile("[\"']((?:Images|Videos|Audio|img|sound|assets)/[^\"'>\\s]+?\\.(?:png|jpe?g|gif|webp|mp4|webm|ogg|mp3|wav))[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
        while (pathMat.find()) {
            addRef.accept(pathMat.group(1));
        }

        return result;
    }
}
