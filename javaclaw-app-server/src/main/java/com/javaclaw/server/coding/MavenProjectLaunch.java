package com.javaclaw.server.coding;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.nativehost.coding.WorkspaceFileAccess;

/** 从冻结执行根内定位 Maven 项目；配置只读一次，解析参数和证据始终来自相同字节。 */
final class MavenProjectLaunch {
    private MavenProjectLaunch() {}

    static Result resolve(CodingInvocation invocation, Path cwd, List<String> arguments) throws Exception {
        Path root = invocation.turn().executionRoot();
        WorkspaceFileAccess files = new WorkspaceFileAccess(root, invocation.permission());
        return resolve(new NativeFiles(files, invocation.cancellation()), root, cwd, arguments);
    }

    static Result resolve(ProjectFiles files, Path root, Path cwd, List<String> arguments) throws Exception {
        Path start = projectDirectory(files, root, cwd, arguments);
        Path basedir = findBaseDirectory(files, root, start);
        String config = relative(root, basedir.resolve(".mvn/jvm.config"));
        var snapshot = files.read(config);
        List<String> options = snapshot.exists() ? MavenJvmArguments.parse(snapshot.content()) : List.of();
        var evidence = new Evidence(
                relative(root, basedir), config, snapshot.exists(), snapshot.sha256(), snapshot.content().length);
        return new Result(basedir, options, evidence);
    }

    private static Path projectDirectory(ProjectFiles files, Path root, Path cwd, List<String> arguments)
            throws Exception {
        Optional<String> selected = selectedFile(arguments);
        if (selected.isEmpty()) {
            return cwd;
        }
        Path path = cwd.resolve(selected.orElseThrow()).normalize();
        if (!path.startsWith(root)) {
            throw new SecurityException("MAVEN_PROJECT_OUTSIDE_EXECUTION_ROOT");
        }
        var entry = files.stat(relative(root, path))
                .orElseThrow(() -> new IllegalArgumentException("MAVEN_PROJECT_FILE_MISSING"));
        return entry.directory() ? path : path.getParent();
    }

    private static Optional<String> selectedFile(List<String> arguments) {
        for (int index = 1; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            if (argument.equals("--")) {
                break;
            }
            if (argument.equals("-f") || argument.equals("--file")) {
                if (index + 1 == arguments.size() || arguments.get(index + 1).isBlank()) {
                    throw new IllegalArgumentException("MAVEN_PROJECT_FILE_MISSING_ARGUMENT");
                }
                return Optional.of(arguments.get(index + 1));
            }
            if (argument.startsWith("--file=")) {
                return nonempty(argument.substring(7));
            }
            if (argument.startsWith("-f")
                    && argument.length() > 2
                    && !List.of("-fae", "-ff", "-fn").contains(argument)) {
                return nonempty(argument.substring(argument.charAt(2) == '=' ? 3 : 2));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> nonempty(String value) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("MAVEN_PROJECT_FILE_MISSING_ARGUMENT");
        }
        return Optional.of(value);
    }

    private static Path findBaseDirectory(ProjectFiles files, Path root, Path start) throws Exception {
        Path current = start;
        for (int depth = 0; depth < 64; depth++) {
            var metadata = files.stat(relative(root, current.resolve(".mvn")));
            if (metadata.filter(WorkspaceFileAccess.Entry::directory).isPresent()) {
                return current;
            }
            if (current.equals(root)) {
                return start;
            }
            current = current.getParent();
        }
        throw new IllegalArgumentException("MAVEN_PROJECT_SEARCH_LIMIT: 祖先目录超过 64 层");
    }

    private static String relative(Path root, Path path) {
        String result = root.relativize(path).toString().replace('\\', '/');
        return result.isEmpty() ? "." : result;
    }

    interface ProjectFiles {
        Optional<WorkspaceFileAccess.Entry> stat(String path) throws Exception;

        WorkspaceFileAccess.Snapshot read(String path) throws Exception;
    }

    private record NativeFiles(WorkspaceFileAccess files, CancellationToken cancellation) implements ProjectFiles {
        @Override
        public Optional<WorkspaceFileAccess.Entry> stat(String path) throws Exception {
            return files.stat(path, cancellation);
        }

        @Override
        public WorkspaceFileAccess.Snapshot read(String path) throws Exception {
            return files.read(path, 64 * 1024, cancellation);
        }
    }

    record Result(Path baseDirectory, List<String> jvmArguments, Evidence evidence) {}

    record Evidence(String relativeBaseDirectory, String configurationPath, boolean exists, String sha256, int bytes) {}
}
