package com.nivroos.tool.sandbox;

/**
 * 受控环境里执行的一个动作（宪法原则六：接口不携带实现细节）。
 *
 * <p>One action to be performed in the controlled environment; the interface deliberately carries
 * no implementation-specific vocabulary (whitelist, container, VM) so heavier isolation backends
 * can implement it unchanged.
 *
 * @param type 动作类型（文件读 / 文件写 / Shell 命令 / HTTP 请求）
 * @param target 目标（路径 / 命令 / URL）
 */
public record SandboxAction(ActionType type, String target) {

  public enum ActionType {
    FILE_READ,
    FILE_WRITE,
    SHELL_COMMAND,
    HTTP_REQUEST
  }
}
