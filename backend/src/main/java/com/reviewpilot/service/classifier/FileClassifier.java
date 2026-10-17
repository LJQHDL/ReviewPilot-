package com.reviewpilot.service.classifier;

import com.reviewpilot.service.diff.FileChange;
import org.springframework.stereotype.Component;

/**
 * 用少量"路径 + 内容"启发式把 {@link FileChange} 归入 {@link FileType} 桶，供 Prompt 与规则选择使用。
 * 分类器刻意做糙：目标是驱动 Prompt 选择（PR#6）和规则选择（PR#5），而非做代码级分析；一切模棱两可都落入 {@link FileType#OTHER}。
 *
 * <p>判定顺序（先命中先生效）：
 * <ol>
 *   <li>测试源码——路径含 {@code /test/}、{@code /tests/}，或文件名以
 *       {@code Test.java} / {@code Tests.java} / {@code Spec.java} 结尾。
 *       最先检查，使 {@code FooControllerTest.java} 归为 TEST 而非 CONTROLLER。</li>
 *   <li>SQL——扩展名 {@code .sql}。</li>
 *   <li>Spring 配置文件——路径任意位置出现 {@code application*.yml} / {@code .yaml} / {@code .properties}。</li>
 *   <li>Java 源码——先在 patch 中扫描 Spring 注解（对新增/上下文行做廉价子串匹配）；
 *       当 patch 中识别不出注解时（如 PR 只改了既有 Service 类的方法体），
 *       回退到文件名后缀检查，让 {@code OrderService.java} 仍归为 SERVICE。</li>
 *   <li>其余一律 OTHER。</li>
 * </ol>
 *
 * <p>注解扫描刻意针对原始 patch（仍带 {@code +}/{@code -}/空格前缀）——
 * 子串匹配已足够可靠，还省去二次解析。
 */
@Component
public class FileClassifier {

    /** 按"测试 → SQL → 配置 → Java → OTHER"的优先级对单个变更文件分类。 */
    public FileType classify(FileChange change) {
        if (change == null || change.filename() == null || change.filename().isEmpty()) {
            return FileType.OTHER;
        }

        String path = change.filename();
        String lower = path.toLowerCase();

        if (isTestPath(lower)) {
            return FileType.TEST;
        }
        if (lower.endsWith(".sql")) {
            return FileType.SQL;
        }
        if (isSpringConfigFile(lower)) {
            return FileType.CONFIG;
        }
        if (lower.endsWith(".java")) {
            return classifyJava(path, change.patch());
        }
        return FileType.OTHER;
    }

    /** 测试文件判定：目录约定或文件名约定；先剥离目录名，避免以 test 结尾的目录造成误报。 */
    private static boolean isTestPath(String lowerPath) {
        if (lowerPath.contains("/test/") || lowerPath.contains("/tests/")) {
            return true;
        }
        // 文件名层面的约定：先去掉目录部分，防止目录名恰好以 test 结尾引发误判
        int slash = lowerPath.lastIndexOf('/');
        String filename = slash < 0 ? lowerPath : lowerPath.substring(slash + 1);
        return filename.endsWith("test.java")
                || filename.endsWith("tests.java")
                || filename.endsWith("spec.java");
    }

    /** Spring 配置文件判定：application*.yml/yaml/properties 与 bootstrap.*。 */
    private static boolean isSpringConfigFile(String lowerPath) {
        int slash = lowerPath.lastIndexOf('/');
        String filename = slash < 0 ? lowerPath : lowerPath.substring(slash + 1);
        if (filename.startsWith("application")
                && (filename.endsWith(".yml") || filename.endsWith(".yaml") || filename.endsWith(".properties"))) {
            return true;
        }
        return filename.equals("bootstrap.yml")
                || filename.equals("bootstrap.yaml")
                || filename.equals("bootstrap.properties");
    }

    /** Java 文件细分：先扫 patch 中的 Spring 注解，再按文件名后缀回退。 */
    private static FileType classifyJava(String path, String patch) {
        // 注解扫描优先——PR 新增或改动类头时可直接命中
        if (patch != null && !patch.isEmpty()) {
            if (patch.contains("@RestController") || patch.contains("@Controller")) {
                return FileType.CONTROLLER;
            }
            if (patch.contains("@Service")) {
                return FileType.SERVICE;
            }
            if (patch.contains("@ConfigurationProperties") || patch.contains("@Configuration")) {
                return FileType.CONFIG;
            }
        }
        // 文件名回退：只改既有 OrderService.java 方法体的 PR，patch 里没有 @Service，
        // 但文件角色仍是 SERVICE。缺了这层回退，下游 RiskDetector 和 PromptBuilder
        // 会把这类改动路由到 OTHER，漏掉角色专属的规则与 Prompt。
        int slash = path.lastIndexOf('/');
        String filename = slash < 0 ? path : path.substring(slash + 1);
        // 先剥掉 ".java" 后缀一次，让后面的 endsWith 判断无歧义
        String basename = filename.endsWith(".java")
                ? filename.substring(0, filename.length() - 5)
                : filename;
        if (basename.endsWith("Controller")) return FileType.CONTROLLER;
        if (basename.endsWith("ServiceImpl") || basename.endsWith("Service")) return FileType.SERVICE;
        if (basename.endsWith("Properties")
                || basename.endsWith("Configuration")
                || basename.endsWith("Config")) return FileType.CONFIG;
        return FileType.OTHER;
    }
}
