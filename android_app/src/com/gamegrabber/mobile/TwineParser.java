package com.gamegrabber.mobile;

import java.net.URLDecoder;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TwineParser {

    private static final Set<String> VALID_EXTENSIONS = new HashSet<>(Arrays.asList(
        ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".bmp", ".ico", ".avif",
        ".mp4", ".webm", ".ogg", ".ogv", ".mp3", ".wav", ".flac", ".m4a", ".aac", ".mov", ".mkv", ".avi",
        ".js", ".css", ".wasm", ".data", ".json", ".woff", ".woff2", ".ttf", ".otf", ".eot",
        ".rpgmvp", ".rpgmvo", ".rpgmvm", ".png_", ".ogg_", ".m4a_"
    ));

    private static final Set<String> VIDEO_EXTENSIONS = new HashSet<>(Arrays.asList(
        ".mp4", ".webm", ".avi", ".mov", ".mkv", ".m4v", ".ogv"
    ));

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

    public static String unescapeHtml(String input) {
        if (input == null || !input.contains("&")) return input;
        String s = input
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&#X27;", "'")
            .replace("&#34;", "\"")
            .replace("&#x22;", "\"")
            .replace("&#X22;", "\"")
            .replace("&lt;", "<")
            .replace("&#60;", "<")
            .replace("&#x3c;", "<")
            .replace("&#x3C;", "<")
            .replace("&gt;", ">")
            .replace("&#62;", ">")
            .replace("&#x3e;", ">")
            .replace("&#x3E;", ">");

        // Numeric decimal entities &#123;
        Matcher decMat = Pattern.compile("&#(\\d+);").matcher(s);
        StringBuffer sbDec = new StringBuffer();
        while (decMat.find()) {
            try {
                int code = Integer.parseInt(decMat.group(1));
                decMat.appendReplacement(sbDec, Matcher.quoteReplacement(String.valueOf((char) code)));
            } catch (Exception e) {
                decMat.appendReplacement(sbDec, Matcher.quoteReplacement(decMat.group(0)));
            }
        }
        decMat.appendTail(sbDec);
        s = sbDec.toString();

        // Numeric hex entities &#x1F;
        Matcher hexMat = Pattern.compile("&#[xX]([0-9a-fA-F]+);").matcher(s);
        StringBuffer sbHex = new StringBuffer();
        while (hexMat.find()) {
            try {
                int code = Integer.parseInt(hexMat.group(1), 16);
                hexMat.appendReplacement(sbHex, Matcher.quoteReplacement(String.valueOf((char) code)));
            } catch (Exception e) {
                hexMat.appendReplacement(sbHex, Matcher.quoteReplacement(hexMat.group(0)));
            }
        }
        hexMat.appendTail(sbHex);
        s = sbHex.toString();

        // Replace &amp; last
        s = s.replace("&#38;", "&")
             .replace("&#x26;", "&")
             .replace("&#X26;", "&")
             .replace("&amp;", "&");
        return s;
    }

    public static GameMeta extractMetadata(String html, String fallbackUrl) {
        return extractMetadata(html, fallbackUrl, null, null);
    }

    public static GameMeta extractMetadata(String html, String fallbackUrl, String portalTitle, String portalVersion) {
        String rawTitle = "";
        Pattern titlePat = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher tm = titlePat.matcher(html);
        if (tm.find()) {
            rawTitle = unescapeHtml(tm.group(1)).trim();
        }
        if ((rawTitle.isEmpty() || rawTitle.equalsIgnoreCase("untitled") || rawTitle.equalsIgnoreCase("index") || rawTitle.equalsIgnoreCase("twine"))
                && portalTitle != null && !portalTitle.isEmpty()) {
            rawTitle = unescapeHtml(portalTitle).trim();
        }

        // Strip portal marketing fluff
        String[] fluffPatterns = new String[]{
            "(?i)[-|–]\\s*(?:Play\\s+)?(?:Free\\s+)?Online(?:\\s+Game)?.*$",
            "(?i)[-|–]\\s*Gamcore.*$",
            "(?i)[-|–]\\s*Play\\s+on\\s+Gamcore.*$",
            "(?i)[-|–]\\s*HTML.*Game.*$",
            "(?i)[-|–]\\s*Porn\\s*Games.*$",
            "(?i)[-|–]\\s*Free.*$",
            "(?i)[-|–]\\s*Download.*$",
            "\\|.*$"
        };
        for (String pat : fluffPatterns) {
            rawTitle = rawTitle.replaceAll(pat, "").trim();
        }

        String version = null;
        Pattern vPat = Pattern.compile("[\\(\\[\\{]?(?:v(?:er)?\\.?\\s*|build\\s*)([0-9]+(?:\\.[0-9a-zA-Z_\\-]+)*)[\\)\\]\\}]?", Pattern.CASE_INSENSITIVE);
        Matcher vm = vPat.matcher(rawTitle);
        String cleanTitle = rawTitle;
        if (vm.find()) {
            version = "v" + vm.group(1).replaceAll("^[vV]", "");
            cleanTitle = (rawTitle.substring(0, vm.start()) + rawTitle.substring(vm.end()))
                .replaceAll("[\\(\\[\\{\\)\\]\\}]", "").trim();
        }

        if (version == null && portalVersion != null && !portalVersion.isEmpty()) {
            version = portalVersion;
        }

        // Search html content for version variable or JSON field (matches desktop scraper.py)
        if (version == null && html != null) {
            Matcher mVar = Pattern.compile("(?:version|game_version|gameVersion|build_num)\\s*[:=]\\s*[\"']v?([0-9]+(?:\\.[0-9a-zA-Z_\\-]+)*)[\"']", Pattern.CASE_INSENSITIVE).matcher(html);
            if (mVar.find()) {
                version = "v" + mVar.group(1).replaceAll("^[vV]", "");
            }
            if (version == null) {
                Matcher mPkg = Pattern.compile("\"version\"\\s*:\\s*\"v?([0-9]+(?:\\.[0-9a-zA-Z_\\-]+)*)\"").matcher(html);
                if (mPkg.find()) {
                    version = "v" + mPkg.group(1).replaceAll("^[vV]", "");
                }
            }
        }

        // Check URL slug for version pattern
        if (version == null && fallbackUrl != null) {
            Matcher uMat = Pattern.compile("[-_](?:v|ver)?([0-9]+(?:\\.[0-9a-zA-Z_\\-]+)+)", Pattern.CASE_INSENSITIVE).matcher(fallbackUrl);
            if (uMat.find()) {
                version = "v" + uMat.group(1).replaceAll("^[vV]", "");
            }
        }

        if (version == null) {
            version = "v1.0";
        }

        cleanTitle = cleanTitle.replaceAll("[\\\\/*?:\"<>|]", "").replaceAll("^[-_\\s.]+|[-_\\s.]+$", "").trim();
        if (cleanTitle.isEmpty() || cleanTitle.equalsIgnoreCase("untitled") || cleanTitle.equalsIgnoreCase("untitled game")
                || cleanTitle.equalsIgnoreCase("gamcore") || cleanTitle.equalsIgnoreCase("game") || cleanTitle.equalsIgnoreCase("index")) {
            String[] parts = fallbackUrl.split("\\?")[0].replaceAll("/+$", "").split("/");
            cleanTitle = parts[parts.length - 1].replaceAll("\\.(?:html?|php)$", "");
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
        path = unescapeHtml(path);
        path = path.trim().replaceAll("^['\"]+|['\"]+$", "").trim();
        if (path.isEmpty()) return null;

        String lowerStart = path.toLowerCase();
        if (lowerStart.startsWith("data:") || lowerStart.startsWith("blob:") ||
            lowerStart.startsWith("javascript:") || lowerStart.startsWith("#") ||
            lowerStart.startsWith("mailto:") || lowerStart.startsWith("about:") ||
            lowerStart.startsWith("http://") || lowerStart.startsWith("https://") ||
            lowerStart.startsWith("//")) {
            return null;
        }

        // Reject illegal / shell / code tokens (spaces and parentheses ARE allowed)
        for (char c : new char[]{'<', '>', '$', '{', '}', ';', '*', '\n', '\r', '|', '"'}) {
            if (path.indexOf(c) >= 0) return null;
        }

        // Strip query parameters and hash fragments
        path = path.split("\\?")[0].split("#")[0].trim();
        path = path.replace('\\', '/');
        if (path.isEmpty()) return null;

        // Safe percent-decoding (preserve literal '+' characters)
        try {
            path = URLDecoder.decode(path.replace("+", "%2B"), "UTF-8");
        } catch (Exception ignored) {}

        path = path.replace('\\', '/').trim();
        // Normalize '.' and '..' segments safely
        String[] rawSegs = path.split("/");
        LinkedList<String> normSegs = new LinkedList<>();
        for (String seg : rawSegs) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (!normSegs.isEmpty()) {
                    normSegs.removeLast();
                }
            } else {
                normSegs.addLast(seg);
            }
        }
        if (normSegs.isEmpty()) return null;
        StringBuilder sbPath = new StringBuilder();
        for (int i = 0; i < normSegs.size(); i++) {
            if (i > 0) sbPath.append("/");
            sbPath.append(normSegs.get(i));
        }
        path = sbPath.toString();

        // Filter out Twine/SugarCube dummy fallback placeholders
        String lower = path.toLowerCase();
        if (lower.equals("movie.ogg") || lower.endsWith("/movie.ogg") ||
            lower.equals("movie.mp4") || lower.endsWith("/movie.mp4") ||
            lower.equals("dummy.mp4") || lower.equals("video.ogg")) {
            return null;
        }

        // Check valid extension
        int dotIdx = lower.lastIndexOf('.');
        if (dotIdx <= 0) return null;
        String ext = lower.substring(dotIdx);
        if (!VALID_EXTENSIONS.contains(ext)) {
            return null;
        }

        return path;
    }

    private static boolean isVideoPath(String path) {
        int dotIdx = path.lastIndexOf('.');
        if (dotIdx < 0) return false;
        return VIDEO_EXTENSIONS.contains(path.substring(dotIdx).toLowerCase());
    }

    private static String extractAttr(String tagAttrs, String attrName) {
        Matcher m = Pattern.compile("\\b" + attrName + "\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE).matcher(tagAttrs);
        if (m.find()) {
            return unescapeHtml(m.group(1));
        }
        return null;
    }

    public static List<String> extractAssets(String html) {
        List<String> result = new ArrayList<>();
        Set<String> seenLower = new HashSet<>();

        java.util.function.Consumer<String> addRef = (ref) -> {
            String c = cleanRelativePath(ref);
            if (c != null) {
                String key = c.toLowerCase();
                if (!seenLower.contains(key)) {
                    seenLower.add(key);
                    result.add(c);
                }
            }
        };

        String unescapedHtml = unescapeHtml(html);

        // Detect SugarCube setup.ImagePath and setup.SoundPath
        String imagePrefix = "images/";
        String soundPrefix = "sounds/";
        Matcher mImgPrefix = Pattern.compile("setup\\.ImagePath\\s*=\\s*(?:setup\\.Path\\s*\\+\\s*)?[\"']([^\"']*)[\"']").matcher(unescapedHtml);
        if (mImgPrefix.find()) {
            imagePrefix = mImgPrefix.group(1).replaceAll("^/+", "");
        }
        Matcher mSndPrefix = Pattern.compile("setup\\.SoundPath\\s*=\\s*(?:setup\\.Path\\s*\\+\\s*)?[\"']([^\"']*)[\"']").matcher(unescapedHtml);
        if (mSndPrefix.find()) {
            soundPrefix = mSndPrefix.group(1).replaceAll("^/+", "");
        }
        final String fImgPrefix = imagePrefix;
        final String fSndPrefix = soundPrefix;

        // 1. Extract top-level CSS url() and head/body tags before <tw-passagedata> first
        int storyStartIdx = html.toLowerCase().indexOf("<tw-passagedata");
        String headerHtml = storyStartIdx > 0 ? html.substring(0, storyStartIdx) : html;
        Matcher headTagMat = Pattern.compile("<(?:script|link|img|video|audio|source|track)[^>]+(?:src|href|poster|data-src)\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(headerHtml);
        while (headTagMat.find()) {
            addRef.accept(headTagMat.group(1));
        }
        Matcher headCssMat = Pattern.compile("url\\s*\\(\\s*[\"']?([^\"'\\)]+)[\"']?\\s*\\)", Pattern.CASE_INSENSITIVE).matcher(headerHtml);
        while (headCssMat.find()) {
            addRef.accept(headCssMat.group(1));
        }

        // 2. Twine / SugarCube Story Passages (<tw-storydata> or Twine 1 tiddlers)
        Pattern storyDataPat = Pattern.compile("<tw-storydata([^>]*)>(.*?)</tw-storydata>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher sdm = storyDataPat.matcher(html);
        if (sdm.find()) {
            String storyAttrs = sdm.group(1);
            String storyContent = sdm.group(2);

            String startPid = extractAttr(storyAttrs, "startnode");
            if (startPid == null) startPid = "1";

            // Extract passages regardless of attribute ordering
            Map<String, String> pidToName = new LinkedHashMap<>();
            Map<String, String> nameToBody = new LinkedHashMap<>();
            Map<String, List<String>> storyGraph = new HashMap<>();

            Pattern passPat = Pattern.compile("<tw-passagedata([^>]*)>(.*?)</tw-passagedata>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher pm = passPat.matcher(storyContent);
            int fallbackIdx = 1;
            while (pm.find()) {
                String pAttrs = pm.group(1);
                String pRawBody = pm.group(2);
                String pid = extractAttr(pAttrs, "pid");
                String name = extractAttr(pAttrs, "name");
                if (name == null) continue;
                if (pid == null) pid = String.valueOf(fallbackIdx++);

                String pUnescaped = unescapeHtml(pRawBody);
                pidToName.put(pid, name);
                nameToBody.put(name, pUnescaped);

                // Extract outgoing passage links
                List<String> links = new ArrayList<>();
                // [[Link|Target]], [[Link->Target]], [[Target<-Link]], [[Target][Setter]]
                Matcher lm = Pattern.compile("\\[\\[([^\\]]+)\\]\\]").matcher(pUnescaped);
                while (lm.find()) {
                    String inner = lm.group(1);
                    if (inner.toLowerCase().startsWith("img[")) continue;
                    String mainPart = inner.split("\\]\\[")[0];
                    String target;
                    if (mainPart.contains("|")) {
                        target = mainPart.substring(mainPart.indexOf('|') + 1);
                    } else if (mainPart.contains("->")) {
                        target = mainPart.substring(mainPart.lastIndexOf("->") + 2);
                    } else if (mainPart.contains("<-")) {
                        target = mainPart.substring(0, mainPart.indexOf("<-"));
                    } else {
                        target = mainPart;
                    }
                    links.add(target.trim());
                }

                // <<link "Text" "Target">> or <<button "Text" "Target">>
                Matcher macroLink = Pattern.compile("<<(?:link|button)\\s+[\"'][^\"']+[\"'](?:\\s+[\"']([^\"']+)[\"'])?").matcher(pUnescaped);
                while (macroLink.find()) {
                    if (macroLink.group(1) != null) {
                        links.add(macroLink.group(1).trim());
                    }
                }

                // data-passage="Target"
                Matcher dataPass = Pattern.compile("data-passage\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(pUnescaped);
                while (dataPass.find()) {
                    links.add(dataPass.group(1).trim());
                }

                storyGraph.put(name, links);
            }

            // Build BFS ordered passage list
            List<String> orderedPassages = new ArrayList<>();
            Set<String> visitedPassages = new HashSet<>();

            String[] specialPassages = new String[]{
                "StoryInit", "StoryBanner", "StoryCaption", "StoryMenu", "PassageHeader", "PassageFooter"
            };
            for (String sp : specialPassages) {
                if (nameToBody.containsKey(sp) && !visitedPassages.contains(sp)) {
                    visitedPassages.add(sp);
                    orderedPassages.add(sp);
                }
            }

            String startName = pidToName.getOrDefault(startPid, "Lets Go");
            Queue<String> queue = new LinkedList<>();
            if (nameToBody.containsKey(startName) && !visitedPassages.contains(startName)) {
                visitedPassages.add(startName);
                queue.add(startName);
            }

            while (!queue.isEmpty()) {
                String curr = queue.poll();
                orderedPassages.add(curr);
                List<String> nextList = storyGraph.get(curr);
                if (nextList != null) {
                    for (String nxt : nextList) {
                        if (nameToBody.containsKey(nxt) && !visitedPassages.contains(nxt)) {
                            visitedPassages.add(nxt);
                            queue.add(nxt);
                        }
                    }
                }
            }

            // Append any unvisited passages (secret branches / macro jumps)
            for (String pName : nameToBody.keySet()) {
                if (!visitedPassages.contains(pName)) {
                    visitedPassages.add(pName);
                    orderedPassages.add(pName);
                }
            }

            // Extract assets per passage (Images/Audio first, then Videos)
            for (String pName : orderedPassages) {
                String pText = nameToBody.get(pName);
                if (pText == null) continue;

                List<String> pImgs = new ArrayList<>();
                List<String> pVids = new ArrayList<>();

                java.util.function.Consumer<String> collectPassageRef = (rawRef) -> {
                    String c = cleanRelativePath(rawRef);
                    if (c != null) {
                        if (isVideoPath(c)) {
                            pVids.add(c);
                        } else {
                            pImgs.add(c);
                        }
                    }
                };

                // HTML media attributes (src, href, poster, archive, data-src)
                Matcher attrMat = Pattern.compile("(?:src|href|poster|archive|data-src)\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(pText);
                while (attrMat.find()) {
                    collectPassageRef.accept(attrMat.group(1));
                }

                // SugarCube [img[...]] syntax
                Matcher imgMacroMat = Pattern.compile("\\[img\\[(.*?)\\]\\]", Pattern.CASE_INSENSITIVE).matcher(pText);
                while (imgMacroMat.find()) {
                    String raw = imgMacroMat.group(1);
                    String firstBlock = raw.split("\\]\\[")[0];
                    if (firstBlock.contains("|")) {
                        firstBlock = firstBlock.substring(firstBlock.indexOf('|') + 1);
                    }
                    String c = cleanRelativePath(firstBlock);
                    if (c != null) {
                        if (!c.contains("/") && !c.toLowerCase().startsWith("images/") && !c.toLowerCase().startsWith("videos/")) {
                            c = cleanRelativePath(fImgPrefix + c);
                        }
                        if (c != null) {
                            if (isVideoPath(c)) pVids.add(c);
                            else pImgs.add(c);
                        }
                    }
                }

                // Audio macros <<cacheaudio "id" "path">>
                Matcher audMat = Pattern.compile("<<cacheaudio\\s+[\"'][^\"']+[\"']\\s+[\"']([^\"']+)[\"']").matcher(pText);
                while (audMat.find()) {
                    String rawAud = audMat.group(1);
                    if (!rawAud.contains("/")) rawAud = fSndPrefix + rawAud;
                    collectPassageRef.accept(rawAud);
                }

                // setup.ImagePath + "..." and setup.SoundPath + "..."
                Matcher setupImgMat = Pattern.compile("setup\\.ImagePath\\s*\\+\\s*[\"']([^\"']+)[\"']").matcher(pText);
                while (setupImgMat.find()) {
                    collectPassageRef.accept(fImgPrefix + setupImgMat.group(1).replaceAll("^/+", ""));
                }
                Matcher setupSndMat = Pattern.compile("setup\\.SoundPath\\s*\\+\\s*[\"']([^\"']+)[\"']").matcher(pText);
                while (setupSndMat.find()) {
                    collectPassageRef.accept(fSndPrefix + setupSndMat.group(1).replaceAll("^/+", ""));
                }

                // CSS url(...) inside passage
                Matcher pCssMat = Pattern.compile("url\\s*\\(\\s*[\"']?([^\"'\\)]+)[\"']?\\s*\\)", Pattern.CASE_INSENSITIVE).matcher(pText);
                while (pCssMat.find()) {
                    collectPassageRef.accept(pCssMat.group(1));
                }

                for (String img : pImgs) addRef.accept(img);
                for (String vid : pVids) addRef.accept(vid);
            }
        } else {
            // Twine 1 format (Sugarcane / Jonah / Responsive): <div tiddler="...">...</div>
            Pattern tiddlerPat = Pattern.compile("<div[^>]+tiddler=[\"']([^\"']+)[\"'][^>]*>(.*?)</div>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher tm = tiddlerPat.matcher(html);
            while (tm.find()) {
                String pText = unescapeHtml(tm.group(2));
                Matcher attrMat = Pattern.compile("(?:src|href|poster|archive|data-src)\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(pText);
                while (attrMat.find()) addRef.accept(attrMat.group(1));

                Matcher imMat = Pattern.compile("\\[img\\[(.*?)\\]\\]", Pattern.CASE_INSENSITIVE).matcher(pText);
                while (imMat.find()) {
                    String raw = imMat.group(1).split("\\]\\[")[0];
                    if (raw.contains("|")) raw = raw.substring(raw.indexOf('|') + 1);
                    addRef.accept(raw);
                }
            }
        }

        // Ren'Py Web engine assets
        String lowerHtmlFull = html.toLowerCase();
        if (lowerHtmlFull.contains("renpy") || lowerHtmlFull.contains("game.zip")) {
            String[] renpyFiles = new String[]{
                "game.zip", "game.zip.001", "game.zip.002",
                "renpy.js", "renpy-pre.js", "renpy.wasm", "renpy.data",
                "pyapp-data.js", "pythonhome-data.js",
                "package.json", "progressive_download.txt",
                "web-presplash.png", "web-presplash.jpg",
                "renpy.png", "icons/icon-192x192.png", "icons/icon-512x512.png"
            };
            for (String rf : renpyFiles) {
                addRef.accept(rf);
            }
        }

        // RPG Maker MV/MZ engine assets
        if (lowerHtmlFull.contains("rpg_core.js") || lowerHtmlFull.contains("rmmz_core.js") || lowerHtmlFull.contains("data/system.json")) {
            String[] rpgData = new String[]{
                "data/System.json", "data/Actors.json", "data/Classes.json", "data/Skills.json",
                "data/Items.json", "data/Weapons.json", "data/Armors.json", "data/Enemies.json",
                "data/Troops.json", "data/States.json", "data/Animations.json", "data/Tilesets.json",
                "data/CommonEvents.json", "data/MapInfos.json", "js/plugins.js"
            };
            for (String rdf : rpgData) {
                addRef.accept(rdf);
            }
        }

        // 3. Global scan across full unescaped HTML for any remaining tags, CSS url(), or quoted asset paths
        extractAssetsFromSecondaryFile(unescapedHtml, addRef);

        return result;
    }

    public static List<String> extractAssetsFromSecondaryFile(String content) {
        List<String> result = new ArrayList<>();
        Set<String> seenLower = new HashSet<>();
        extractAssetsFromSecondaryFile(unescapeHtml(content), (ref) -> {
            String c = cleanRelativePath(ref);
            if (c != null) {
                String k = c.toLowerCase();
                if (!seenLower.contains(k)) {
                    seenLower.add(k);
                    result.add(c);
                }
            }
        });
        return result;
    }

    private static void extractAssetsFromSecondaryFile(String unescapedContent, java.util.function.Consumer<String> addRef) {
        if (unescapedContent == null || unescapedContent.isEmpty()) return;

        // Standard HTML attributes
        Matcher tagPattern = Pattern.compile("<(?:script|link|img|video|audio|source|track)[^>]+(?:src|href|poster|data-src)\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(unescapedContent);
        while (tagPattern.find()) {
            addRef.accept(tagPattern.group(1));
        }

        // CSS url(...)
        Matcher urlMat = Pattern.compile("url\\s*\\(\\s*[\"']?([^\"'\\)]+)[\"']?\\s*\\)", Pattern.CASE_INSENSITIVE).matcher(unescapedContent);
        while (urlMat.find()) {
            addRef.accept(urlMat.group(1));
        }

        // Quoted relative paths with common asset directories (allows spaces and parentheses!)
        Matcher pathMat = Pattern.compile("[\"']((?:Images|Videos|Audio|Sounds|Music|Media|Movies|Assets|img|video|videos|audio|sounds|music|media|assets|data|fonts|css|js|scripts)/[^\"'<>\\r\\n]+?\\.[a-zA-Z0-9]{2,5})[\"']", Pattern.CASE_INSENSITIVE).matcher(unescapedContent);
        while (pathMat.find()) {
            addRef.accept(pathMat.group(1));
        }

        // Ren'Py package.json parsing ("files": [...])
        if (unescapedContent.contains("\"files\"")) {
            Matcher pkgMat = Pattern.compile("\"([^\"]+?\\.[a-zA-Z0-9]{2,5})\"").matcher(unescapedContent);
            while (pkgMat.find()) {
                addRef.accept(pkgMat.group(1));
            }
        }

        // Ren'Py progressive_download.txt line parsing
        if (unescapedContent.contains(".opus") || unescapedContent.contains(".ogg") || unescapedContent.contains(".png")) {
            String[] lines = unescapedContent.split("[\\r\\n]+");
            for (String l : lines) {
                String cl = l.trim().split("#")[0].trim();
                if (!cl.isEmpty() && cl.contains(".") && !cl.contains(" ")) {
                    addRef.accept(cl);
                }
            }
        }
    }
}
