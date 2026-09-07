package com.javaclaw.server.toolchain;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.Environment;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainKind;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证格式错误、属性解析预算和不同工具声明的诊断归属。 */
class ProjectDeclarationFormatTest {
    private final Environment defaults =
            new Environment(0, CodingToolchainCatalog.bundled().defaultEnvironment());

    @Test
    void Maven源级别和目标级别允许较新JDK且其他插件同名字段不构成约束() {
        String pom = """
            <project><properties>
              <maven.compiler.source>1.8</maven.compiler.source>
              <maven.compiler.target>${bytecode.level}</maven.compiler.target>
              <bytecode.level>17</bytecode.level>
            </properties><build><plugins>
              <plugin><artifactId>other-plugin</artifactId><configuration><release>99</release></configuration></plugin>
              <plugin>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration><source>17</source><target>17</target></configuration>
              </plugin>
            </plugins></build></project>
            """;
        var result = select("pom.xml", pom);
        result.requireCompatible(List.of(ToolchainKind.JDK, ToolchainKind.MAVEN));
        assertTrue(result.conflicts().isEmpty());
    }

    @Test
    void Maven缺失重复非法和过深属性不自动采用任意值() {
        for (String properties : List.of(
                "<maven.compiler.release>${missing}</maven.compiler.release>",
                "<maven.compiler.release>${level}</maven.compiler.release><level>17</level><level>21</level>",
                "<maven.compiler.release>${bad name}</maven.compiler.release>",
                "<maven.compiler.release>17+offset</maven.compiler.release>",
                deepProperties())) {
            var result = select("pom.xml", "<project><properties>" + properties + "</properties></project>");
            assertThrows(IllegalStateException.class, () -> result.requireCompatible(List.of(ToolchainKind.JDK)));
            result.requireCompatible(List.of(ToolchainKind.NODE));
        }
    }

    @Test
    void JSON必须有对象形状和文本约束且npm声明不禁止pnpm() {
        for (String manifest : List.of("[]", "{\"engines\":{\"node\":22}}")) {
            var result = select("package.json", manifest);
            assertThrows(IllegalStateException.class, () -> result.requireCompatible(List.of(ToolchainKind.NODE)));
            result.requireCompatible(List.of(ToolchainKind.JDK));
        }
        var npm = select("package.json", "{\"packageManager\":\"npm@10.9.3\"}");
        npm.requireCompatible(List.of(ToolchainKind.NPM, ToolchainKind.PNPM));
        select("package.json", "{\"name\":\"project\"}").requireCompatible(List.of(ToolchainKind.NODE));
    }

    @Test
    void Python兼容发布和排除通配使用标准语义且Poetry单字段有效() {
        select("pyproject.toml", "[project]\nrequires-python = \"~=3.12.1,!=3.11.*\"")
                .requireCompatible(List.of(ToolchainKind.PYTHON));
        select("pyproject.toml", "[tool.poetry.dependencies]\npython = \"^3.12\"")
                .requireCompatible(List.of(ToolchainKind.PYTHON));
        var malformed = select("pyproject.toml", "[project\nrequires-python = \"3.12\"");
        assertThrows(IllegalStateException.class, () -> malformed.requireCompatible(List.of(ToolchainKind.PYTHON)));
        malformed.requireCompatible(List.of(ToolchainKind.JDK));
    }

    @Test
    void 单语言版本文件和动态Wrapper错误只拒绝所属工具() {
        for (var invalid : Map.of(
                        ".java-version", ToolchainKind.JDK,
                        ".python-version", ToolchainKind.PYTHON,
                        "gradle/wrapper/gradle-wrapper.properties", ToolchainKind.GRADLE)
                .entrySet()) {
            var result = select(invalid.getKey(), "latest");
            assertThrows(IllegalStateException.class, () -> result.requireCompatible(List.of(invalid.getValue())));
            result.requireCompatible(List.of(ToolchainKind.NODE));
        }
    }

    private CodingEnvironmentSelection select(String path, String content) {
        return ProjectSelectionFixture.select(Map.of(path, content), defaults);
    }

    private static String deepProperties() {
        var result = new StringBuilder("<maven.compiler.release>${p0}</maven.compiler.release>");
        for (int index = 0; index < 9; index++) {
            result.append("<p")
                    .append(index)
                    .append(">${p")
                    .append(index + 1)
                    .append("}</p")
                    .append(index)
                    .append(">");
        }
        return result.append("<p9>21</p9>").toString();
    }
}
