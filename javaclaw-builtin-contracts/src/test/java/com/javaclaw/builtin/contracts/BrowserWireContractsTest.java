package com.javaclaw.builtin.contracts;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.TurnId;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserWireContractsTest {
    private final CanonicalJson json = new CanonicalJson();

    @Test
    void 启动命令往返保留所有权账号代次和操作租约() {
        var task = new BrowserContracts.OpenTask(
                BrowserContractFixtures.SESSION,
                BrowserContractFixtures.owner(),
                BrowserContractFixtures.ORIGIN.resolve("/docs"),
                BrowserContractFixtures.lease());
        assertEquals(task, json.decode(json.encode(task), BrowserContracts.OpenTask.class));
        var open = new BrowserCommands.Open(
                task.uri(), Optional.of(new SiteAccountContracts.Selection("docs", "work")), false);
        var command = new BrowserCommands.Invocation("browser_open", json.encode(open));
        var restored = json.decode(json.encode(command), BrowserCommands.Invocation.class);
        assertEquals(command, restored);
        assertEquals(open, json.decode(restored.payload(), BrowserCommands.Open.class));
        assertThrows(NullPointerException.class, () -> new BrowserCommands.Open(null, Optional.empty(), false));
        assertThrows(NullPointerException.class, () -> new BrowserCommands.Open(task.uri(), null, false));
        assertThrows(NullPointerException.class, () -> new BrowserCommands.Invocation("browser_open", null));
    }

    @Test
    void 上传和图片坐标动作使用类型化附件与帧引用往返() {
        var input = new BrowserContracts.ActionInput(
                "",
                Optional.of(new BrowserContracts.Point(10, 20)),
                Optional.of(new BrowserContracts.Drag(
                        new BrowserContracts.Point(10, 20), new BrowserContracts.Point(30, 40))),
                Optional.of(new BrowserContracts.FileSpec("upload.png", "image/png")));
        var action = new BrowserContracts.Action(
                BrowserContracts.Operation.UPLOAD, new BrowserContracts.Target("page", "r1", "frame"), input);
        var request = new BrowserCommands.Act(action, Optional.of(BrowserContractFixtures.attachment()));
        assertEquals(request, json.decode(json.encode(request), BrowserCommands.Act.class));
        assertEquals("a".repeat(64), request.upload().orElseThrow().digest());
        assertThrows(NullPointerException.class, () -> new BrowserCommands.Act(null, Optional.empty()));
        assertThrows(NullPointerException.class, () -> new BrowserCommands.Act(action, null));
        assertEquals(Set.of("action", "upload"), names(BrowserCommands.Act.class));
    }

    @Test
    void 版本化截图结果往返保留观察所有者帧像素和附件关联() {
        var screenshot = new BrowserResult(
                1, BrowserContractFixtures.observation(true, true), Optional.of(BrowserContractFixtures.attachment()));
        var result = json.decode(json.encode(screenshot), BrowserResult.class);
        assertEquals(screenshot, result);
        var frame = result.observation().frame().orElseThrow();
        assertEquals(3, frame.controlGeneration());
        assertEquals(7, frame.documentEpoch());
        assertEquals(2560, frame.imageWidth());
        assertEquals(2, frame.viewport().deviceScaleFactor());
        assertEquals(result.observation().page().pageId(), frame.pageId());
        assertEquals(
                BrowserContractFixtures.WORKSPACE,
                result.observation().session().owner().workspaceId());
        assertEquals(128, result.observation().artifact().orElseThrow().sizeBytes());
        assertEquals(
                new BrowserResult(1, BrowserContractFixtures.observation(false, false), Optional.empty()),
                json.decode(
                        json.encode(new BrowserResult(
                                1, BrowserContractFixtures.observation(false, false), Optional.empty())),
                        BrowserResult.class));
    }

    @Test
    void 不完整截图和附件结果不能被序列化载荷伪装成可信图片() {
        var text = BrowserContractFixtures.observation(false, false);
        assertThrows(IllegalArgumentException.class, () -> new BrowserResult(2, text, Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserResult(1, text, Optional.of(BrowserContractFixtures.attachment())));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserResult(1, BrowserContractFixtures.observation(false, true), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BrowserResult(1, BrowserContractFixtures.observation(true, false), Optional.empty()));
        assertThrows(NullPointerException.class, () -> new BrowserResult(1, null, Optional.empty()));
        var valid = json.encode(new BrowserResult(
                1, BrowserContractFixtures.observation(true, true), Optional.of(BrowserContractFixtures.attachment())));
        CanonicalPayload invalid = new CanonicalPayload(valid.json().replace("\"version\":1", "\"version\":2"));
        assertThrows(RuntimeException.class, () -> json.decode(invalid, BrowserResult.class), "wire输入也必须执行领域构造校验");
    }

    @Test
    void 没有窗口也能往返续接失败状态且兼容旧状态构造() {
        var parent = TurnId.random();
        var failed = new BrowserCommands.ContinuationStatus(
                BrowserCommands.ContinuationState.FAILED,
                BrowserCommands.ContinuationFailure.BUDGET_EXHAUSTED,
                "原预算已用完",
                parent,
                Optional.empty());
        var status = new BrowserCommands.Status(true, Optional.empty(), "浏览器已就绪", Optional.of(failed));
        assertEquals(status, json.decode(json.encode(status), BrowserCommands.Status.class));
        assertTrue(new BrowserCommands.Status(false, Optional.empty(), "不可用")
                .continuation()
                .isEmpty());
        assertThrows(
                NullPointerException.class, () -> new BrowserCommands.Status(true, Optional.empty(), "ready", null));
        assertThrows(
                NullPointerException.class,
                () -> new BrowserCommands.ContinuationStatus(
                        null, BrowserCommands.ContinuationFailure.NONE, "", parent, Optional.empty()));
        assertThrows(
                NullPointerException.class,
                () -> new BrowserCommands.ContinuationStatus(
                        BrowserCommands.ContinuationState.REQUESTED,
                        BrowserCommands.ContinuationFailure.NONE,
                        "",
                        null,
                        Optional.empty()));
    }

    @Test
    void 表单准备投影只携标签和引用且复制外部列表() {
        var target = new BrowserContracts.CredentialsTarget("page", "user-ref", "password-ref");
        var form = new BrowserContracts.LoginForm("登录", "用户名", "密码", target);
        var forms = new ArrayList<>(List.of(form));
        var result = new BrowserCommands.LoginForms(forms);
        forms.clear();
        assertEquals(1, result.forms().size());
        assertEquals(result, json.decode(json.encode(result), BrowserCommands.LoginForms.class));
        assertThrows(UnsupportedOperationException.class, () -> result.forms().clear());
        assertEquals(
                Set.of("label", "usernameLabel", "passwordLabel", "target"), names(BrowserContracts.LoginForm.class));
        assertEquals(Set.of("pageId", "usernameRef", "passwordRef"), names(BrowserContracts.CredentialsTarget.class));
        assertFalse(json.encode(result).json().contains("\"value\":"));
    }

    private static Set<String> names(Class<?> type) {
        return java.util.Arrays.stream(type.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(Collectors.toSet());
    }
}
