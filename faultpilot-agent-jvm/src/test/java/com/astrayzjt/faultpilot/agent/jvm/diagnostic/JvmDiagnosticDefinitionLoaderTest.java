package com.astrayzjt.faultpilot.agent.jvm.diagnostic;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import static org.assertj.core.api.Assertions.assertThat;

class JvmDiagnosticDefinitionLoaderTest {

    @Test
    void loadsImmutableJvmSkillFrontMatterAndToolCatalog() {
        var resolver = new PathMatchingResourcePatternResolver(new DefaultResourceLoader());

        JvmDiagnosticCatalog catalog = new JvmDiagnosticDefinitionLoader(resolver).load();

        assertThat(catalog.skillSummaries()).extracting(SkillSummary::name)
                .containsExactly("jvm-cpu-hotspot", "jvm-thread-pool-exhausted");
        LoadedSkill cpu = catalog.requireSkill("jvm-cpu-hotspot");
        assertThat(catalog.toolSummaries(cpu)).extracting(ToolSummary::name)
                .containsExactly("query_prometheus_process_cpu", "query_arthas_hot_threads");
        assertThat(cpu.instructions())
                .contains("PROCESS_CPU_HIGH plus CPU_HOT_METHOD_FOUND")
                .doesNotContain("apiVersion:");
        assertThat(catalog.requireTool("query_arthas_waiting_threads").spec().riskLevel())
                .isEqualTo("READ_ONLY");
    }
}
