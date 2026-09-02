package com.javaclaw.nativehost.process;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;

final class WorkerProtocolTestMain {
    private WorkerProtocolTestMain() {}

    public static void main(String[] arguments) throws Exception {
        String mode = arguments[0];
        DataInputStream input = new DataInputStream(System.in);
        DataOutputStream output = new DataOutputStream(System.out);
        while (true) {
            int length;
            try {
                length = input.readInt();
            } catch (java.io.EOFException end) {
                return;
            }
            byte[] request = input.readNBytes(length);
            if ("exit".equals(mode)) {
                return;
            }
            if ("sleep".equals(mode)) {
                Thread.sleep(10_000);
                return;
            }
            byte[] response = "malformed".equals(mode) ? "[]".getBytes(StandardCharsets.UTF_8) : request;
            output.writeInt(response.length);
            output.write(response);
            output.flush();
        }
    }
}
