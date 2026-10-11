package io.jenkins.plugins.asyntai;

import java.util.List;

/**
 * The four knowledge base calls the sync needs. {@link AsyntaiClient} talks to
 * the real service; tests can pass any other implementation.
 */
public interface KnowledgeApi {

    /** One knowledge base entry as the list call returns it. */
    final class Entry {
        private final String id;
        private final String title;

        public Entry(String id, String title) {
            this.id = id;
            this.title = title;
        }

        public String getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }
    }

    /** Newest entries first. The service returns at most 100. */
    List<Entry> list(String websiteId) throws ApiException;

    /** Stored text of one entry, or {@code null} when the service does not return it. */
    String getContent(String id) throws ApiException;

    void addText(String title, String content, String websiteId) throws ApiException;

    void delete(String id) throws ApiException;
}
