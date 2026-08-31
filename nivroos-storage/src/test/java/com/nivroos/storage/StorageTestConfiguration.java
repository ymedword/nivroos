package com.nivroos.storage;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * storage 模块测试切片的应用配置锚点。
 *
 * <p>@DataJpaTest 以本类所在包（com.nivroos.storage）为基准扫描实体与仓储， 与 boot 模块的 @SpringBootApplication 扫描范围一致。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class StorageTestConfiguration {}
