package com.astrayzjt.faultpilot.tool.declarative.execution;

import java.net.URI;

public record DiagnosticEndpoint(String ref, URI baseUri, String authorization) {
}
