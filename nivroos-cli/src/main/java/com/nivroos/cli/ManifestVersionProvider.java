package com.nivroos.cli;

import picocli.CommandLine.IVersionProvider;

import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * 从打包 JAR 的 MANIFEST.MF 读取 {@code Implementation-Version}（Maven 构建时由
 * jar 插件写入，版本号单一来源为根 POM）。IDE 里直接运行时读不到 manifest，
 * 回退为 {@code dev}。
 */
public class ManifestVersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() {
        return new String[]{"NivroOS " + readVersion()};
    }

    /**
     * 在 fat JAR 中会枚举到多个 MANIFEST.MF，按 {@code Implementation-Title} 匹配
     * nivroos-cli 自己的 manifest。
     */
    String readVersion() {
        try {
            Enumeration<URL> resources = getClass().getClassLoader()
                    .getResources("META-INF/MANIFEST.MF");
            while (resources.hasMoreElements()) {
                Manifest manifest = new Manifest(resources.nextElement().openStream());
                Attributes attributes = manifest.getMainAttributes();
                if ("NivroOS CLI".equals(attributes.getValue("Implementation-Title"))) {
                    String version = attributes.getValue("Implementation-Version");
                    if (version != null) {
                        return version;
                    }
                }
            }
        } catch (IOException e) {
            // 读不到 manifest 时回退 dev 标识，不阻断 CLI
        }
        return "dev";
    }
}
