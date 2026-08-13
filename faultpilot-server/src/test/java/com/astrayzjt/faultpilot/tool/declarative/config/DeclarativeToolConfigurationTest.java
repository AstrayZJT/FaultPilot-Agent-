package com.astrayzjt.faultpilot.tool.declarative.config;

import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.tool.declarative.catalog.SkillCatalog;
import com.astrayzjt.faultpilot.tool.declarative.catalog.ToolCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class DeclarativeToolConfigurationTest {

    @Test
    void createsCatalogsOnceDuringSpringStartup() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                DeclarativeToolConfiguration.class)) {
            ToolCatalog tools = context.getBean(ToolCatalog.class);
            SkillCatalog skills = context.getBean(SkillCatalog.class);

            assertThat(tools.summaries(AgentType.JVM_AGENT)).hasSize(2);
            assertThat(skills.summaries(AgentType.JVM_AGENT)).hasSize(1);
            assertThat(context.getBean(ToolCatalog.class)).isSameAs(tools);
            assertThat(context.getBean(SkillCatalog.class)).isSameAs(skills);
        }
    }
}
