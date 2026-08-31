package com.nivroos.core.model;

/**
 * JSON Schema 字符串的值包装。
 *
 * <p>Value wrapper for a JSON Schema string; keeps core free of any framework-specific schema type.
 *
 * @param value JSON Schema 内容
 */
public record JsonSchema(String value) {}
