package com.javaclaw.nativehost.ffm;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Locale;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * 专用 macOS helper 拥有的 bootstrap 子命名空间；requestor receive right 的销毁负责撤销全部实例登记。
 *
 * <p>只能在独立可信 helper 中安装，不能改变 App Server JVM 的 task special port。任何不可用或创建失败均抛出异常， 不回退宿主命名空间。旧 SDK
 * 声明不代表当前系统实现可用，必须检查每次原生返回值。
 */
public final class MacBootstrapNamespace implements AutoCloseable {
    private static final int BOOTSTRAP_SPECIAL_PORT = 4;
    private static final int RECEIVE_RIGHT = 1;

    private final Bindings bindings;
    private final int task;
    private final int parent;
    private final int requestor;
    private final int subset;
    private boolean installed;
    private boolean closed;

    private MacBootstrapNamespace(Bindings bindings, int task, int parent, int requestor, int subset) {
        this.bindings = bindings;
        this.task = task;
        this.parent = parent;
        this.requestor = requestor;
        this.subset = subset;
    }

    /**
     * 创建当前 helper 独占的子命名空间，尚不改变继承关系。
     *
     * @return 当前线程拥有且必须关闭的原生租约
     * @throws IOException 原生 API 不可用或系统拒绝创建
     */
    public static MacBootstrapNamespace open() throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            throw new IOException("MAC_BOOTSTRAP_UNAVAILABLE: unsupported platform");
        }
        Bindings api = Bindings.load();
        int task = api.call(api.task());
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment parent = arena.allocate(JAVA_INT);
            MemorySegment owner = arena.allocate(JAVA_INT);
            MemorySegment child = arena.allocate(JAVA_INT);
            checked(api.call(api.getSpecial(), task, BOOTSTRAP_SPECIAL_PORT, parent), "get task bootstrap");
            int parentPort = parent.get(JAVA_INT, 0);
            try {
                checked(api.call(api.allocate(), task, RECEIVE_RIGHT, owner), "allocate requestor");
                int requestor = owner.get(JAVA_INT, 0);
                try {
                    checked(api.call(api.subset(), parentPort, requestor, child), "bootstrap_subset");
                    return new MacBootstrapNamespace(api, task, parentPort, requestor, child.get(JAVA_INT, 0));
                } catch (IOException failure) {
                    release(api, api.destroy(), task, requestor, failure);
                    throw failure;
                }
            } catch (IOException failure) {
                release(api, api.deallocate(), task, parentPort, failure);
                throw failure;
            }
        }
    }

    /**
     * 验证同实例可查找、兄弟实例和宿主不可查找动态服务，随后回收所有探针权利。
     *
     * <p>不改变调用进程的 task bootstrap port，也不启动窗口；成功只证明命名空间前置条件，不能代替可见 Chromium 门禁。
     *
     * @throws IOException 原生能力不可用或任何隔离断言失败
     */
    public static void verifyIsolation() throws IOException {
        try (MacBootstrapNamespace first = open();
                MacBootstrapNamespace second = open()) {
            MacBootstrapIsolationProbe.verify(first.task, first.subset, second.subset, first.parent);
            MacBootstrapIsolationProbe.verify(second.task, second.subset, first.subset, second.parent);
        } catch (RuntimeException failure) {
            throw new IOException("MAC_BOOTSTRAP_ISOLATION_FAILED: native bindings", failure);
        }
    }

    /**
     * 安装子命名空间，使随后启动的 Sandbox 进程及后代继承；调用者必须是独立 helper。
     *
     * @throws IOException 租约已关闭或内核拒绝安装
     */
    public void installForChildren() throws IOException {
        if (closed) {
            throw new IOException("MAC_BOOTSTRAP_UNAVAILABLE: namespace closed");
        }
        checked(bindings.call(bindings.setSpecial(), task, BOOTSTRAP_SPECIAL_PORT, subset), "install task bootstrap");
        installed = true;
    }

    /** 恢复 helper 的父命名空间并撤销子命名空间；幂等，不静默忽略原生清理失败。 */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        IOException failure = new IOException("MAC_BOOTSTRAP_CLEANUP_FAILED");
        if (installed) {
            release(bindings, bindings.setSpecial(), task, BOOTSTRAP_SPECIAL_PORT, parent, failure);
        }
        release(bindings, bindings.destroy(), task, requestor, failure);
        release(bindings, bindings.deallocate(), task, subset, failure);
        release(bindings, bindings.deallocate(), task, parent, failure);
        if (failure.getSuppressed().length > 0) {
            throw failure;
        }
    }

    private static void release(Bindings api, MethodHandle operation, int task, int port, IOException failure) {
        try {
            checked(api.call(operation, task, port), "release Mach port");
        } catch (IOException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    private static void release(
            Bindings api, MethodHandle operation, int task, int type, int port, IOException failure) {
        try {
            checked(api.call(operation, task, type, port), "restore task bootstrap");
        } catch (IOException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    private static void checked(int result, String operation) throws IOException {
        if (result != 0) {
            throw new IOException("MAC_BOOTSTRAP_UNAVAILABLE: " + operation + " returned " + result);
        }
    }

    private record Bindings(
            MethodHandle task,
            MethodHandle getSpecial,
            MethodHandle setSpecial,
            MethodHandle allocate,
            MethodHandle subset,
            MethodHandle destroy,
            MethodHandle deallocate) {
        static Bindings load() throws IOException {
            try {
                Linker linker = Linker.nativeLinker();
                SymbolLookup symbols = linker.defaultLookup();
                FunctionDescriptor output = FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS);
                FunctionDescriptor input = FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
                FunctionDescriptor port = FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT);
                return new Bindings(
                        lookup(linker, symbols, "mach_task_self", FunctionDescriptor.of(JAVA_INT)),
                        lookup(linker, symbols, "task_get_special_port", output),
                        lookup(linker, symbols, "task_set_special_port", input),
                        lookup(linker, symbols, "mach_port_allocate", output),
                        lookup(linker, symbols, "bootstrap_subset", output),
                        lookup(linker, symbols, "mach_port_destroy", port),
                        lookup(linker, symbols, "mach_port_deallocate", port));
            } catch (RuntimeException unavailable) {
                throw new IOException("MAC_BOOTSTRAP_UNAVAILABLE: native bindings", unavailable);
            }
        }

        int call(MethodHandle operation, Object... arguments) throws IOException {
            try {
                return (int) operation.invokeWithArguments(arguments);
            } catch (Throwable failure) {
                throw new IOException("MAC_BOOTSTRAP_UNAVAILABLE: native call", failure);
            }
        }

        private static MethodHandle lookup(
                Linker linker, SymbolLookup symbols, String name, FunctionDescriptor descriptor) {
            return linker.downcallHandle(symbols.find(name).orElseThrow(), descriptor);
        }
    }
}
