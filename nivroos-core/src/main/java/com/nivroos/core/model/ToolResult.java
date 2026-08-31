package com.nivroos.core.model;

/**
 * 工具执行结果。
 *
 * <p>Execution result of a tool; used by ToolExecutor (US-2), defined here as the return type of
 * {@link NivroTool}.
 *
 * @param success 是否成功
 * @param content 结果内容
 * @param errorMessage 错误信息（成功时为 null）
 * @param retryable 是否可重试
 */
public record ToolResult(boolean success, String content, String errorMessage, boolean retryable) {}
