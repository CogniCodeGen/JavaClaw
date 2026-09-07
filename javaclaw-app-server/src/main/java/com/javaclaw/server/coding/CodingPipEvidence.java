package com.javaclaw.server.coding;

import java.util.Base64;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.builtin.contracts.CodingResults;
import com.javaclaw.protocol.CanonicalJson;

/** 固定 pip 包装进程分离安装日志与报告；报告始终是原生输出证据，不是可信指令或依赖推断。 */
final class CodingPipEvidence {
    static final int MAX_REPORT_BYTES = 256 * 1024;
    static final String SCRIPT = """
            import base64, json, os, stat, subprocess, sys, tempfile, venv
            destination, source, proxy = sys.argv[1:4]
            report_directory = tempfile.mkdtemp(prefix='pip-evidence-', dir=os.path.dirname(destination))
            report = os.path.join(report_directory, 'report.json')
            code, encoded, error = 1, '', ''
            try:
                # 可移植 Unix Python 的动态库相对于原始可执行文件定位，不能复制入口后丢失该关系。
                venv.create(destination, with_pip=True, symlinks=os.name != 'nt')
                executable = os.path.join(destination, 'Scripts' if os.name == 'nt' else 'bin', 'python.exe' if os.name == 'nt' else 'python')
                args = [executable, '-m', 'pip', 'install', '--proxy', proxy, '--report', report]
                args += ['-r', source] if source.endswith('.txt') else [source]
                process = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
                while True:
                    chunk = process.stdout.read1(8192)
                    if not chunk:
                        break
                    sys.stderr.buffer.write(chunk)
                    sys.stderr.buffer.flush()
                code = process.wait()
                if os.path.islink(report):
                    raise ValueError('report is a symbolic link')
                descriptor = os.open(report, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_BINARY', 0))
                with os.fdopen(descriptor, 'rb') as stream:
                    if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
                        raise ValueError('report is not a regular file')
                    data = stream.read(262145)
                    if len(data) > 262144:
                        raise ValueError('report exceeds 262144 bytes')
                    encoded = base64.b64encode(data).decode('ascii')
            except Exception as failure:
                error = str(failure)[:4096]
                output = getattr(failure, 'output', None)
                if isinstance(output, bytes):
                    sys.stderr.buffer.write(output[:262144])
                    sys.stderr.buffer.flush()
            finally:
                try:
                    os.unlink(report)
                    os.rmdir(report_directory)
                except OSError:
                    pass
            print(json.dumps({'format': 'javaclaw.pip-report.v1', 'exitCode': code, 'reportBase64': encoded, 'error': error}), flush=True)
            sys.exit(code)
            """;

    private CodingPipEvidence() {}

    static Optional<byte[]> report(CanonicalJson json, CodingResults.CommandResult result) {
        try {
            var envelope =
                    json.decode(new CanonicalPayload(result.output().stdout().strip()), ReportEnvelope.class);
            if (!envelope.format().equals("javaclaw.pip-report.v1")
                    || !envelope.error().isEmpty()
                    || result.command()
                            .exitCode()
                            .filter(code -> code == envelope.exitCode())
                            .isEmpty()) {
                return Optional.empty();
            }
            byte[] bytes = Base64.getDecoder().decode(envelope.reportBase64());
            if (bytes.length == 0 || bytes.length > MAX_REPORT_BYTES) {
                return Optional.empty();
            }
            return Optional.of(bytes);
        } catch (RuntimeException invalidOrIncomplete) {
            // 不从混合、截断或非固定 envelope 的 stdout 猜测 JSON；原始输出仍单独保留。
            return Optional.empty();
        }
    }

    private record ReportEnvelope(String format, int exitCode, String reportBase64, String error) {}
}
