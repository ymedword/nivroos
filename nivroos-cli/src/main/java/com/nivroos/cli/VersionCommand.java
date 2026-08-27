package com.nivroos.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

/** {@code nivroos version} — 打印 NivroOS 版本信息 */
@Command(name = "version", description = "打印 NivroOS 版本信息")
public class VersionCommand implements Callable<Integer> {

  @Override
  public Integer call() {
    System.out.println("NivroOS " + new ManifestVersionProvider().readVersion());
    return 0;
  }
}
