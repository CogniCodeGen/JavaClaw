package com.javaclaw.nativehost.sandbox;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import com.javaclaw.nativehost.ffm.NativeResourceLimits;
import com.javaclaw.sandbox.api.SandboxCommand;
import com.javaclaw.sandbox.api.SandboxLaunchRequest;
import com.javaclaw.sandbox.api.SandboxLaunchResponse;
import com.javaclaw.sandbox.api.SandboxSessionOptions;

/** Isolated launcher for one-shot commands and authenticated long-lived process sessions. */
public final class SandboxLauncherMain {
    private static final int MAX_REQUEST_CHARS = 8 * 1024 * 1024;

    private SandboxLauncherMain() {}

    /** 从继承标准输入接收有界一次性策略和 nonce，执行普通或 session 模式；能力缺失时返回失败帧，不退化为无沙箱执行。 */
    public static void main(String[] args) throws Exception {
        ObjectMapper json = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        if (args.length == 1 && "--session".equals(args[0])) {
            runSession(json, input);
            return;
        }
        if (args.length != 0) {
            throw new IllegalArgumentException("unknown sandbox launcher mode");
        }
        String nonce = "invalid";
        SandboxLaunchResponse response;
        try {
            String line = readBoundedLine(input);
            SandboxLaunchRequest request = json.readValue(line, SandboxLaunchRequest.class);
            nonce = request.nonce();
            SandboxCommand command = request.toCommand();
            SandboxBackend backend = PlatformBackends.current();
            if (!backend.available()) {
                throw new UnsupportedOperationException("required sandbox backend is unavailable: " + backend.name());
            }
            NativeResourceLimits.applyLauncherLimits(command.policy().timeout());
            response = SandboxLaunchResponse.success(nonce, new SandboxProcessRunner().run(backend, command));
        } catch (Throwable failure) {
            String message = failure.getMessage();
            if (message == null || message.isBlank()) {
                message = failure.getClass().getSimpleName();
            }
            response = SandboxLaunchResponse.failure(nonce, message);
        }
        System.out.println(json.writeValueAsString(response));
        System.out.flush();
    }

    private static void runSession(ObjectMapper json, BufferedReader input) throws Exception {
        String nonce = "invalid";
        try {
            String line = readBoundedLine(input);
            SandboxLaunchRequest request = json.readValue(line, SandboxLaunchRequest.class);
            nonce = request.nonce();
            SandboxSessionOptions options = request.sessionOptions();
            if (options == null) {
                throw new IllegalArgumentException("sandbox session options are missing");
            }
            SandboxCommand command = request.toCommand();
            SandboxBackend backend = PlatformBackends.current();
            if (!backend.available()) {
                throw new UnsupportedOperationException("required sandbox backend is unavailable: " + backend.name());
            }
            NativeResourceLimits.applyLauncherLimits(command.policy().timeout());
            new SandboxProcessRunner().runSession(backend, command, options, input, json, nonce);
        } catch (Throwable failure) {
            String message = failure.getMessage();
            if (message == null || message.isBlank()) {
                message = failure.getClass().getSimpleName();
            }
            System.out.println(
                    json.writeValueAsString(com.javaclaw.sandbox.api.SandboxSessionFrame.error(nonce, message)));
            System.out.flush();
        }
    }

    private static String readBoundedLine(BufferedReader input) throws IOException {
        StringBuilder line = new StringBuilder(4096);
        int value;
        while ((value = input.read()) >= 0 && value != '\n') {
            if (value != '\r') {
                line.append((char) value);
            }
            if (line.length() > MAX_REQUEST_CHARS) {
                throw new IOException("launcher request is too large");
            }
        }
        if (line.isEmpty()) {
            throw new IOException("launcher request is empty");
        }
        return line.toString();
    }
}
