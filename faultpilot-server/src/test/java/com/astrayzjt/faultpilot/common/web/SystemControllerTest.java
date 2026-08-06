package com.astrayzjt.faultpilot.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SystemControllerTest {

    @Test
    void returnsDevelopmentVersionWhenBuildInfoIsUnavailable() {
        ObjectProvider<org.springframework.boot.info.BuildProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        Map<String, String> result = new SystemController(provider, "faultpilot-server").info();

        assertThat(result).containsEntry("application", "faultpilot-server");
        assertThat(result).containsEntry("version", "development");
    }
}

