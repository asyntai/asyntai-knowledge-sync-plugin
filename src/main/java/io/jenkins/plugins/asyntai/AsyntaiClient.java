package io.jenkins.plugins.asyntai;

import hudson.ProxyConfiguration;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;

/** Client for the Asyntai knowledge base API. Uses the proxy configured in Jenkins. */
public class AsyntaiClient implements KnowledgeApi {

    public static final String DEFAULT_API_URL = "https://asyntai.com";

    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    private final HttpClient http;
    private final String baseUrl;
    private final String apiKey;

    public AsyntaiClient(String baseUrl, String apiKey) {
        this(ProxyConfiguration.newHttpClient(), baseUrl, apiKey);
    }

    AsyntaiClient(HttpClient http, String baseUrl, String apiKey) {
        this.http = http;
        String url = baseUrl == null || baseUrl.isBlank() ? DEFAULT_API_URL : baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        this.baseUrl = url;
        this.apiKey = apiKey;
    }

    @Override
    public List<Entry> list(String websiteId) throws ApiException {
        String query = "limit=100";
        if (websiteId != null && !websiteId.isBlank()) {
            query += "&website_id=" + URLEncoder.encode(websiteId.trim(), StandardCharsets.UTF_8);
        }
        JSONObject payload = request("GET", "/api/v1/knowledge/?" + query, null);
        List<Entry> entries = new ArrayList<>();
        JSONArray array = payload.optJSONArray("entries");
        if (array != null) {
            for (int i = 0; i < array.size(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null && item.has("id")) {
                    entries.add(new Entry(item.optString("id"), item.optString("title", "")));
                }
            }
        }
        return entries;
    }

    @Override
    public String getContent(String id) throws ApiException {
        JSONObject payload = request("GET", "/api/v1/knowledge/" + encode(id) + "/", null);
        Object content = payload.opt("content");
        return content instanceof String ? (String) content : null;
    }

    @Override
    public void addText(String title, String content, String websiteId) throws ApiException {
        JSONObject body = new JSONObject();
        body.put("title", title);
        body.put("content", content);
        if (websiteId != null && !websiteId.isBlank()) {
            body.put("website_id", websiteId.trim());
        }
        request("POST", "/api/v1/knowledge/text/", body);
    }

    @Override
    public void delete(String id) throws ApiException {
        request("DELETE", "/api/v1/knowledge/" + encode(id) + "/", null);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private JSONObject request(String method, String endpoint, JSONObject body) throws ApiException {
        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder(URI.create(baseUrl + endpoint));
        } catch (IllegalArgumentException e) {
            throw new ApiException("The API URL " + baseUrl + " is not valid.", 0);
        }
        builder.timeout(TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .header("User-Agent", "asyntai-jenkins-plugin");
        if (body != null) {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ApiException("Could not reach " + baseUrl + ": " + e.getMessage(), 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("Interrupted while calling " + baseUrl + ".", 0);
        }

        String text = response.body() == null ? "" : response.body();
        JSONObject payload = new JSONObject();
        if (!text.isBlank()) {
            try {
                payload = JSONObject.fromObject(text);
            } catch (JSONException e) {
                payload = new JSONObject();
            }
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String detail = payload.optString("error", "");
            if (detail.isEmpty()) {
                detail = payload.optString("message", "");
            }
            if (detail.isEmpty()) {
                detail = text.isBlank() ? "HTTP " + status : text.substring(0, Math.min(200, text.length()));
            }
            throw new ApiException(detail, status);
        }
        return payload;
    }
}
