package com.javaclaw.browser.worker;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.microsoft.playwright.BrowserContext;

import com.javaclaw.browser.protocol.BrowserRegistrationProtocol;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts.CredentialCandidate;

/** 登记 Actor 独占的有界秘密缓存；只保存本 Context 输入候选，不访问浏览器密码库或其他窗口。 */
final class RegistrationCredentials implements AutoCloseable {
    private static final int MAXIMUM_CANDIDATES = 8;
    private static final String BINDING = "__javaclawRegistrationInput";
    private static final String SCRIPT = """
            (() => {
              if (window !== window.top) return;
              const documentId = crypto.randomUUID();
              const forms = new WeakMap();
              let sequence = 0;
              const read = event => {
                if (!event.isTrusted) return;
                const input = event.target;
                if (!(input instanceof HTMLInputElement) && !(input instanceof HTMLFormElement)) return;
                const scope = input instanceof HTMLFormElement ? input : input.form || document;
                const fields = Array.from(scope.querySelectorAll('input')).filter(e =>
                  e.form === (scope instanceof HTMLFormElement ? scope : null) && !e.disabled
                  && e.getClientRects().length && !['one-time-code','new-password'].includes(e.autocomplete));
                const passwords = fields.filter(e => e.type === 'password');
                if (passwords.length !== 1) return;
                const named = fields.filter(e => e.autocomplete === 'username');
                const users = named.length ? named : fields.filter(e => ['text','email','tel'].includes(e.type));
                if (users.length !== 1 || !users[0].value || !passwords[0].value) return;
                if (users[0].value.length + passwords[0].value.length > 16384) return;
                if (!forms.has(scope)) forms.set(scope, String(++sequence));
                window.__javaclawRegistrationInput(documentId + ':' + forms.get(scope),
                  users[0].value, passwords[0].value).catch(() => {});
              };
              document.addEventListener('input', read, true);
              document.addEventListener('change', read, true);
              document.addEventListener('submit', read, true);
            })();
            """;
    private final Map<String, Candidate> candidates = new LinkedHashMap<>();

    void install(BrowserContext context, java.util.function.Predicate<URI> allowed, Runnable changed) {
        context.exposeBinding(BINDING, (source, arguments) -> {
            try {
                if (source.frame() != source.page().mainFrame() || arguments.length != 3) {
                    return null;
                }
                URI origin = com.javaclaw.builtin.contracts.SiteContracts.originOf(
                        com.javaclaw.builtin.contracts.SiteRegistrationContracts.displayUri(
                                URI.create(source.frame().url())));
                if (allowed.test(origin)
                        && arguments[0] instanceof String key
                        && arguments[1] instanceof String user
                        && arguments[2] instanceof String secret
                        && capture(origin, key, user, secret)) {
                    changed.run();
                }
            } catch (IllegalArgumentException ignored) {
                // 页面输入与回调均是不可信数据；无效候选直接丢弃，不输出任何输入或页面异常。
            }
            return null;
        });
        context.addInitScript(SCRIPT);
    }

    boolean capture(URI origin, String documentForm, String username, String password) {
        if (!validInput(documentForm, username, password)) {
            return false;
        }
        byte[] bytes = (username + '\0' + password).getBytes(StandardCharsets.UTF_8);
        String key = origin + ":" + documentForm;
        try {
            if (bytes.length > BrowserRegistrationProtocol.MAXIMUM_CREDENTIAL_BYTES) {
                return false;
            }
            Candidate previous = candidates.get(key);
            if (previous != null && Arrays.equals(previous.bytes(), bytes)) {
                return false;
            }
            if (previous == null && candidates.size() >= MAXIMUM_CANDIDATES) {
                return false;
            }
            if (previous != null) {
                previous.close();
            }
            candidates.put(
                    key,
                    new Candidate(
                            new CredentialCandidate(
                                    UUID.randomUUID().toString(),
                                    origin,
                                    "本次输入的登录表单 " + (previous == null ? candidates.size() + 1 : index(key))),
                            bytes.clone()));
            return true;
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static boolean validInput(String documentForm, String username, String password) {
        return documentForm.length() <= 100
                && !username.isEmpty()
                && !password.isEmpty()
                && username.indexOf('\0') < 0
                && password.indexOf('\0') < 0;
    }

    private int index(String key) {
        return List.copyOf(candidates.keySet()).indexOf(key) + 1;
    }

    List<CredentialCandidate> descriptions() {
        return candidates.values().stream().map(Candidate::description).toList();
    }

    byte[] selected(Optional<String> id, URI origin) {
        if (id.isEmpty()) {
            return new byte[0];
        }
        Candidate candidate = candidates.values().stream()
                .filter(value -> value.description().id().equals(id.orElseThrow()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("BROWSER_CREDENTIAL_TARGET_CHANGED"));
        if (!candidate.description().origin().equals(origin)) {
            throw new IllegalStateException("BROWSER_CREDENTIAL_TARGET_CHANGED");
        }
        return candidate.bytes().clone();
    }

    String redact(String value) {
        String result = value;
        for (Candidate candidate : candidates.values()) {
            for (String secret : new String(candidate.bytes(), StandardCharsets.UTF_8).split("\u0000")) {
                result = result.replace(secret, "REDACTED")
                        .replace(java.net.URLEncoder.encode(secret, StandardCharsets.UTF_8), "REDACTED");
            }
        }
        return result;
    }

    @Override
    public void close() {
        candidates.values().forEach(Candidate::close);
        candidates.clear();
    }

    /** 字节数组只属于当前 Actor，替换或关闭时立即清零。 */
    private record Candidate(CredentialCandidate description, byte[] bytes) implements AutoCloseable {
        @Override
        public void close() {
            Arrays.fill(bytes, (byte) 0);
        }
    }
}
