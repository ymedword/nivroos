package com.nivroos.core.model;

/**
 * token 用量（字段可空：厂商未返回或调用失败时）。
 *
 * <p>Token usage; fields are nullable when the provider omits usage or the call failed.
 *
 * @param promptTokens 输入 token 数
 * @param completionTokens 输出 token 数
 * @param totalTokens 总 token 数
 */
public record Usage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {}
