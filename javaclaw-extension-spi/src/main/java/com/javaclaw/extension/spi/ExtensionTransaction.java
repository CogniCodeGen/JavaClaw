package com.javaclaw.extension.spi;

import java.util.List;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.TurnId;

/** 内置扩展事务内可用的最小存储操作。 */
public interface ExtensionTransaction {
    /**
     * 读取扩展私有记录。
     *
     * @param collection 集合名
     * @param key 键
     * @return 记录，不存在时为空
     */
    Optional<VersionedDocument> get(String collection, String key);

    /**
     * 按键稳定排序分页读取扩展私有记录。
     *
     * @param collection 集合名
     * @param afterKey 排他游标；从头读取为空字符串
     * @param limit 页大小，1 到 500
     * @return 版本化记录
     */
    List<VersionedDocument> list(String collection, String afterKey, int limit);

    /**
     * 分页读取一个文档的不可变历史，包含 tombstone。
     *
     * @param collection 集合名
     * @param key 文档键
     * @param afterRevision 排他版本游标；从头读取为 0
     * @param limit 页大小，1 到 500
     * @return 按 revision 升序排列的历史
     */
    List<DocumentRevision> history(String collection, String key, long afterRevision, int limit);

    /**
     * 按键分页列出当前 tombstone，供管理、统计和显式恢复使用。
     *
     * @param collection 集合名
     * @param afterKey 排他键游标
     * @param limit 页大小，1 到 500
     * @return 当前删除标记
     */
    List<DocumentRevision> listTombstones(String collection, String afterKey, int limit);

    /**
     * 条件写入扩展私有记录。
     *
     * @param collection 集合名
     * @param key 键
     * @param expectedRevision 期望版本；创建为 0
     * @param payload 内容
     * @return 新版本
     */
    long put(String collection, String key, long expectedRevision, CanonicalPayload payload);

    /**
     * 条件删除扩展私有记录。
     *
     * @param collection 集合名
     * @param key 键
     * @param expectedRevision 期望版本
     */
    void delete(String collection, String key, long expectedRevision);

    /**
     * 与扩展状态同事务追加 Core Item。
     *
     * @param turnId 所属 Turn
     * @param kind 展示类别
     * @param schemaId 扩展注册的 schema
     * @param payload 规范内容
     * @param status Item 状态
     */
    void appendItem(TurnId turnId, String kind, String schemaId, CanonicalPayload payload, ItemStatus status);

    /**
     * 与扩展状态同事务追加领域事件。
     *
     * @param topic 稳定事件主题
     * @param payload 规范事件内容
     */
    void appendEvent(String topic, CanonicalPayload payload);

    /**
     * 与扩展状态同事务写入待投递消息。
     *
     * @param destination 目标端口
     * @param idempotencyKey 全局幂等键
     * @param payload 规范消息内容
     */
    void enqueueOutbox(String destination, String idempotencyKey, CanonicalPayload payload);
}
