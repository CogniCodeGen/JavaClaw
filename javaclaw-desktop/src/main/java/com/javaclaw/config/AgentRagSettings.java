package com.javaclaw.config;

import java.util.Objects;
import java.util.Properties;

import static com.javaclaw.config.AgentConfigSchema.*;

/** Typed access to RAG retrieval and embedding settings, including encrypted credentials. */
final class AgentRagSettings {

    private final Properties properties;
    private final CredentialCipher credentials;

    AgentRagSettings(Properties properties, CredentialCipher credentials) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    boolean enabled() { return bool(KEY_RAG_ENABLED, DEFAULT_RAG_ENABLED); }
    void enabled(boolean value) { put(KEY_RAG_ENABLED, value); }
    String embeddingProvider() { return text(KEY_RAG_EMBEDDING_PROVIDER, DEFAULT_RAG_EMBEDDING_PROVIDER); }
    void embeddingProvider(String value) { put(KEY_RAG_EMBEDDING_PROVIDER, value); }
    String embeddingBaseUrl() { return text(KEY_RAG_EMBEDDING_BASE_URL, DEFAULT_RAG_EMBEDDING_BASE_URL); }
    void embeddingBaseUrl(String value) { put(KEY_RAG_EMBEDDING_BASE_URL, value); }
    String embeddingApiKey() {
        return credentials.decrypt(text(KEY_RAG_EMBEDDING_API_KEY, DEFAULT_RAG_EMBEDDING_API_KEY));
    }
    void embeddingApiKey(String value) {
        put(KEY_RAG_EMBEDDING_API_KEY, credentials.encrypt(value));
    }
    String embeddingModelName() { return text(KEY_RAG_EMBEDDING_MODEL_NAME, DEFAULT_RAG_EMBEDDING_MODEL_NAME); }
    void embeddingModelName(String value) { put(KEY_RAG_EMBEDDING_MODEL_NAME, value); }
    int embeddingDimensions() {
        return integer(properties, KEY_RAG_EMBEDDING_DIMENSIONS, DEFAULT_RAG_EMBEDDING_DIMENSIONS);
    }
    void embeddingDimensions(int value) { put(KEY_RAG_EMBEDDING_DIMENSIONS, value); }
    int chunkSize() { return integer(properties, KEY_RAG_CHUNK_SIZE, DEFAULT_RAG_CHUNK_SIZE); }
    void chunkSize(int value) { put(KEY_RAG_CHUNK_SIZE, value); }
    int chunkOverlap() { return integer(properties, KEY_RAG_CHUNK_OVERLAP, DEFAULT_RAG_CHUNK_OVERLAP); }
    void chunkOverlap(int value) { put(KEY_RAG_CHUNK_OVERLAP, value); }
    int retrieveLimit() { return integer(properties, KEY_RAG_RETRIEVE_LIMIT, DEFAULT_RAG_RETRIEVE_LIMIT); }
    void retrieveLimit(int value) { put(KEY_RAG_RETRIEVE_LIMIT, value); }
    double scoreThreshold() {
        return decimal(properties, KEY_RAG_SCORE_THRESHOLD, DEFAULT_RAG_SCORE_THRESHOLD);
    }
    void scoreThreshold(double value) { put(KEY_RAG_SCORE_THRESHOLD, value); }

    private boolean bool(String key, boolean fallback) {
        return Boolean.parseBoolean(properties.getProperty(key, String.valueOf(fallback)));
    }

    private String text(String key, String fallback) {
        return properties.getProperty(key, fallback);
    }

    private void put(String key, Object value) {
        properties.setProperty(key, String.valueOf(value));
    }
}
