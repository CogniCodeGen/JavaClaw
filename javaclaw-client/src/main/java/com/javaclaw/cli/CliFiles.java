package com.javaclaw.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.javaclaw.sdk.JavaClawClient;
import com.javaclaw.sdk.model.AttachmentInfo;
import com.javaclaw.sdk.model.JsonDocument;

/** CLI 的本机输入输出边界；只有 SDK 消费文件，路径不作为服务端工具权限传递。 */
final class CliFiles {
    private CliFiles() {}

    static String text(JavaClawCli.Arguments args) throws IOException {
        return new String(bytes(Path.of(args.required("file")), 1024 * 1024), StandardCharsets.UTF_8);
    }

    static JsonDocument document(JavaClawCli.Arguments args) throws IOException {
        return new JsonDocument(text(args));
    }

    static byte[] bytes(Path path, int maximum) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] value = input.readNBytes(maximum + 1);
            if (value.length > maximum) {
                throw new IllegalArgumentException("local input exceeds limit");
            }
            return value;
        }
    }

    static AttachmentInfo attachment(JavaClawClient client, JavaClawCli.Arguments args) {
        if (args.value("sha") != null) {
            return client.attachments().read(args.required("sha"), 0, 1).join().attachment();
        }
        return client.attachments()
                .upload(
                        Path.of(args.required("file")),
                        args.value("media-type", "application/octet-stream"),
                        args.key() + ":upload")
                .join();
    }

    static Object credential(JavaClawCli.Arguments args, Function<char[], CompletableFuture<?>> action) {
        char[] credential;
        String variable = args.value("credential-env");
        if (variable != null) {
            String value = System.getenv(variable);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("credential environment variable is not configured");
            }
            credential = value.toCharArray();
        } else {
            var console = System.console();
            if (console == null) {
                throw new IllegalArgumentException(
                        "use --credential-env NAME or an interactive terminal; raw credentials are not accepted as arguments");
            }
            credential = console.readPassword("Credential (not echoed): ");
            if (credential == null || credential.length == 0) {
                throw new IllegalArgumentException("credential input cancelled");
            }
        }
        try {
            return action.apply(credential).join();
        } finally {
            Arrays.fill(credential, '\0');
        }
    }
}
