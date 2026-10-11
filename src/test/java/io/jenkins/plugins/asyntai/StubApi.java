package io.jenkins.plugins.asyntai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

/**
 * In-memory stand-in for the Asyntai knowledge API. It answers the same four
 * endpoints with the same JSON shapes and errors as the real service, so the
 * plugin runs end to end without a network call or a paid account.
 */
final class StubApi implements AutoCloseable {

    static final String KEY = "stub-key-7f3a";

    /** id -> {title, content, website}. Newest last; the list call reverses it. */
    final Map<String, String[]> entries = Collections.synchronizedMap(new LinkedHashMap<>());

    final AtomicInteger posts = new AtomicInteger();
    final AtomicInteger deletes = new AtomicInteger();
    final AtomicInteger lists = new AtomicInteger();
    final List<String> seenWebsiteIds = Collections.synchronizedList(new ArrayList<>());

    /** When above zero, POST answers 429 once this many posts were accepted. */
    volatile int rateLimitAfter = -1;

    private final HttpServer server;

    StubApi() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void put(String title, String content) {
        entries.put(UUID.randomUUID().toString(), new String[] {title, content, ""});
    }

    List<String> titles() {
        synchronized (entries) {
            List<String> out = new ArrayList<>();
            for (String[] value : entries.values()) {
                out.add(value[0]);
            }
            Collections.sort(out);
            return out;
        }
    }

    String contentOf(String title) {
        synchronized (entries) {
            for (String[] value : entries.values()) {
                if (value[0].equals(title)) {
                    return value[1];
                }
            }
        }
        return null;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (!("Bearer " + KEY).equals(auth)) {
                send(exchange, 401, error("Invalid API key"));
                return;
            }
            URI uri = exchange.getRequestURI();
            String[] parts = uri.getPath().replaceAll("^/+|/+$", "").split("/");
            if (parts.length < 3
                    || !"api".equals(parts[0])
                    || !"v1".equals(parts[1])
                    || !"knowledge".equals(parts[2])) {
                send(exchange, 404, error("Not found"));
                return;
            }
            String method = exchange.getRequestMethod();

            if ("GET".equals(method) && parts.length == 3) {
                lists.incrementAndGet();
                String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
                for (String pair : query.split("&")) {
                    if (pair.startsWith("website_id=")) {
                        seenWebsiteIds.add(URLDecoder.decode(pair.substring(11), StandardCharsets.UTF_8));
                    }
                }
                JSONArray list = new JSONArray();
                synchronized (entries) {
                    List<Map.Entry<String, String[]>> all = new ArrayList<>(entries.entrySet());
                    Collections.reverse(all);
                    for (Map.Entry<String, String[]> e : all.subList(0, Math.min(100, all.size()))) {
                        JSONObject item = new JSONObject();
                        item.put("id", e.getKey());
                        item.put("type", "text");
                        item.put("title", e.getValue()[0]);
                        item.put("description", "");
                        item.put("created_at", "2026-10-11T00:00:00Z");
                        list.add(item);
                    }
                }
                JSONObject body = new JSONObject();
                body.put("success", true);
                body.put("entries", list);
                send(exchange, 200, body);
                return;
            }

            if ("POST".equals(method) && parts.length == 4 && "text".equals(parts[3])) {
                if (rateLimitAfter >= 0 && posts.get() >= rateLimitAfter) {
                    send(exchange, 429, error("Daily upload limit reached"));
                    return;
                }
                String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                JSONObject data = JSONObject.fromObject(raw.isEmpty() ? "{}" : raw);
                String title = data.optString("title", "");
                String content = data.optString("content", "");
                if (title.isEmpty()) {
                    send(exchange, 400, error("title is required"));
                    return;
                }
                if (content.length() < 10) {
                    send(exchange, 400, error("content must be at least 10 characters"));
                    return;
                }
                String website = data.optString("website_id", "");
                if (!website.isEmpty()) {
                    seenWebsiteIds.add(website);
                }
                posts.incrementAndGet();
                String id = UUID.randomUUID().toString();
                entries.put(id, new String[] {title, content, website});
                JSONObject body = new JSONObject();
                body.put("success", true);
                body.put("id", id);
                body.put("title", title);
                body.put("chunks_created", 1);
                send(exchange, 200, body);
                return;
            }

            if (parts.length == 4) {
                String id = URLDecoder.decode(parts[3], StandardCharsets.UTF_8);
                String[] entry = entries.get(id);
                if (entry == null) {
                    send(exchange, 404, error("Knowledge base entry not found"));
                    return;
                }
                if ("DELETE".equals(method)) {
                    deletes.incrementAndGet();
                    entries.remove(id);
                    JSONObject body = new JSONObject();
                    body.put("success", true);
                    send(exchange, 200, body);
                    return;
                }
                if ("GET".equals(method)) {
                    JSONObject body = new JSONObject();
                    body.put("success", true);
                    body.put("id", id);
                    body.put("title", entry[0]);
                    body.put("content", entry[1]);
                    body.put("content_available", true);
                    send(exchange, 200, body);
                    return;
                }
            }
            send(exchange, 405, error("Method not allowed"));
        } catch (RuntimeException e) {
            send(exchange, 500, error(e.toString()));
        }
    }

    private static JSONObject error(String message) {
        JSONObject body = new JSONObject();
        body.put("success", false);
        body.put("error", message);
        return body;
    }

    private static void send(HttpExchange exchange, int status, JSONObject body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
