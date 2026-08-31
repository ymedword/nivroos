package com.nivroos.core.model;

/**
 * 对话消息（内部统一表示，与具体 LLM 协议无关）。
 *
 * <p>对话消息的统一表示；role 取值 system / user / assistant / tool， 与供应商协议的消息格式解耦。
 *
 * @param role 消息角色（system / user / assistant / tool）
 * @param content 消息内容
 */
public record Message(String role, String content) {}
