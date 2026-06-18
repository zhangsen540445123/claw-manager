package com.clawbotforall.runtime;

import java.nio.file.Path;

/**
 * 单个 OpenClaw 实例拥有的文件系统路径。
 */
public record InstancePaths(
    Path baseDir,
    Path homeDir,
    Path workspaceDir,
    Path logsDir
) {}
