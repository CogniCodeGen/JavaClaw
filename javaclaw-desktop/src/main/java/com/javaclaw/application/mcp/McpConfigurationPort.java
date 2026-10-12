package com.javaclaw.application.mcp;

import java.util.List;
import java.util.Map;

/** 工作区 MCP 配置持久化端口；实现必须在提交成功后才更新可见快照。 */
public interface McpConfigurationPort {

    List<Entry> list();

    void save(Entry entry);

    void saveAll(List<Entry> entries);

    boolean delete(String name);

    String storageDescription();

    record Entry(
            String name,
            McpManagementApplicationService.Transport transport,
            String command,
            List<String> arguments,
            Map<String, String> environment,
            String url,
            Map<String, String> headers,
            boolean enabled) {
        public Entry {
            name = name == null ? "" : name;
            transport = transport == null
                    ? McpManagementApplicationService.Transport.STDIO : transport;
            command = command == null ? "" : command;
            arguments = List.copyOf(arguments == null ? List.of() : arguments);
            environment = Map.copyOf(environment == null ? Map.of() : environment);
            url = url == null ? "" : url;
            headers = Map.copyOf(headers == null ? Map.of() : headers);
        }
    }
}
