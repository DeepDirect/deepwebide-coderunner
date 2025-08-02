package com.sandbox.sandbox_server.dto;

import lombok.Getter;

@Getter
public class SandboxRunRequest {
    private String uuid;
    private String url;
    private String framework;
    private int port;
}
