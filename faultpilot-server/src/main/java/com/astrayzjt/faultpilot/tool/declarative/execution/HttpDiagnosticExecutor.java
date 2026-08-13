package com.astrayzjt.faultpilot.tool.declarative.execution;

import com.astrayzjt.faultpilot.tool.declarative.model.ToolDefinition;
import com.astrayzjt.faultpilot.tool.registry.ToolExecutionContext;

import java.util.Map;

public final class HttpDiagnosticExecutor implements DiagnosticToolExecutor {

    private final EndpointCatalog endpoints;
    private final DiagnosticRequestBuilder requestBuilder;
    private final DiagnosticHttpClient httpClient;
    private final DiagnosticResponseMapper responseMapper;

    public HttpDiagnosticExecutor(EndpointCatalog endpoints,
                                  DiagnosticRequestBuilder requestBuilder,
                                  DiagnosticHttpClient httpClient,
                                  DiagnosticResponseMapper responseMapper) {
        this.endpoints = endpoints;
        this.requestBuilder = requestBuilder;
        this.httpClient = httpClient;
        this.responseMapper = responseMapper;
    }

    @Override
    public ToolExecutionResult execute(ToolDefinition definition, ToolExecutionContext context,
                                       Map<String, Object> arguments) {
        context.throwIfExpired();
        DiagnosticEndpoint endpoint = endpoints.require(definition.spec().endpoint().ref());
        DiagnosticHttpRequest request = requestBuilder.build(definition, endpoint, context, arguments);
        try {
            DiagnosticHttpResponse response = httpClient.execute(request);
            return responseMapper.map(definition, response, context);
        } catch (RuntimeException exception) {
            return ToolExecutionResult.failure(
                    definition.spec().endpoint().ref() + ":" + context.serviceName(),
                    "Diagnostic HTTP endpoint is unavailable");
        }
    }
}
