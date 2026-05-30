package com.reviewpilot.service.classifier;

import com.reviewpilot.service.diff.FileChange;
import org.springframework.stereotype.Component;

/**
 * Classifies a {@link FileChange} into a {@link FileType} bucket using a small
 * set of path + content heuristics. The classifier is intentionally rough: it
 * exists to drive prompt selection (PR#6) and rule selection (PR#5), not to be
 * a code-aware analyzer. Anything ambiguous falls back to {@link FileType#OTHER}.
 *
 * <p>Decision order (first match wins):
 * <ol>
 *   <li>Test sources — path contains {@code /test/}, {@code /tests/}, or filename
 *       ends with {@code Test.java} / {@code Tests.java} / {@code Spec.java}.
 *       Checked first so that {@code FooControllerTest.java} is classified as
 *       TEST rather than CONTROLLER.</li>
 *   <li>SQL — extension {@code .sql}.</li>
 *   <li>Spring config files — {@code application*.yml} / {@code .yaml} /
 *       {@code .properties} anywhere in the path.</li>
 *   <li>Java sources — scan the patch for Spring stereotypes (cheap substring
 *       match against added/context lines). {@code @RestController} or
 *       {@code @Controller} → CONTROLLER; {@code @Service} → SERVICE;
 *       {@code @Configuration} or {@code @ConfigurationProperties} → CONFIG.</li>
 *   <li>Anything else → OTHER.</li>
 * </ol>
 *
 * <p>The annotation scan deliberately runs against the raw patch (which still
 * carries {@code +}/{@code -}/space prefixes) — a substring check is robust
 * enough for our purposes and avoids re-parsing.
 */
@Component
public class FileClassifier {

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
            return classifyJava(change.patch());
        }
        return FileType.OTHER;
    }

    private static boolean isTestPath(String lowerPath) {
        if (lowerPath.contains("/test/") || lowerPath.contains("/tests/")) {
            return true;
        }
        // Filename-level convention. Strip directory first to avoid
        // false positives from directory names that happen to end in "test".
        int slash = lowerPath.lastIndexOf('/');
        String filename = slash < 0 ? lowerPath : lowerPath.substring(slash + 1);
        return filename.endsWith("test.java")
                || filename.endsWith("tests.java")
                || filename.endsWith("spec.java");
    }

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

    private static FileType classifyJava(String patch) {
        if (patch == null || patch.isEmpty()) {
            return FileType.OTHER;
        }
        if (patch.contains("@RestController") || patch.contains("@Controller")) {
            return FileType.CONTROLLER;
        }
        if (patch.contains("@Service")) {
            return FileType.SERVICE;
        }
        if (patch.contains("@ConfigurationProperties") || patch.contains("@Configuration")) {
            return FileType.CONFIG;
        }
        return FileType.OTHER;
    }
}
