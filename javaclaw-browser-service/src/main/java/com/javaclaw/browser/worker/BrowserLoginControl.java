package com.javaclaw.browser.worker;

/** Worker 读取宿主非敏感登录控制信号的最小边界。 */
interface BrowserLoginControl extends AutoCloseable {
    /** 登录控制决定。 */
    enum Decision {
        /** 继续等待用户操作。 */
        WAIT,
        /** 遮罩敏感控件后保存 storage state。 */
        SAVE,
        /** 取消并丢弃当前 Context。 */
        CANCEL
    }

    /** @return 当前控制决定 */
    Decision decision();

    /** 清理控制文件；不得影响 Browser profile 之外的路径。 */
    @Override
    void close();
}
