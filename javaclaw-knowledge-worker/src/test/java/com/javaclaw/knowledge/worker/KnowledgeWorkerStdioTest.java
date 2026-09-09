package com.javaclaw.knowledge.worker;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

import com.javaclaw.builtin.contracts.KnowledgeWorkerProtocol;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.LengthPrefixedFraming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeWorkerStdioTest {
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private final CanonicalJson json = new CanonicalJson();

    @Test
    void 冷启动解析Docx时日志不能污染标准输出协议() throws Exception {
        byte[] content = document();

        KnowledgeWorkerProtocol.Response response = exchange(content);

        assertTrue(response.error().isEmpty());
        assertEquals("local document 42", response.result().orElseThrow().text());
        assertEquals(digest(content), response.result().orElseThrow().digest());
    }

    @Test
    void 冷启动解析损坏Docx仍返回稳定业务失败且没有杂音() throws Exception {
        KnowledgeWorkerProtocol.Response response = exchange("broken-docx".getBytes(StandardCharsets.UTF_8));

        assertTrue(response.result().isEmpty());
        assertEquals("KNOWLEDGE_EXTRACTION_FAILED", response.error().orElseThrow());
    }

    private KnowledgeWorkerProtocol.Response exchange(byte[] content) throws Exception {
        // 每次使用新的 JVM，确保覆盖 POI / Log4j 首次加载；进程内 run 测试无法发现真实 stdout 日志污染。
        Process process = new ProcessBuilder(
                        javaExecutable(), "-XX:-UsePerfData", "-cp", classpath(), KnowledgeWorkerMain.class.getName())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        FutureTask<byte[]> output = new FutureTask<>(
                () -> process.getInputStream().readNBytes(KnowledgeWorkerProtocol.MAXIMUM_JSON_BYTES + 5));
        Thread.ofVirtual().name("knowledge-stdio-test").start(output);
        try {
            var request = new KnowledgeWorkerProtocol.Request(1, DOCX, digest(content), content.length, 1_024);
            LengthPrefixedFraming.write(
                    process.getOutputStream(),
                    json.encode(request).json().getBytes(StandardCharsets.UTF_8),
                    KnowledgeWorkerProtocol.MAXIMUM_JSON_BYTES);
            LengthPrefixedFraming.write(
                    process.getOutputStream(), content, KnowledgeWorkerProtocol.MAXIMUM_CONTENT_BYTES);
            process.getOutputStream().close();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Knowledge Worker 未在期限内结束");
            assertEquals(0, process.exitValue());
            ByteArrayInputStream captured = new ByteArrayInputStream(output.get(2, TimeUnit.SECONDS));
            byte[] frame = LengthPrefixedFraming.read(captured, KnowledgeWorkerProtocol.MAXIMUM_JSON_BYTES);
            assertEquals(-1, captured.read(), "stdout 只能包含一个协议帧，不能追加解析器日志");
            return json.decode(
                    json.parse(new String(frame, StandardCharsets.UTF_8)), KnowledgeWorkerProtocol.Response.class);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            if (!output.isDone()) {
                output.cancel(true);
            }
        }
    }

    private static byte[] document() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(bytes)) {
            entry(archive, "[Content_Types].xml", """
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>
                    """);
            entry(archive, "_rels/.rels", """
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                    </Relationships>
                    """);
            entry(archive, "word/document.xml", """
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body><w:p><w:r><w:t>local document 42</w:t></w:r></w:p></w:body>
                    </w:document>
                    """);
        }
        return bytes.toByteArray();
    }

    private static void entry(ZipOutputStream archive, String name, String xml) throws Exception {
        archive.putNextEntry(new ZipEntry(name));
        archive.write(xml.getBytes(StandardCharsets.UTF_8));
        archive.closeEntry();
    }

    private static String digest(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private static String javaExecutable() {
        String name =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", name).toString();
    }

    private static String classpath() {
        return System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    }
}
