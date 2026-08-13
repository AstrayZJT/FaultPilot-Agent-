package com.astrayzjt.faultpilot.tool.declarative.loader;

import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import com.astrayzjt.faultpilot.tool.declarative.config.DeclarativeToolProperties;
import com.astrayzjt.faultpilot.tool.declarative.validation.DiagnosticDefinitionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiagnosticDefinitionLoaderTest {

    @TempDir
    Path tempDirectory;

    @Test
    void loadsValidatedImmutableJvmCatalogFromClasspath() {
        DiagnosticCatalogBundle bundle = loader().load(new DeclarativeToolProperties());

        assertThat(bundle.tools().summaries(AgentType.JVM_AGENT))
                .extracting("name")
                .containsExactly("query_arthas_hot_threads", "query_prometheus_process_cpu");
        assertThat(bundle.skills().summaries(AgentType.JVM_AGENT))
                .extracting("name")
                .containsExactly("jvm-cpu-hotspot");
        assertThat(bundle.skills().require("jvm-cpu-hotspot").instructions())
                .contains("## Investigation order");
        assertThat(bundle.tools().require("query_prometheus_process_cpu")
                .spec().response().evidence().trueType()).isEqualTo(EvidenceType.PROCESS_CPU_HIGH);

        assertThatThrownBy(() -> bundle.tools().summaries(AgentType.JVM_AGENT)
                .add(bundle.tools().summaries(AgentType.JVM_AGENT).getFirst()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> bundle.tools().require("query_prometheus_process_cpu")
                .spec().input().properties().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsUnknownYamlFieldsInsteadOfIgnoringThem() throws Exception {
        TestLayout layout = validLayout();
        Files.writeString(layout.tool(), validToolYaml().replace(
                "  riskLevel: READ_ONLY", "  arbitraryUrl: https://attacker.example\n  riskLevel: READ_ONLY"));

        assertThatThrownBy(() -> loader().load(properties(layout)))
                .isInstanceOf(DiagnosticDefinitionException.class)
                .hasMessageContaining("arbitraryUrl");
    }

    @Test
    void rejectsFullUrlsAndCrossAgentSkillTools() throws Exception {
        TestLayout layout = validLayout();
        Files.writeString(layout.tool(), validToolYaml().replace(
                "path: /api/v1/query", "path: https://attacker.example/query"));

        assertThatThrownBy(() -> loader().load(properties(layout)))
                .isInstanceOf(DiagnosticDefinitionException.class)
                .hasMessageContaining("Endpoint path");

        Files.writeString(layout.tool(), validToolYaml().replace(
                "ownerAgent: JVM_AGENT", "ownerAgent: DATABASE_AGENT"));
        assertThatThrownBy(() -> loader().load(properties(layout)))
                .isInstanceOf(DiagnosticDefinitionException.class)
                .hasMessageContaining("another agent");
    }

    @Test
    void rejectsDuplicateDefinitionsAndMissingSkillInstructions() throws Exception {
        TestLayout layout = validLayout();
        Files.copy(layout.tool(), layout.tool().resolveSibling("duplicate.yaml"));

        assertThatThrownBy(() -> loader().load(properties(layout)))
                .isInstanceOf(DiagnosticDefinitionException.class)
                .hasMessageContaining("Duplicate tool");

        Files.delete(layout.tool().resolveSibling("duplicate.yaml"));
        Files.delete(layout.instructions());
        assertThatThrownBy(() -> loader().load(properties(layout)))
                .isInstanceOf(DiagnosticDefinitionException.class)
                .hasMessageContaining("Missing SKILL.md");
    }

    private TestLayout validLayout() throws Exception {
        Path tools = Files.createDirectories(tempDirectory.resolve("tools"));
        Path skill = Files.createDirectories(tempDirectory.resolve("skills/jvm-cpu"));
        Path tool = tools.resolve("cpu.yaml");
        Path skillYaml = skill.resolve("skill.yaml");
        Path instructions = skill.resolve("SKILL.md");
        Files.writeString(tool, validToolYaml());
        Files.writeString(skillYaml, validSkillYaml());
        Files.writeString(instructions, "# JVM CPU\n\n## Goal\n\nConfirm the CPU signal with bounded tools.\n");
        return new TestLayout(tool, skillYaml, instructions);
    }

    private DeclarativeToolProperties properties(TestLayout layout) {
        DeclarativeToolProperties properties = new DeclarativeToolProperties();
        properties.setToolLocations(List.of(filePattern(layout.tool().getParent(), "*.yaml")));
        properties.setSkillLocations(List.of(filePattern(layout.skill().getParent().getParent(), "*/skill.yaml")));
        properties.setAllowedEndpointRefs(Set.of("prometheus"));
        return properties;
    }

    private String filePattern(Path directory, String suffix) {
        return directory.toUri() + suffix;
    }

    private DiagnosticDefinitionLoader loader() {
        return new DiagnosticDefinitionLoader(new PathMatchingResourcePatternResolver());
    }

    private String validToolYaml() {
        return """
                apiVersion: faultpilot/v1
                kind: DiagnosticTool
                metadata:
                  name: query_cpu
                  version: 1.0.0
                spec:
                  ownerAgent: JVM_AGENT
                  description: Query bounded process CPU.
                  endpoint:
                    ref: prometheus
                    path: /api/v1/query
                    method: GET
                  input:
                    type: object
                    required: [serviceName]
                    properties:
                      serviceName:
                        type: string
                        source: task.serviceName
                        maxLength: 128
                  request:
                    prometheus:
                      metric: process_cpu_usage
                      selectorFrom: service.prometheusLabels
                      extraMatchers: []
                  response:
                    resultPath: data.result
                    valuePath: value[1]
                    aggregation: MAX
                    emptyStatus: DATA_UNAVAILABLE
                    evidence:
                      rule: THRESHOLD
                      thresholdFrom: observability.processCpuHighThreshold
                      trueType: PROCESS_CPU_HIGH
                      falseType: PROCESS_CPU_NORMAL
                      trueSummary: CPU is high
                      falseSummary: CPU is normal
                  limits:
                    timeoutSeconds: 5
                    maxResponseBytes: 65536
                    maxItems: 20
                  riskLevel: READ_ONLY
                """;
    }

    private String validSkillYaml() {
        return """
                apiVersion: faultpilot/v1
                kind: DiagnosticSkill
                metadata:
                  name: jvm-cpu
                  version: 1.0.0
                spec:
                  ownerAgent: JVM_AGENT
                  description: Diagnose JVM CPU pressure.
                  triggerEvidenceTypes: [PROCESS_CPU_HIGH]
                  producesEvidenceTypes: [PROCESS_CPU_HIGH]
                  allowedTools: [query_cpu]
                  completion:
                    allOf: [PROCESS_CPU_HIGH]
                    anyOf: []
                  limits:
                    maxSteps: 2
                    timeoutSeconds: 30
                  riskLevel: READ_ONLY
                """;
    }

    private record TestLayout(Path tool, Path skill, Path instructions) {
    }
}
