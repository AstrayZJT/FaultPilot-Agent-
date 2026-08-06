package com.astrayzjt.faultpilot.evaluation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationModuleTest {

    @Test
    void moduleIsReadyForEvaluationCases() {
        assertThat("faultpilot-evaluation").isNotBlank();
    }
}

