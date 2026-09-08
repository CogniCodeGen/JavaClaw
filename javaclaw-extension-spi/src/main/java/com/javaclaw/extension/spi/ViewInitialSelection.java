package com.javaclaw.extension.spi;

/**
 * 数据源在 values 中声明的可选初选提示，只影响展示，不授予命令或资源访问权限。 客户端仅在该页面尚未初始化选择时使用提示，并要求已加载行的键和版本同时匹配； 用户的显式选择及清空优先。缺少、未知版本或不匹配的提示不得替换成首行或
 * latest。
 *
 * @param version 元数据版本，目前为 1
 * @param dataSourceId 提示所属数据源，必须与查询结果一致，非空且最多 128 字符
 * @param key 已保存引用对应的行键，非空且最多 2048 字符
 * @param revisionField 行中精确版本字段，非空且最多 128 字符
 * @param revision 已保存引用版本，非负，无单位
 */
public record ViewInitialSelection(int version, String dataSourceId, String key, String revisionField, long revision) {
    /** values 内保留的元数据键，不得用作业务命令参数。 */
    public static final String VALUES_KEY = "view.initialSelection";

    /** 当前支持的元数据版本。 */
    public static final int VERSION = 1;

    /** 校验版本和有界标识；客户端遇到不支持的元数据应保持未选。 */
    public ViewInitialSelection {
        if (version != VERSION || revision < 0) {
            throw new IllegalArgumentException("initial selection version or revision is invalid");
        }
        dataSourceId = bounded(dataSourceId, "dataSourceId", 128);
        key = bounded(key, "key", 2048);
        revisionField = bounded(revisionField, "revisionField", 128);
    }

    private static String bounded(String value, String name, int limit) {
        String checked = ViewSchemaText.required(value, name);
        if (checked.length() > limit) {
            throw new IllegalArgumentException(name + " exceeds initial selection limit");
        }
        return checked;
    }
}
