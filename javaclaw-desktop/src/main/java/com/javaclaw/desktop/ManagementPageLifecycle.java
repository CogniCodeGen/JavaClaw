package com.javaclaw.desktop;

import java.util.function.Consumer;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyObjectProperty;

/** 管理页在对象切换、页面切换和窗口关闭时必须遵守的编辑生命周期。 */
interface ManagementPageLifecycle {
    /** 返回页面是否包含尚未保存的用户修改。 */
    ReadOnlyBooleanProperty dirtyProperty();

    /** 返回当前草稿是否允许发起保存。 */
    ReadOnlyBooleanProperty canSaveProperty();

    /** 返回首次加载、刷新或错误状态；不代表写请求是否可取消。 */
    ReadOnlyObjectProperty<LoadState> loadStateProperty();

    /** 返回当前资源的用户可读名称。 */
    String currentResource();

    /** 请求保存；回调仅在保存取得确定成功或失败结果后执行。 */
    void requestSave(Consumer<Boolean> completion);

    /** 放弃当前本地草稿，不向服务端发起写请求。 */
    void discard();

    /** 取消尚未取得结果的纯读取请求；已经提交的写请求不受影响。 */
    void cancelReads();

    /** 释放页面监听器；不得将结果未知的写请求伪装为已取消。 */
    void dispose();

    /** 页面内容加载状态。 */
    enum LoadState {
        INITIAL_LOADING,
        READY,
        REFRESHING,
        ERROR
    }
}
