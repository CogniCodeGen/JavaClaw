package com.javaclaw.api.interaction;

/**
 * 用户选择项。
 *
 * @param id          稳定标识，返回给调用方
 * @param label       用户可见名称
 * @param description 可选补充说明
 */
public record ChoiceOption(String id, String label, String description) {

    public ChoiceOption {
        if (id == null) id = "";
        if (label == null) label = "";
        if (description == null) description = "";
    }

    @Override
    public String toString() {
        return description.isBlank() ? label : label + " — " + description;
    }
}
