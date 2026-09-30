package com.nivroos.core.notify;

/**
 * 通知渠道（技术方案 §6.8：名称 / 类型 / 地址 / 说明）。
 *
 * <p>A global notification channel record: {@code name} is the registry key the model passes to the
 * {@code notify} tool, {@code type} selects the adapter (core phase: only {@code webhook}), {@code
 * url} is the dispatch target. The url is itself a credential - it stays in the registry and never
 * enters conversation history, the model only ever names the channel.
 */
public record NotifyChannel(String name, String type, String url, String description) {}
