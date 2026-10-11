package io.jenkins.plugins.asyntai;

import java.io.PrintStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares documentation files with the knowledge base and sends the
 * difference. Has no Jenkins dependency, so the rules are easy to test.
 *
 * <p>The entry title is the file path with the title prefix in front. The
 * next run finds the entry again by that title.
 */
public final class KnowledgeSync {

    /** Shortest text the service accepts. */
    static final int MIN_CONTENT_LENGTH = 10;

    private KnowledgeSync() {}

    /** One file to send: its path relative to the root folder, and its raw text. */
    public static final class SourceFile {
        private final String path;
        private final String text;

        public SourceFile(String path, String text) {
            this.path = path;
            this.text = text;
        }

        public String getPath() {
            return path;
        }

        public String getText() {
            return text;
        }
    }

    /** Settings for one run. */
    public static final class Options {
        String titlePrefix = "";
        String websiteId = "";
        boolean prune;
        boolean dryRun;

        public Options titlePrefix(String value) {
            this.titlePrefix = value == null ? "" : value;
            return this;
        }

        public Options websiteId(String value) {
            this.websiteId = value == null ? "" : value.trim();
            return this;
        }

        public Options prune(boolean value) {
            this.prune = value;
            return this;
        }

        public Options dryRun(boolean value) {
            this.dryRun = value;
            return this;
        }
    }

    /** One line of the build summary. */
    public static final class Row implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String result;
        private final String title;

        public Row(String result, String title) {
            this.result = result;
            this.title = title;
        }

        public String getResult() {
            return result;
        }

        public String getTitle() {
            return title;
        }
    }

    /** Counts and rows of one run. Kept with the build, so it must stay serializable. */
    public static final class Result implements Serializable {
        private static final long serialVersionUID = 1L;

        int added;
        int updated;
        int unchanged;
        int deleted;
        int skipped;
        int failed;
        private final List<Row> rows = new ArrayList<>();

        public int getAdded() {
            return added;
        }

        public int getUpdated() {
            return updated;
        }

        public int getUnchanged() {
            return unchanged;
        }

        public int getDeleted() {
            return deleted;
        }

        public int getSkipped() {
            return skipped;
        }

        public int getFailed() {
            return failed;
        }

        public List<Row> getRows() {
            return Collections.unmodifiableList(rows);
        }

        void row(String result, String title) {
            rows.add(new Row(result, title));
        }
    }

    /**
     * Prepares raw file text the same way on every platform: drops a byte
     * order mark, turns CRLF into LF, removes a leading YAML front matter
     * block and trims. A Windows checkout of an unchanged file then compares
     * equal to the stored entry.
     */
    static String clean(String raw) {
        String text = raw == null ? "" : raw;
        if (!text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK) {
            text = text.substring(1);
        }
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        if (text.startsWith("---")) {
            int end = text.indexOf("\n---", 3);
            if (end != -1) {
                int after = text.indexOf('\n', end + 1);
                text = after == -1 ? "" : text.substring(after + 1);
            }
        }
        return text.strip();
    }

    private static final char BYTE_ORDER_MARK = (char) 0xFEFF;

    private static final Pattern HTML_DROP = Pattern.compile(
            "<!--.*?-->|<(script|style|noscript|svg|template|head|nav|footer)\\b[^>]*>.*?</\\1\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HTML_BREAK = Pattern.compile(
            "<(br|/p|/div|/li|/ul|/ol|/tr|/td|/th|/dt|/dd|/h[1-6]|/header|/main|/section|/article|/aside"
                    + "|/pre|/blockquote|/table|/figcaption)\\b[^>]*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern HTML_ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);");
    private static final Pattern SPACES = Pattern.compile("[ \\t\\u00A0]+");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n\\s*\\n\\s*\\n+");
    private static final Pattern SPACE_BEFORE_PUNCTUATION = Pattern.compile(" +([.,;:!?)])");

    /** True for files whose markup should be turned into plain text before sending. */
    static boolean isHtml(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".html") || lower.endsWith(".htm");
    }

    /**
     * Turns an HTML page into readable text: drops scripts, styles and
     * comments, keeps block breaks as new lines, removes tags and decodes
     * entities. The chatbot answers from prose; markup only adds noise.
     */
    static String htmlToText(String html) {
        String text = html == null ? "" : html.replace("\r\n", "\n").replace('\r', '\n');
        text = HTML_DROP.matcher(text).replaceAll(" ");
        text = HTML_BREAK.matcher(text).replaceAll("\n");
        text = HTML_TAG.matcher(text).replaceAll(" ");
        Matcher entity = HTML_ENTITY.matcher(text);
        StringBuilder decoded = new StringBuilder();
        while (entity.find()) {
            entity.appendReplacement(decoded, Matcher.quoteReplacement(decodeEntity(entity.group(1))));
        }
        entity.appendTail(decoded);
        StringBuilder lines = new StringBuilder();
        for (String line : decoded.toString().split("\n", -1)) {
            String tidy = SPACES.matcher(line).replaceAll(" ").strip();
            tidy = SPACE_BEFORE_PUNCTUATION.matcher(tidy).replaceAll("$1");
            lines.append(tidy).append('\n');
        }
        return BLANK_LINES.matcher(lines.toString()).replaceAll("\n\n").strip();
    }

    private static String decodeEntity(String name) {
        try {
            if (name.startsWith("#x") || name.startsWith("#X")) {
                return new String(Character.toChars(Integer.parseInt(name.substring(2), 16)));
            }
            if (name.startsWith("#")) {
                return new String(Character.toChars(Integer.parseInt(name.substring(1))));
            }
        } catch (IllegalArgumentException e) {
            return "&" + name + ";";
        }
        switch (name) {
            case "amp":
                return "&";
            case "lt":
                return "<";
            case "gt":
                return ">";
            case "quot":
                return "\"";
            case "apos":
                return "'";
            case "nbsp":
                return " ";
            case "mdash":
                return "—";
            case "ndash":
                return "–";
            case "hellip":
                return "…";
            case "copy":
                return "©";
            case "rarr":
                return "→";
            default:
                return "&" + name + ";";
        }
    }

    /** The text that is sent for one file. */
    static String prepare(String path, String raw) {
        return isHtml(path) ? clean(htmlToText(raw)) : clean(raw);
    }

    /**
     * Runs the sync.
     *
     * @throws ApiException when the knowledge base cannot be read at all
     */
    public static Result run(List<SourceFile> files, KnowledgeApi api, Options options, PrintStream log)
            throws ApiException {
        Result result = new Result();

        // Existing entries by title. The service returns the newest 100.
        Map<String, KnowledgeApi.Entry> byTitle = new LinkedHashMap<>();
        for (KnowledgeApi.Entry entry : api.list(options.websiteId)) {
            byTitle.putIfAbsent(entry.getTitle(), entry);
        }

        Set<String> seenTitles = new HashSet<>();
        boolean stopped = false;

        for (SourceFile file : files) {
            String title = options.titlePrefix + file.getPath();
            seenTitles.add(title);
            if (stopped) {
                continue;
            }

            String content = prepare(file.getPath(), file.getText());
            if (content.length() < MIN_CONTENT_LENGTH) {
                log.println("skipped: " + title + " (less than " + MIN_CONTENT_LENGTH + " characters of text)");
                result.skipped++;
                result.row("skipped", title);
                continue;
            }

            KnowledgeApi.Entry match = byTitle.get(title);

            // An entry that already holds the same text needs no work. This also
            // protects the daily character limit of the plan.
            if (match != null) {
                String stored;
                try {
                    stored = api.getContent(match.getId());
                } catch (ApiException e) {
                    stored = null;
                }
                if (stored != null && clean(stored).equals(content)) {
                    result.unchanged++;
                    result.row("unchanged", title);
                    continue;
                }
            }

            if (options.dryRun) {
                String action = match != null ? "would update" : "would add";
                log.println(action + ": " + title);
                result.row(action, title);
                if (match != null) {
                    result.updated++;
                } else {
                    result.added++;
                }
                continue;
            }

            // There is no update call, so a changed file means delete the old
            // entry and add the new one. The delete runs first, otherwise a
            // failure in the middle would leave two entries with one title.
            try {
                if (match != null) {
                    api.delete(match.getId());
                }
                api.addText(title, content, options.websiteId);
                if (match != null) {
                    result.updated++;
                    result.row("updated", title);
                    log.println("updated: " + title);
                } else {
                    result.added++;
                    result.row("added", title);
                    log.println("added: " + title);
                }
            } catch (ApiException e) {
                result.failed++;
                result.row("FAILED", title + " (" + e.getMessage() + ")");
                log.println("FAILED: " + title + ": " + e.getMessage());
                if (e.isAccountRejection()) {
                    log.println("Stopping: the account rejected the request.");
                    stopped = true;
                }
            }
        }

        if (options.prune && !options.titlePrefix.isEmpty() && !stopped) {
            for (Map.Entry<String, KnowledgeApi.Entry> pair : byTitle.entrySet()) {
                String title = pair.getKey();
                if (!title.startsWith(options.titlePrefix) || seenTitles.contains(title)) {
                    continue;
                }
                if (options.dryRun) {
                    log.println("would delete: " + title);
                    result.deleted++;
                    result.row("would delete", title);
                    continue;
                }
                try {
                    api.delete(pair.getValue().getId());
                    result.deleted++;
                    result.row("deleted", title);
                    log.println("deleted: " + title);
                } catch (ApiException e) {
                    result.failed++;
                    result.row("FAILED", title + " (could not be deleted: " + e.getMessage() + ")");
                    log.println("FAILED: " + title + ": could not be deleted: " + e.getMessage());
                }
            }
        } else if (options.prune && options.titlePrefix.isEmpty()) {
            log.println("WARNING: prune was ignored. Set a title prefix as well, so the step only removes"
                    + " entries it owns.");
        }

        return result;
    }
}
