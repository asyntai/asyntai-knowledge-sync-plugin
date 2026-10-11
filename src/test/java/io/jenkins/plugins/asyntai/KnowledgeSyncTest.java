package io.jenkins.plugins.asyntai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Sync rules and the HTTP client, against the stub API, without a Jenkins instance. */
class KnowledgeSyncTest {

    private StubApi stub;
    private AsyntaiClient api;
    private ByteArrayOutputStream logBytes;
    private PrintStream log;

    @BeforeEach
    void setUp() throws Exception {
        stub = new StubApi();
        api = new AsyntaiClient(HttpClient.newHttpClient(), stub.url(), StubApi.KEY);
        logBytes = new ByteArrayOutputStream();
        log = new PrintStream(logBytes, true, StandardCharsets.UTF_8);
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    private String log() {
        return logBytes.toString(StandardCharsets.UTF_8);
    }

    private static KnowledgeSync.SourceFile file(String path, String text) {
        return new KnowledgeSync.SourceFile(path, text);
    }

    private KnowledgeSync.Result run(KnowledgeSync.Options options, KnowledgeSync.SourceFile... files)
            throws ApiException {
        return KnowledgeSync.run(List.of(files), api, options, log);
    }

    @Test
    void cleanStripsBomCrlfFrontMatterAndSpace() {
        assertEquals("Hello world", KnowledgeSync.clean("﻿  Hello world \r\n"));
        assertEquals("line one\nline two", KnowledgeSync.clean("line one\r\nline two\r\n"));
        assertEquals("Body text", KnowledgeSync.clean("---\ntitle: X\ntags: [a]\n---\nBody text\n"));
        assertEquals("Body text", KnowledgeSync.clean("---\r\ntitle: X\r\n---\r\nBody text"));
        assertEquals("", KnowledgeSync.clean("---\ntitle: only\n---"));
        // A horizontal rule further down is not front matter.
        assertEquals("Intro\n---\nMore", KnowledgeSync.clean("Intro\n---\nMore"));
        assertEquals("", KnowledgeSync.clean(null));
    }

    @Test
    void htmlBecomesReadableText() {
        String html = "<!DOCTYPE html><html><head><title>T</title><style>p{color:red}</style></head>\r\n"
                + "<body><script>var a = '<p>x</p>';</script><!-- note -->\n"
                + "<h1>Refund   policy</h1><p>Refunds take <b>5&nbsp;days</b> &amp; are free.</p>\n"
                + "<ul><li>One</li><li>Two &#8211; three &#x2192; four</li></ul>"
                + "<p>Unknown &bogus; stays</p></body></html>";
        assertEquals(
                "Refund policy\nRefunds take 5 days & are free.\n\nOne\nTwo – three → four\n\n"
                        + "Unknown &bogus; stays",
                KnowledgeSync.htmlToText(html));
        assertTrue(KnowledgeSync.isHtml("site/Guide.HTML"));
        assertTrue(KnowledgeSync.isHtml("a.htm"));
        assertTrue(!KnowledgeSync.isHtml("a.md"));
        // Markdown keeps its angle brackets and entities as written.
        assertEquals("Use <b> & &amp;", KnowledgeSync.prepare("a.md", "Use <b> & &amp;"));
    }

    @Test
    void htmlDropsNavigationAndFooterAndTidiesPunctuation() {
        String html = "<body><nav><a href='/'>Home</a> <a href='/shop'>Shop</a></nav>"
                + "<main><h1>Returns &amp; refunds</h1><p>Return any item within <b>30&nbsp;days</b>.</p>"
                + "<table><tr><td>Label</td><td>Free</td></tr></table></main>"
                + "<footer>Copyright 2026</footer></body>";
        assertEquals("Returns & refunds\nReturn any item within 30 days.\nLabel\nFree", KnowledgeSync.htmlToText(html));
    }

    @Test
    void htmlFileIsSentAsTextAndStaysUnchanged() throws Exception {
        String page = "<html><body><h2>Shipping</h2><p>We ship to 30 countries.</p></body></html>";
        run(new KnowledgeSync.Options(), file("site/shipping.html", page));
        assertEquals("Shipping\nWe ship to 30 countries.", stub.contentOf("site/shipping.html"));
        KnowledgeSync.Result second = run(new KnowledgeSync.Options(), file("site/shipping.html", page));
        assertEquals(1, second.getUnchanged());
        assertEquals(1, stub.posts.get());
    }

    @Test
    void firstRunAddsEverySecondRunChangesNothing() throws Exception {
        KnowledgeSync.Result first = run(
                new KnowledgeSync.Options(),
                file("docs/a.md", "Alpha page with enough text."),
                file("docs/b.md", "Beta page with enough text."));
        assertEquals(2, first.getAdded());
        assertEquals(List.of("docs/a.md", "docs/b.md"), stub.titles());

        KnowledgeSync.Result second = run(
                new KnowledgeSync.Options(),
                file("docs/a.md", "Alpha page with enough text."),
                file("docs/b.md", "Beta page with enough text."));
        assertEquals(0, second.getAdded());
        assertEquals(2, second.getUnchanged());
        assertEquals(2, stub.posts.get(), "an unchanged file must not be sent again");
    }

    @Test
    void windowsLineEndingsCountAsUnchanged() throws Exception {
        run(new KnowledgeSync.Options(), file("docs/a.md", "Line one\nLine two of the page."));
        KnowledgeSync.Result second =
                run(new KnowledgeSync.Options(), file("docs/a.md", "﻿Line one\r\nLine two of the page.\r\n"));
        assertEquals(1, second.getUnchanged());
        assertEquals(1, stub.posts.get());
    }

    @Test
    void changedFileReplacesItsEntry() throws Exception {
        run(new KnowledgeSync.Options(), file("docs/a.md", "Version one of the page."));
        KnowledgeSync.Result second = run(new KnowledgeSync.Options(), file("docs/a.md", "Version two of the page."));
        assertEquals(1, second.getUpdated());
        assertEquals(1, stub.deletes.get());
        assertEquals(List.of("docs/a.md"), stub.titles(), "one title, one entry");
        assertEquals("Version two of the page.", stub.contentOf("docs/a.md"));
    }

    @Test
    void titlePrefixAndPrune() throws Exception {
        stub.put("other/keep.md", "An entry this repository does not own.");
        KnowledgeSync.Options options =
                new KnowledgeSync.Options().titlePrefix("handbook/").prune(true);
        run(options, file("a.md", "Alpha page with enough text."), file("b.md", "Beta page with enough text."));
        assertEquals(List.of("handbook/a.md", "handbook/b.md", "other/keep.md"), stub.titles());

        KnowledgeSync.Result second = run(options, file("a.md", "Alpha page with enough text."));
        assertEquals(1, second.getDeleted());
        assertEquals(List.of("handbook/a.md", "other/keep.md"), stub.titles());
    }

    @Test
    void pruneWithoutPrefixIsIgnored() throws Exception {
        stub.put("docs/old.md", "An old page that is gone from the repo.");
        KnowledgeSync.Result result =
                run(new KnowledgeSync.Options().prune(true), file("docs/a.md", "Alpha page with enough text."));
        assertEquals(0, result.getDeleted());
        assertTrue(stub.titles().contains("docs/old.md"));
        assertTrue(log().contains("prune was ignored"));
    }

    @Test
    void dryRunSendsNothing() throws Exception {
        stub.put("p/old.md", "An old page that is gone from the repo.");
        stub.put("p/a.md", "Version one of the page.");
        KnowledgeSync.Result result = run(
                new KnowledgeSync.Options().titlePrefix("p/").prune(true).dryRun(true),
                file("a.md", "Version two of the page."),
                file("new.md", "A brand new page of text."));
        assertEquals(1, result.getAdded());
        assertEquals(1, result.getUpdated());
        assertEquals(1, result.getDeleted());
        assertEquals(0, stub.posts.get());
        assertEquals(0, stub.deletes.get());
        assertEquals(List.of("p/a.md", "p/old.md"), stub.titles());
        assertTrue(log().contains("would add: p/new.md"));
        assertTrue(log().contains("would update: p/a.md"));
        assertTrue(log().contains("would delete: p/old.md"));
    }

    @Test
    void tooShortFileIsSkipped() throws Exception {
        KnowledgeSync.Result result =
                run(new KnowledgeSync.Options(), file("docs/tiny.md", "short"), file("docs/fm.md", "---\na: b\n---\n"));
        assertEquals(2, result.getSkipped());
        assertEquals(0, stub.posts.get());
        assertEquals(0, result.getFailed());
    }

    @Test
    void rateLimitStopsTheRunAndSkipsPrune() throws Exception {
        stub.put("p/gone.md", "This entry must survive a failed run.");
        stub.rateLimitAfter = 1;
        KnowledgeSync.Result result = run(
                new KnowledgeSync.Options().titlePrefix("p/").prune(true),
                file("a.md", "Alpha page with enough text."),
                file("b.md", "Beta page with enough text."),
                file("c.md", "Gamma page with enough text."));
        assertEquals(1, result.getAdded());
        assertEquals(1, result.getFailed());
        assertEquals(1, stub.posts.get(), "no request after the account said no");
        assertTrue(stub.titles().contains("p/gone.md"), "prune must not run after a stop");
        assertTrue(log().contains("Stopping: the account rejected the request."));
    }

    @Test
    void websiteIdIsSent() throws Exception {
        run(new KnowledgeSync.Options().websiteId(" 42 "), file("docs/a.md", "Alpha page with enough text."));
        assertTrue(stub.seenWebsiteIds.contains("42"));
    }

    @Test
    void badKeyFailsTheListCall() {
        AsyntaiClient bad = new AsyntaiClient(HttpClient.newHttpClient(), stub.url(), "wrong");
        ApiException e = assertThrows(
                ApiException.class,
                () -> KnowledgeSync.run(
                        List.of(file("a.md", "Alpha page with enough text.")), bad, new KnowledgeSync.Options(), log));
        assertEquals(401, e.getStatus());
        assertEquals("Invalid API key", e.getMessage());
    }

    @Test
    void unreachableHostGivesStatusZero() {
        AsyntaiClient down = new AsyntaiClient(HttpClient.newHttpClient(), "http://127.0.0.1:1", StubApi.KEY);
        ApiException e = assertThrows(ApiException.class, () -> down.list(""));
        assertEquals(0, e.getStatus());
        assertTrue(e.getMessage().startsWith("Could not reach http://127.0.0.1:1"));
    }

    @Test
    void clientRoundTripAndTrailingSlash() throws Exception {
        AsyntaiClient slash = new AsyntaiClient(HttpClient.newHttpClient(), stub.url() + "///", StubApi.KEY);
        slash.addText("Title with spaces & symbols?", "Some content of the entry.", "");
        List<KnowledgeApi.Entry> entries = slash.list("");
        assertEquals(1, entries.size());
        String id = entries.get(0).getId();
        assertEquals("Some content of the entry.", slash.getContent(id));
        slash.delete(id);
        assertTrue(slash.list("").isEmpty());
        ApiException missing = assertThrows(ApiException.class, () -> slash.getContent(id));
        assertEquals(404, missing.getStatus());
    }

    @Test
    void invalidApiUrlIsReported() {
        AsyntaiClient broken = new AsyntaiClient(HttpClient.newHttpClient(), "not a url", StubApi.KEY);
        ApiException e = assertThrows(ApiException.class, () -> broken.list(""));
        assertEquals(0, e.getStatus());
        assertNull(e.getCause());
    }
}
