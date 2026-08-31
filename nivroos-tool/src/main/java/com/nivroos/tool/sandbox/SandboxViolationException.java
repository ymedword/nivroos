package com.nivroos.tool.sandbox;

/**
 * 沙箱校验失败异常。
 *
 * <p>Thrown when an action violates the sandbox rules; the message carries the reason (e.g. the
 * rejected domain) and flows into the tool audit record as success=false + error_message via the
 * ToolExecutor failure path.
 */
public class SandboxViolationException extends RuntimeException {

  public SandboxViolationException(String message) {
    super("Sandbox violation: " + message);
  }
}
