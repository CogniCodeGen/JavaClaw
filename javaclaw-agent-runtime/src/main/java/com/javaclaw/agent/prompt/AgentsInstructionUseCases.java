package com.javaclaw.agent.prompt;

import java.nio.file.Path;
import java.util.Set;

/** 只读 AGENTS.md 解析边界；文件系统是唯一权威来源，不提供导入、保存或历史接口。 */
@FunctionalInterface
public interface AgentsInstructionUseCases {
    /** 按实际工作目录和允许读取根解析当前链；返回值包含正文，协议适配器必须只投影元数据。 */
    AgentsInstructionResolution inspect(Path workingDirectory, Set<Path> readableRoots);
}
