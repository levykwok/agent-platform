/*
 * Copyright 2026 by the Agent Platform contributors.
 */
package io.agent.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 主业务模块入口。
 * 统一放在这里，核心代码与 Agentscope 框架/示例解耦。
 */
@SpringBootApplication
@EnableScheduling
public class PlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
