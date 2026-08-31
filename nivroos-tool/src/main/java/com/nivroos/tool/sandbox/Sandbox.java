package com.nivroos.tool.sandbox;

/**
 * 沙箱接口（宪法原则六：接口先行，扩展阶段只新增实现类）。
 *
 * <p>Expresses the intent "execute an action in a controlled environment" only. Tools call {@link
 * #enforce} at the start of their own execute methods.
 */
public interface Sandbox {

  /**
   * 校验一个动作是否被允许；校验失败抛 {@link SandboxViolationException}。
   *
   * @param action 待校验的动作
   */
  void enforce(SandboxAction action);
}
