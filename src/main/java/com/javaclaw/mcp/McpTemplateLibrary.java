package com.javaclaw.mcp;

import java.util.List;
import java.util.Map;

/**
 * MCP Server 预置模板库
 *
 * <p>为常见开源 MCP Server 提供"一键添加"体验。模板中 {@code {{xxx}}} 占位符
 * 会在用户提交时由表单值替换。</p>
 */
public final class McpTemplateLibrary {

    /**
     * 单个模板
     *
     * @param id            模板唯一标识
     * @param displayName   UI 展示名
     * @param description   一句话说明
     * @param command       启动命令
     * @param args          参数（可包含占位符 {@code {{name}}}）
     * @param envKeys       需要用户填写的环境变量名
     * @param placeholders  参数占位符的说明（key=占位符名，value=人类可读说明）
     * @param toolsHint     该模板提供的工具简述（用于模板对话框右侧预览，让用户知道选这个 server 后能做什么）
     */
    public record McpTemplate(
            String id,
            String displayName,
            String description,
            String command,
            List<String> args,
            List<String> envKeys,
            Map<String, String> placeholders,
            String toolsHint) {}

    public static final List<McpTemplate> ALL = List.of(
            new McpTemplate(
                    "filesystem",
                    "本地文件系统",
                    "访问本机指定目录下的文件（按授权目录控制读写）",
                    "npx",
                    List.of("-y", "@modelcontextprotocol/server-filesystem", "{{path}}"),
                    List.of(),
                    Map.of("path", "要授权访问的本地目录绝对路径"),
                    "read_file / write_file / list_directory / search_files / move_file 等"
            ),
            new McpTemplate(
                    "github",
                    "GitHub",
                    "读写 GitHub Issue、PR、仓库内容",
                    "npx",
                    List.of("-y", "@modelcontextprotocol/server-github"),
                    List.of("GITHUB_PERSONAL_ACCESS_TOKEN"),
                    Map.of(),
                    "create_issue / list_pull_requests / get_file_contents / search_repositories 等"
            ),
            new McpTemplate(
                    "postgres",
                    "PostgreSQL",
                    "以只读方式查询 PostgreSQL 数据库",
                    "npx",
                    List.of("-y", "@modelcontextprotocol/server-postgres", "{{connection_string}}"),
                    List.of(),
                    Map.of("connection_string", "PostgreSQL 连接串，如 postgresql://user:pwd@host:5432/db"),
                    "query（执行只读 SQL）"
            ),
            new McpTemplate(
                    "sqlite",
                    "SQLite",
                    "查询/操作本地 SQLite 数据库文件",
                    "uvx",
                    List.of("mcp-server-sqlite", "--db-path", "{{db_path}}"),
                    List.of(),
                    Map.of("db_path", "SQLite 数据库文件绝对路径"),
                    "read_query / write_query / list_tables / describe_table"
            ),
            new McpTemplate(
                    "fetch",
                    "网页抓取",
                    "抓取任意 URL 的内容（适合补充网页理解能力）",
                    "uvx",
                    List.of("mcp-server-fetch"),
                    List.of(),
                    Map.of(),
                    "fetch（按 URL 抓取并转 Markdown）"
            ),
            new McpTemplate(
                    "brave-search",
                    "Brave 搜索",
                    "通过 Brave Search API 进行网页/本地搜索",
                    "npx",
                    List.of("-y", "@modelcontextprotocol/server-brave-search"),
                    List.of("BRAVE_API_KEY"),
                    Map.of(),
                    "brave_web_search / brave_local_search"
            ),
            new McpTemplate(
                    "memory",
                    "知识图谱记忆",
                    "本地持久化的知识图谱：实体、关系、观察记录",
                    "npx",
                    List.of("-y", "@modelcontextprotocol/server-memory"),
                    List.of(),
                    Map.of(),
                    "create_entities / create_relations / add_observations / read_graph 等"
            ),
            new McpTemplate(
                    "time",
                    "时间/时区",
                    "时区换算与当前时间查询",
                    "uvx",
                    List.of("mcp-server-time"),
                    List.of(),
                    Map.of(),
                    "get_current_time / convert_time"
            ),
            new McpTemplate(
                    "sequential-thinking",
                    "顺序思考",
                    "结构化的多步思维链工具，适合复杂推理",
                    "npx",
                    List.of("-y", "@modelcontextprotocol/server-sequential-thinking"),
                    List.of(),
                    Map.of(),
                    "sequentialthinking（动态拆解推理步骤）"
            ),
            new McpTemplate(
                    "puppeteer",
                    "Puppeteer 浏览器",
                    "无头 Chrome 浏览器自动化（导航、截图、点击、表单）",
                    "npx",
                    List.of("-y", "@modelcontextprotocol/server-puppeteer"),
                    List.of(),
                    Map.of(),
                    "puppeteer_navigate / puppeteer_screenshot / puppeteer_click / puppeteer_fill 等"
            ),
            new McpTemplate(
                    "slack",
                    "Slack",
                    "读取/发送 Slack 频道消息",
                    "npx",
                    List.of("-y", "@modelcontextprotocol/server-slack"),
                    List.of("SLACK_BOT_TOKEN", "SLACK_TEAM_ID"),
                    Map.of(),
                    "slack_list_channels / slack_post_message / slack_reply_to_thread 等"
            ),
            new McpTemplate(
                    "git",
                    "Git 仓库",
                    "对本地 Git 仓库执行只读查询（log/diff/show 等）",
                    "uvx",
                    List.of("mcp-server-git", "--repository", "{{repo_path}}"),
                    List.of(),
                    Map.of("repo_path", "Git 仓库的绝对路径"),
                    "git_status / git_diff / git_log / git_show 等"
            )
    );

    private McpTemplateLibrary() {}

    public static McpTemplate byId(String id) {
        for (McpTemplate t : ALL) if (t.id().equals(id)) return t;
        return null;
    }
}
