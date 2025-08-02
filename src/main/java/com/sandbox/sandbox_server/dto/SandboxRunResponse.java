package com.sandbox.sandbox_server.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SandboxRunResponse {
    private String message;
    private String result;
    private String executionId;
    private String status;
    private String output;
    private String error;
    private Long executionTime;
}