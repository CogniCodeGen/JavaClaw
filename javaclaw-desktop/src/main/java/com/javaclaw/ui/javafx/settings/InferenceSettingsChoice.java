package com.javaclaw.ui.javafx.settings;

/** 本地推理设置页 ComboBox/ListView 共用的标签值。 */
record InferenceSettingsChoice<T>(String label, T value) {
    InferenceSettingsChoice {
        label = label == null ? "" : label;
        if (value == null) throw new IllegalArgumentException("选项值不能为空");
    }
    @Override public String toString() { return label; }
}
