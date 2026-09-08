package com.javaclaw.protocol;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ItemId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.TurnStreamCall;
import com.javaclaw.api.TurnStreamEvent;
import com.javaclaw.api.TurnStreamKind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnStreamRpcContractsTest {
    private final CanonicalJson json = new CanonicalJson();
    private final TurnId turnId = TurnId.random();
    private final TurnStreamCall call = new TurnStreamCall(1, "attempt", ItemId.random());

    @Test
    void 公开事件保持强类型标量ID与每条游标且不携带内部字段() {
        var event = new TurnStreamEvent(
                turnId,
                "cursor",
                "START",
                new TurnStreamEvent.Data(
                        TurnStreamKind.TEXT_DELTA, Optional.of(call), "😀正文", 0, Optional.empty(), Optional.empty()));
        var notification = new TurnStreamRpcContracts.Notification("sub", List.of(event), Optional.empty());
        assertEquals(notification, json.decode(json.encode(notification), TurnStreamRpcContracts.Notification.class));
        assertTrue(json.encode(notification).json().contains("\"turnId\":\""));
        assertThrows(
                ProtocolException.class,
                () -> json.decode(
                        json.parse(json.encode(notification)
                                .json()
                                .replace("\"subscriptionId\"", "\"reasoning\":\"secret\",\"subscriptionId\"")),
                        TurnStreamRpcContracts.Notification.class));
    }

    @Test
    void 终态无需模型调用身份且水位帧不包含数据事件() {
        var event = new TurnStreamEvent(
                turnId,
                "last",
                "START",
                new TurnStreamEvent.Data(
                        TurnStreamKind.TURN_FINISHED,
                        Optional.empty(),
                        "",
                        0,
                        Optional.of(0L),
                        Optional.of(TurnStatus.CANCELLED)));
        assertEquals(event, json.decode(json.encode(event), TurnStreamEvent.class));
        var watermark = new TurnStreamRpcContracts.Watermark("last", true, Optional.of(0L));
        var notification = new TurnStreamRpcContracts.Notification("sub", List.of(), Optional.of(watermark));
        assertEquals(notification, json.decode(json.encode(notification), TurnStreamRpcContracts.Notification.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TurnStreamRpcContracts.Notification("sub", List.of(event), Optional.of(watermark)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TurnStreamRpcContracts.Notification("sub", List.of(), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TurnStreamRpcContracts.Watermark("last", false, Optional.of(0L)));
    }

    @Test
    void 新流能力与旧客户端取交集并要求逐方法协商() {
        var old = new ProtocolNegotiator(StableCapabilities.all(), Set.of())
                .negotiate(new InitializeParams(
                        ProtocolVersion.CURRENT,
                        new ClientInfo("old", "6"),
                        new CapabilityAdvertisement(Set.of(), Set.of())));
        assertThrows(ProtocolException.class, () -> MethodCatalog.require(TurnStreamRpcContracts.SUBSCRIBE, old));
        var current = new NegotiatedCapabilities(Set.of(TurnStreamRpcContracts.CAPABILITY), Set.of());
        assertEquals(
                RpcMethodKind.COMMAND,
                MethodCatalog.require(TurnStreamRpcContracts.SUBSCRIBE, current).kind());
        assertEquals(
                RpcMethodKind.NOTIFICATION,
                MethodCatalog.require(TurnStreamRpcContracts.EVENT, current).kind());
        assertEquals(
                RpcMethodKind.QUERY,
                MethodCatalog.require(TurnStreamRpcContracts.LIST, current).kind());
    }

    @Test
    void 严格分页与控制字段拒绝空ID和超大请求() {
        assertThrows(IllegalArgumentException.class, () -> new TurnStreamRpcContracts.Subscribe("", turnId, "START"));
        assertThrows(IllegalArgumentException.class, () -> new TurnStreamRpcContracts.Subscribe("sub", turnId, ""));
        assertThrows(IllegalArgumentException.class, () -> new TurnStreamRpcContracts.Unsubscribe("x".repeat(101)));
        assertThrows(IllegalArgumentException.class, () -> new TurnStreamRpcContracts.ListRequest(turnId, "START", 0));
        assertThrows(
                IllegalArgumentException.class, () -> new TurnStreamRpcContracts.ListRequest(turnId, "START", 129));
        var request = new TurnStreamRpcContracts.Subscribe("sub", turnId, "START");
        assertEquals(request, json.decode(json.encode(request), TurnStreamRpcContracts.Subscribe.class));
        var page = new TurnStreamRpcContracts.Page(List.of(), "START", false);
        assertEquals(page, json.decode(json.encode(page), TurnStreamRpcContracts.Page.class));
    }
}
