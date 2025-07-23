package com.sandbox.sandbox_server.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
public class SandboxService {
    public String saveAndExtractProject(String framework, MultipartFile projectZip) throws IOException {
        // 1. UUID로 하위폴더 생성
        String uuid = UUID.randomUUID().toString();
        Path projectDir = Paths.get("uploads", uuid);
        Files.createDirectories(projectDir);

        // 2. zip파일 저장
        Path zipPath = projectDir.resolve("project.zip");
        try (InputStream in = projectZip.getInputStream()) {
            Files.copy(in, zipPath, StandardCopyOption.REPLACE_EXISTING);
        }

        // 3. 압축 해제
        unzip(zipPath.toFile(), projectDir.toFile());

        return uuid;
    }

    private void unzip(File zipFile, File destDir) throws IOException {
        // 리눅스/맥 환경에서는 명령어로 빠르게 (java.util.zip로 해도 무방)
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "unzip", zipFile.getAbsolutePath(), "-d", destDir.getAbsolutePath()
            );
            pb.inheritIO();
            Process proc = pb.start();
            if (proc.waitFor() != 0) throw new IOException("압축 해제 실패");
        } catch (InterruptedException e) {
            throw new IOException("압축 해제 실패(Interrupted)");
        }
    }
}