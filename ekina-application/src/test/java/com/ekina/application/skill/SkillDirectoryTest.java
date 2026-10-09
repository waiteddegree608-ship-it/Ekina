package com.ekina.application.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SkillDirectoryTest {
    @TempDir Path workspace;

    @Test void loadsPortableSkillsWithoutTreatingCatalogAsInstructions() throws Exception {
        Path dir = Files.createDirectories(workspace.resolve("skills/paper-writing"));
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: paper-writing\ndescription: 学术写作\n---\n# 论文写作\n使用 `auditPaper` 核查论文。\n");
        Files.writeString(workspace.resolve("skills/README.md"), "# Catalog\n不是技能\n");
        Files.writeString(workspace.resolve("skills/legacy.md"), "# 旧技能\n论文\n");
        SkillService service = new SkillService();
        var root = SkillService.class.getDeclaredField("workspaceDir");
        root.setAccessible(true);
        root.set(service, workspace.toString());
        var enabled = SkillService.class.getDeclaredField("enabled");
        enabled.setAccessible(true);
        enabled.set(service, true);
        service.reload();
        assertEquals(2, service.list().size());
        assertTrue(service.list().stream().anyMatch(s -> s.getId().equals("paper.writing")));
        assertTrue(service.preferredTools("论文").contains("auditPaper"));
        assertFalse(service.buildPromptSection("论文").contains("不是技能"));
    }
}
