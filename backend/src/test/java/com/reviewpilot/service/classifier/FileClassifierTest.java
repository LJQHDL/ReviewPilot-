package com.reviewpilot.service.classifier;

import com.reviewpilot.service.diff.FileChange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileClassifierTest {

    private final FileClassifier classifier = new FileClassifier();

    @Test
    void null_or_empty_filename_falls_back_to_other() {
        assertEquals(FileType.OTHER, classifier.classify(null));
        assertEquals(FileType.OTHER, classifier.classify(change("", null)));
    }

    @Test
    void test_path_wins_over_controller_annotation() {
        // A test file *for* a controller still classifies as TEST — we route by
        // what the file is, not what it covers.
        String patch = "@@ -0,0 +1,3 @@\n+@RestController\n+public class FooControllerTest {\n+}\n";
        FileChange c = change("backend/src/test/java/com/example/FooControllerTest.java", patch);
        assertEquals(FileType.TEST, classifier.classify(c));
    }

    @Test
    void test_filename_suffix_recognised_outside_test_directory() {
        // Some projects put tests next to sources. Filename convention catches it.
        FileChange spec = change("src/main/java/com/example/UserSpec.java", null);
        FileChange tests = change("src/main/java/com/example/UserTests.java", null);
        assertEquals(FileType.TEST, classifier.classify(spec));
        assertEquals(FileType.TEST, classifier.classify(tests));
    }

    @Test
    void sql_files_classified_by_extension() {
        assertEquals(FileType.SQL, classifier.classify(change("db/migrations/V1__init.sql", null)));
        assertEquals(FileType.SQL, classifier.classify(change("schema.SQL", null)));
    }

    @Test
    void spring_yaml_and_properties_classified_as_config() {
        assertEquals(FileType.CONFIG,
                classifier.classify(change("backend/src/main/resources/application.yml", null)));
        assertEquals(FileType.CONFIG,
                classifier.classify(change("backend/src/main/resources/application-prod.properties", null)));
        assertEquals(FileType.CONFIG,
                classifier.classify(change("backend/src/main/resources/bootstrap.yaml", null)));
    }

    @Test
    void java_with_rest_controller_annotation_classified_as_controller() {
        String patch = """
                @@ -0,0 +1,5 @@
                +@RestController
                +@RequestMapping("/api/foo")
                +public class FooController {
                +    // ...
                +}""";
        assertEquals(FileType.CONTROLLER,
                classifier.classify(change("backend/src/main/java/com/example/FooController.java", patch)));
    }

    @Test
    void java_with_service_annotation_classified_as_service() {
        String patch = "@@ -0,0 +1,2 @@\n+@Service\n+public class FooService {}\n";
        assertEquals(FileType.SERVICE,
                classifier.classify(change("backend/src/main/java/com/example/FooService.java", patch)));
    }

    @Test
    void java_with_configuration_properties_classified_as_config() {
        String patch = "@@ -0,0 +1,2 @@\n+@ConfigurationProperties(\"foo\")\n+public class FooProps {}\n";
        assertEquals(FileType.CONFIG,
                classifier.classify(change("backend/src/main/java/com/example/config/FooProps.java", patch)));
    }

    @Test
    void java_without_recognised_annotation_falls_back_to_other() {
        // A plain DTO / record / utility doesn't carry a stereotype we route on,
        // and its filename has no role-suffix either.
        String patch = "@@ -0,0 +1,1 @@\n+public record Foo(String bar) {}\n";
        assertEquals(FileType.OTHER,
                classifier.classify(change("backend/src/main/java/com/example/Foo.java", patch)));
    }

    @Test
    void java_with_null_patch_falls_back_to_other() {
        // Renames or binary-ish entries arrive without a patch body, and the
        // filename here has no role-suffix.
        assertEquals(FileType.OTHER,
                classifier.classify(change("backend/src/main/java/com/example/Foo.java", null)));
    }

    @Test
    void java_with_method_only_patch_uses_filename_suffix_for_routing() {
        // A PR that only edits a method body of an existing OrderService.java
        // won't have @Service in its patch — the class header isn't in the diff.
        // Without the filename fallback this routes to OTHER, breaking role-
        // specific rules (RiskDetector) and prompts (PromptBuilder).
        String patch = """
                @@ -42,3 +42,4 @@
                 public void placeOrder(Order o) {
                +    log.info("placing");
                     repo.save(o);
                 }""";
        assertEquals(FileType.SERVICE,
                classifier.classify(change("backend/src/main/java/com/example/OrderService.java", patch)));
    }

    @Test
    void java_filename_suffix_routes_controller_service_impl_and_config() {
        // No annotation in the patch — fallback alone must classify each correctly.
        String trivial = "@@ -1 +1 @@\n-x\n+y\n";
        assertEquals(FileType.CONTROLLER, classifier.classify(
                change("src/main/java/foo/UserController.java", trivial)));
        assertEquals(FileType.SERVICE, classifier.classify(
                change("src/main/java/foo/UserServiceImpl.java", trivial)));
        assertEquals(FileType.CONFIG, classifier.classify(
                change("src/main/java/foo/AppProperties.java", trivial)));
        assertEquals(FileType.CONFIG, classifier.classify(
                change("src/main/java/foo/SecurityConfig.java", trivial)));
    }

    @Test
    void annotation_scan_wins_over_filename_when_patch_disagrees() {
        // Defensive: if the patch contains @RestController but the filename is
        // generic (e.g. Foo.java), trust the annotation. Annotation scan runs first.
        String patch = "@@ -0,0 +1,2 @@\n+@RestController\n+public class Foo {}\n";
        assertEquals(FileType.CONTROLLER,
                classifier.classify(change("src/main/java/foo/Foo.java", patch)));
    }

    @Test
    void frontend_and_doc_files_are_other() {
        // Frontend, markdown, build files all share the OTHER bucket — the generic
        // prompt is good enough for them and we don't want false-positive routing.
        assertEquals(FileType.OTHER, classifier.classify(change("frontend/src/App.vue", null)));
        assertEquals(FileType.OTHER, classifier.classify(change("README.md", null)));
        assertEquals(FileType.OTHER, classifier.classify(change("backend/pom.xml", null)));
    }

    private static FileChange change(String filename, String patch) {
        return new FileChange(filename, "modified", 0, 0, false, patch, List.of());
    }
}
