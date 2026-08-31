package com.javaclaw.agent.tool;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import com.javaclaw.sandbox.api.SandboxLaunchRequest;
import com.javaclaw.sandbox.api.SandboxLaunchResponse;
import com.javaclaw.sandbox.api.SandboxResult;
import com.javaclaw.sandbox.api.SandboxSessionControl;
import com.javaclaw.sandbox.api.SandboxSessionFrame;

public final class FakeSandboxLauncher {
    private FakeSandboxLauncher() {}

    public static void main(String[] args) throws Exception {
        ObjectMapper json = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line = input.readLine();
        SandboxLaunchRequest request = json.readValue(line, SandboxLaunchRequest.class);
        if (args.length > 0 && "--session".equals(args[args.length - 1])) {
            if (request.sessionOptions() == null) {
                throw new IllegalArgumentException("missing session options");
            }
            System.out.println(json.writeValueAsString(SandboxSessionFrame.ready(request.nonce(), "fake-session")));
            System.out.flush();
            if ("blocked-session".equals(args[0]) || "closed-output".equals(args[0])) {
                if ("closed-output".equals(args[0])) {
                    System.out.close();
                }
                Thread.sleep(30_000);
                return;
            }
            if ("oversized-frame".equals(args[0])) {
                System.out.print("x".repeat(4 * 1024 * 1024));
                System.out.flush();
                Thread.sleep(30_000);
                return;
            }
            while ((line = input.readLine()) != null) {
                SandboxSessionControl control = json.readValue(line, SandboxSessionControl.class);
                if (control.operation() == SandboxSessionControl.Operation.STDIN) {
                    byte[] value = control.data();
                    if (request.sessionOptions().pseudoTerminal()) {
                        String dimensions = request.sessionOptions().columns() + "x"
                                + request.sessionOptions().rows() + ":";
                        byte[] prefix = dimensions.getBytes(StandardCharsets.UTF_8);
                        byte[] combined = java.util.Arrays.copyOf(prefix, prefix.length + value.length);
                        System.arraycopy(value, 0, combined, prefix.length, value.length);
                        value = combined;
                    }
                    System.out.println(json.writeValueAsString(
                            SandboxSessionFrame.stream(request.nonce(), SandboxSessionFrame.Kind.STDOUT, value)));
                    System.out.flush();
                } else if (control.operation() == SandboxSessionControl.Operation.TERMINATE) {
                    System.out.println(
                            json.writeValueAsString(SandboxSessionFrame.exit(request.nonce(), 0, false, "")));
                    System.out.flush();
                    return;
                }
            }
            return;
        }
        String nonce = args.length > 0 && "wrong-nonce".equals(args[0]) ? "wrong" : request.nonce();
        SandboxLaunchResponse response = SandboxLaunchResponse.success(
                nonce, new SandboxResult(0, "from launcher", "", false, false, Duration.ofMillis(1), "fake"));
        System.out.println(json.writeValueAsString(response));
    }
}
