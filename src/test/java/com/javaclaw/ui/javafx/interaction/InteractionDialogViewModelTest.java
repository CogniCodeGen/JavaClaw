package com.javaclaw.ui.javafx.interaction;

import com.javaclaw.api.interaction.ChoiceOption;
import com.javaclaw.api.interaction.ChoiceRequest;
import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.SecretRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionDialogViewModelTest {

    @Test
    void doubleConfirmationRequiresTrimmedExactKeyword() {
        try (ConfirmDialogViewModel model = new ConfirmDialogViewModel()) {
            model.apply(new ConfirmRequest("delete", "不可逆", "删除目标",
                    ConfirmKind.DOUBLE_CONFIRM, 30, "DELETE", true));

            assertFalse(model.validBinding().get());
            model.keywordInputProperty().set(" DELETE ");
            assertTrue(model.validBinding().get());
            assertTrue(model.detailsProperty().get().contains("工具名：delete"));
        }
    }

    @Test
    void ordinaryConfirmationDoesNotRequireKeyword() {
        try (ConfirmDialogViewModel model = new ConfirmDialogViewModel()) {
            model.apply(new ConfirmRequest("read", "中风险", "读取",
                    ConfirmKind.CONFIRM, 30, "", false));

            assertTrue(model.validBinding().get());
            assertFalse(model.keywordRequiredProperty().get());
        }
    }

    @Test
    void choiceAndSecretStateAreBoundedAndClearable() {
        ChoiceDialogViewModel choice = new ChoiceDialogViewModel();
        choice.apply(new ChoiceRequest("选择", "请选择账号",
                List.of(new ChoiceOption("a", "A", ""),
                        new ChoiceOption("b", "B", "备用")), 30));
        assertEquals("a", choice.selectedProperty().get().id());

        SecretDialogViewModel secret = new SecretDialogViewModel();
        secret.apply(new SecretRequest("密码", "请输入", 30, 3));
        secret.secretProperty().set("abcdef");
        secret.clamp();
        assertArrayEquals(new char[]{'a', 'b', 'c'}, secret.takeAndClear());
        assertEquals("", secret.secretProperty().get());
    }
}
