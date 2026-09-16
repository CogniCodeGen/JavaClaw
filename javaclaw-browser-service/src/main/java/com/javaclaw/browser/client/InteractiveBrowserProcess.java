package com.javaclaw.browser.client;

/** 常驻进程的宿主监护所有权；测试可以注入管道进程，生产必须绑定原生 lease。 */
record InteractiveBrowserProcess(Process process, Runnable touch, AutoCloseable owner) implements AutoCloseable {
    @Override
    public void close() {
        try {
            owner.close();
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new BrowserWorkerException("Browser process cleanup failed", failure);
        }
    }
}
