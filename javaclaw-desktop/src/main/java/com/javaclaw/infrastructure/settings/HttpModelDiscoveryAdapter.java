package com.javaclaw.infrastructure.settings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.application.settings.ModelDiscoveryApplicationService.DiscoveryRequest;
import com.javaclaw.application.settings.ModelDiscoveryApplicationService.DiscoveryResult;
import com.javaclaw.application.settings.ModelDiscoveryApplicationService.ModelOption;
import com.javaclaw.application.settings.ModelDiscoveryApplicationService.Usage;
import com.javaclaw.application.settings.ModelDiscoveryPort;
import com.javaclaw.platform.http.HttpGateway;
import com.javaclaw.platform.http.HttpResult;
import com.javaclaw.platform.http.HttpRetryPolicy;
import com.javaclaw.platform.json.JsonCodec;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** 按提供商查询模型目录。响应体、URL 和传输异常均不进入用户提示或日志。 */
public final class HttpModelDiscoveryAdapter implements ModelDiscoveryPort {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 5;
    private static final int MAX_MODELS = PAGE_SIZE * MAX_PAGES;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    private final HttpGateway http;
    private final JsonCodec json;

    /** 生产装配必须传入使用 HttpClient.Redirect.NEVER 的网关。 */
    public HttpModelDiscoveryAdapter(HttpGateway http, JsonCodec json) {
        this.http = Objects.requireNonNull(http, "http");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public DiscoveryResult discover(DiscoveryRequest request) throws InterruptedException {
        Objects.requireNonNull(request, "request");
        String provider = request.provider().toLowerCase(Locale.ROOT);
        boolean officialDashscope = "dashscope".equals(provider)
                && officialDashscopeHost(URI.create(request.baseUrl()).getHost());
        URI endpoint = endpoint(URI.create(request.baseUrl()), provider, officialDashscope);
        Map<String, ModelOption> found = new LinkedHashMap<>();
        String cursor = "";
        for (int page = 1; page <= MAX_PAGES; page++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("model discovery cancelled");
            URI pageUri = pageUri(endpoint, provider, officialDashscope, page, cursor);
            HttpResult response;
            try {
                response = http.sendAndWait("settings-model-discovery", () -> request(pageUri,
                        provider, request.apiKey()), HttpRetryPolicy.none());
            } catch (IOException unavailable) {
                return DiscoveryResult.failed("无法连接模型列表接口，请检查 API 地址和网络");
            } catch (IllegalArgumentException invalidRequest) {
                return DiscoveryResult.failed("API Key 或 API 地址格式无效");
            }
            // A custom transport must obey the same no-redirect rule as the production client.
            if (!pageUri.equals(response.uri())) {
                return DiscoveryResult.failed("模型列表接口发生重定向，请直接填写最终 API 地址");
            }
            if (!response.isSuccessful()) return DiscoveryResult.failed(httpFailure(response.statusCode()));
            byte[] bytes = response.body();
            if (bytes.length > MAX_RESPONSE_BYTES) {
                return DiscoveryResult.failed("模型列表响应过大，请检查 API 地址");
            }
            JsonNode body;
            try {
                body = json.mapper().reader()
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readTree(bytes);
            } catch (JsonProcessingException invalidJson) {
                return DiscoveryResult.failed("模型接口未返回有效 JSON；" + endpointGuidance(provider));
            } catch (IOException readFailure) {
                return DiscoveryResult.failed("模型接口未返回有效 JSON；" + endpointGuidance(provider));
            }
            if (body == null || body.isNull() || body.isMissingNode()) {
                return DiscoveryResult.failed("模型接口响应为空；" + endpointGuidance(provider));
            }
            // DashScope's successful list response contains code:null and success:true.
            if (body.has("error") || (officialDashscope
                    && ((body.hasNonNull("code")
                    && !"Success".equalsIgnoreCase(body.path("code").asText()))
                    || (body.hasNonNull("success") && !body.path("success").asBoolean())))) {
                return DiscoveryResult.failed("模型接口返回错误；" + endpointGuidance(provider));
            }
            JsonNode entries = entries(body, provider, officialDashscope);
            if (!entries.isArray()) {
                String field = officialDashscope ? "output.models" :
                        ("openai".equals(provider) || "anthropic".equals(provider)
                                || "dashscope".equals(provider)) ? "data" : "models";
                return DiscoveryResult.failed("模型接口响应缺少 " + field + " 模型列表；"
                        + endpointGuidance(provider));
            }
            for (JsonNode entry : entries) {
                ModelOption option = option(entry, provider, officialDashscope, request.usage());
                if (option != null) found.putIfAbsent(option.id(), option);
                if (found.size() >= MAX_MODELS) break;
            }
            if (found.size() >= MAX_MODELS) break;
            String next = nextCursor(body, provider, officialDashscope, page);
            if (next == null) break;
            if (next.isBlank() || next.equals(cursor)) {
                return DiscoveryResult.failed("模型接口返回无效的分页游标");
            }
            cursor = next;
        }
        List<ModelOption> models = new ArrayList<>(found.values());
        return new DiscoveryResult(true, models,
                models.isEmpty() ? "接口可达，但未发现可用模型；仍可手动输入" :
                        "已发现 " + models.size() + " 个模型");
    }

    private static HttpRequest request(URI uri, String provider, String apiKey) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT).GET();
        if (!apiKey.isBlank() && !"not-needed".equals(apiKey)) {
            switch (provider) {
                case "anthropic" -> {
                    builder.header("x-api-key", apiKey);
                    builder.header("anthropic-version", "2023-06-01");
                }
                case "gemini" -> builder.header("x-goog-api-key", apiKey);
                default -> builder.header("Authorization", "Bearer " + apiKey);
            }
        } else if ("anthropic".equals(provider)) {
            builder.header("anthropic-version", "2023-06-01");
        }
        return builder.build();
    }

    private static URI endpoint(URI base, String provider, boolean officialDashscope) {
        if (officialDashscope) return withPath(base, "/api/v1/models");
        if ("ollama".equals(provider)) {
            String path = base.getRawPath() == null ? "" : base.getRawPath();
            while (path.endsWith("/") && !path.isEmpty()) path = path.substring(0, path.length() - 1);
            if (path.endsWith("/api/tags")) return withPath(base, path);
            if (path.endsWith("/v1")) path = path.substring(0, path.length() - 3);
            return withPath(base, path.endsWith("/api") ? path + "/tags" : path + "/api/tags");
        }
        String path = switch (provider) {
            case "anthropic" -> "/v1/models";
            case "gemini" -> "/v1beta/models";
            default -> "/models";
        };
        String basePath = base.getRawPath();
        if (basePath == null) basePath = "";
        while (basePath.endsWith("/") && !basePath.isEmpty()) {
            basePath = basePath.substring(0, basePath.length() - 1);
        }
        if (basePath.endsWith(path)) return withPath(base, basePath);
        int finalSlash = path.lastIndexOf('/');
        String parent = path.substring(0, finalSlash);
        if (!parent.isEmpty() && basePath.endsWith(parent)) path = path.substring(finalSlash);
        return withPath(base, basePath + path);
    }

    private static URI withPath(URI base, String path) {
        String authority = base.getRawAuthority();
        return URI.create(base.getScheme() + "://" + authority + path);
    }

    private static URI pageUri(
            URI endpoint, String provider, boolean officialDashscope, int page, String cursor) {
        String query;
        if (officialDashscope) {
            query = "page_no=" + page + "&page_size=" + PAGE_SIZE;
        } else if ("anthropic".equals(provider)) {
            query = "limit=" + PAGE_SIZE + (cursor.isEmpty() ? "" :
                    "&after_id=" + urlEncode(cursor));
        } else if ("gemini".equals(provider)) {
            query = "pageSize=" + PAGE_SIZE + (cursor.isEmpty() ? "" :
                    "&pageToken=" + urlEncode(cursor));
        } else {
            return endpoint;
        }
        return URI.create(endpoint.toASCIIString() + "?" + query);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static JsonNode entries(JsonNode body, String provider, boolean officialDashscope) {
        if (officialDashscope) return body.path("output").path("models");
        return switch (provider) {
            case "ollama", "gemini" -> body.path("models");
            default -> body.path("data");
        };
    }

    private static ModelOption option(
            JsonNode entry, String provider, boolean officialDashscope, Usage usage) {
        if (!entry.isObject()) return null;
        if ("gemini".equals(provider) && !supportsGeminiUsage(entry, usage)) return null;
        String id = switch (provider) {
            case "ollama" -> text(entry, "name", "model");
            case "gemini" -> text(entry, "name");
            case "dashscope" -> officialDashscope ? text(entry, "model") : text(entry, "id");
            default -> text(entry, "id");
        };
        if ("gemini".equals(provider) && id.startsWith("models/")) id = id.substring(7);
        if (id.isBlank()) return null;
        String display = switch (provider) {
            case "anthropic" -> text(entry, "display_name");
            case "gemini" -> text(entry, "displayName");
            default -> text(entry, "name");
        };
        return new ModelOption(id, display);
    }

    private static boolean supportsGeminiUsage(JsonNode entry, Usage usage) {
        JsonNode methods = entry.path("supportedGenerationMethods");
        if (!methods.isArray()) return true;
        for (JsonNode method : methods) {
            String name = method.asText();
            if (usage == Usage.CHAT && ("generateContent".equals(name)
                    || "generateMessage".equals(name))) return true;
            if (usage == Usage.EMBEDDING && ("embedContent".equals(name)
                    || "batchEmbedContents".equals(name))) return true;
        }
        return false;
    }

    private static String text(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual() && !value.asText().isBlank()) return value.asText().strip();
        }
        return "";
    }

    private static String nextCursor(
            JsonNode body, String provider, boolean officialDashscope, int page) {
        if (officialDashscope) {
            long total = body.path("output").path("total").asLong(-1);
            return total > (long) page * PAGE_SIZE ? Integer.toString(page + 1) : null;
        }
        if ("anthropic".equals(provider) && body.path("has_more").asBoolean(false)) {
            return body.path("last_id").asText("");
        }
        if ("gemini".equals(provider)) {
            String value = body.path("nextPageToken").asText("");
            return value.isBlank() ? null : value;
        }
        return null;
    }

    private static boolean officialDashscopeHost(String host) {
        if (host == null) return false;
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("dashscope.aliyuncs.com")
                || normalized.equals("dashscope-intl.aliyuncs.com")
                || normalized.equals("cn-hongkong.dashscope.aliyuncs.com")
                || normalized.endsWith(".maas.aliyuncs.com");
    }

    private static String httpFailure(int status) {
        String action = switch (status) {
            case 401, 403 -> "请检查 API Key 和访问权限";
            case 404 -> "请检查 Base URL 和模型列表路径";
            case 408, 429 -> "请求超时或达到频率限制，请稍后重试";
            default -> status >= 300 && status < 400
                    ? "接口发生重定向，请直接填写最终 API 地址" :
                    status >= 500 ? "提供商服务暂不可用，请稍后重试" :
                    "请检查 API 地址和提供商设置";
        };
        return "连接异常 (HTTP " + status + ")；" + action;
    }

    private static String endpointGuidance(String provider) {
        return switch (provider) {
            case "anthropic" -> "请检查 Base URL 和 /v1/models 路径";
            case "gemini" -> "请检查 Base URL 和 /v1beta/models 路径";
            case "ollama" -> "请检查 Base URL 和 /api/tags 路径";
            case "dashscope" -> "请检查 Base URL 和模型列表路径（官方服务使用 /api/v1/models）";
            default -> "请检查 Base URL 和 API 路径（OpenAI 兼容服务通常需包含 /v1）";
        };
    }
}
