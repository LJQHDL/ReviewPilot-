package com.reviewpilot.architecture;

import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Enforces dependency direction against compiled production bytecode, not source wording. */
class LayerBoundariesTest {
    @Test
    void lower_layers_do_not_call_web_or_application_layers_and_pipeline_has_no_json_or_http() throws Exception {
        List<String> violations = new ArrayList<>();
        try (var classes = Files.walk(Path.of("target/classes/com/reviewpilot"))) {
            for (Path file : classes.filter(p -> p.toString().endsWith(".class")).toList()) {
                ClassReader reader = new ClassReader(Files.readAllBytes(file));
                String source = reader.getClassName();
                reader.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String method,
                                                        String desc, boolean isInterface) {
                                if (forbidden(source, owner)) violations.add(source + " -> " + owner);
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    private static boolean forbidden(String source, String target) {
        String prefix = "com/reviewpilot/";
        if (source.startsWith(prefix + "model/")) {
            return target.startsWith(prefix + "service/") || target.startsWith(prefix + "pipeline/")
                    || target.startsWith(prefix + "controller/");
        }
        if (source.startsWith(prefix + "service/")) {
            return target.startsWith(prefix + "controller/") || target.startsWith(prefix + "pipeline/");
        }
        if (source.startsWith(prefix + "pipeline/")) {
            return target.startsWith(prefix + "controller/")
                    || target.startsWith("com/fasterxml/jackson/")
                    || target.startsWith("org/springframework/web/");
        }
        return false;
    }
}
