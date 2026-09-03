/** Provider 适配与原生模型能力边界。 */
module com.javaclaw.model.adapters {
    requires com.fasterxml.jackson.databind;
    requires com.google.genai;
    requires com.javaclaw.api;
    requires com.javaclaw.agent.runtime;
    requires com.javaclaw.extension.spi;
    requires openai.java.client.okhttp;
    requires openai.java.core;
    requires okhttp3;
    requires kotlin.stdlib;
    requires anthropic.java.client.okhttp;
    requires anthropic.java.core;
    requires reactor.core;
    requires spring.ai.anthropic;
    requires spring.ai.google.genai;
    requires spring.ai.model;
    requires spring.ai.openai;

    exports com.javaclaw.model;
}
