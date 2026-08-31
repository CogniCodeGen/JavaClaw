package com.javaclaw.sdk;

import com.javaclaw.sdk.model.EventInfo;
import com.javaclaw.sdk.model.ItemInfo;
import com.javaclaw.sdk.model.TurnInfo;

/**
 * 持久事件的实时通知；断线或背压后可按 sequence 重放。
 *
 * @param event 持久事件信封
 * @param item 事件关联的 Item 投影；非 Item 事件可为 null
 * @param turn 事件关联的 Turn 投影；无 Turn 更新时可为 null
 */
public record EventNotification(EventInfo event, ItemInfo item, TurnInfo turn) implements ClientNotification {}
