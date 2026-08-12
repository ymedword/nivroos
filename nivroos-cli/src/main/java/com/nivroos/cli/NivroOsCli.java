package com.nivroos.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.Set;

/**
 * NivroOS CLI 主入口（Picocli）。
 *
 * <p>轻命令（{@code version} 等）直接执行，不启动 Spring 上下文、启动快；
 * 需要 Spring 上下文的命令（{@code chat} / {@code serve} / {@code gateway}）由
 * {@code NivroOsApplication.main} 分发：命中已注册命令走 CLI，否则启动服务。
 * 骨架期仅实现 {@code version}，其余命令随 user story 逐个补齐。
 */
@Command(
        name = "nivroos",
        description = "NivroOS — 企业级 Agent OS",
        mixinStandardHelpOptions = true,
        versionProvider = ManifestVersionProvider.class,
        subcommands = {VersionCommand.class}
)
public class NivroOsCli implements Runnable {

    /** 已注册的子命令名（不含 help——Picocli 自动提供），用于启动分发判断 */
    private static final Set<String> REGISTERED_COMMANDS = Set.of("version");

    private static final CommandLine LINE = new CommandLine(new NivroOsCli());

    /** 执行 CLI 命令，返回进程退出码 */
    public static int run(String[] args) {
        return LINE.execute(args);
    }

    /** 启动分发判断：首参数命中 CLI 命令（含 Picocli 标准 help/version 选项）则走 CLI */
    public static boolean isCliCommand(String firstArg) {
        return REGISTERED_COMMANDS.contains(firstArg)
                || firstArg.equals("-h") || firstArg.equals("--help")
                || firstArg.equals("-V") || firstArg.equals("--version");
    }

    @Override
    public void run() {
        LINE.usage(System.out);
    }
}
