package com.nivroos.core.model;

/**
 * 模型请求的工具调用（透传产物，不执行）。
 *
 * <p>A tool call requested by the model; passed through to the caller, execution belongs to
 * ToolExecutor (US-2).
 *
 * @param name 工具名称
 * @param arguments 调用参数（JSON 字符串）
 */
public record ToolCallRequest(String name, String arguments) {}
