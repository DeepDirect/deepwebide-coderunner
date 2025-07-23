package com.sandbox.sandbox_server.service;

import com.sandbox.sandbox_server.util.DockerfileUtil;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
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

        // 4. Dockerfile 자동 생성
        DockerfileUtil.generateDockerfile(projectDir, framework);

        // 5. 랜덤 포트 할당(12000~13000)
        int port = 12000 + (int) (Math.random() * 1000);

        // 6. 도커 빌드 & 실행 (build_and_run.sh 호출)
        String script = String.format("bash scripts/build_and_run.sh %s %d %s", uuid, port, framework);
        ProcessBuilder pb = new ProcessBuilder(script.split(" "));
        pb.directory(new File(".")); // 루트 기준 실행
        pb.inheritIO(); // (로그 직접 보기 원할 때)
        Process proc = pb.start();
        try {
            if (proc.waitFor() != 0) {
                throw new IOException("도커 빌드/실행 실패");
            }
        } catch (InterruptedException e) {
            throw new IOException("도커 빌드/실행 실패(Interrupted)");
        }

        // 7. 실행 정보 리턴
        // uuid:port 형태로 반환 (컨트롤러에서 분리)
        return uuid + ":" + port;
    }

    private void unzip(File zipFile, File destDir) throws IOException {
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

    public String runProject(String uuid, String s3Url, String framework, int port) throws IOException {
        Path projectDir = Paths.get("uploads", uuid);
        Files.createDirectories(projectDir);

        // 1. S3 파일 다운로드
        Path zipPath = projectDir.resolve("project.zip");
        try (InputStream in = new URL(s3Url).openStream()) {
            Files.copy(in, zipPath, StandardCopyOption.REPLACE_EXISTING);
        }

        // 2. 압축 해제
        unzip(zipPath.toFile(), projectDir.toFile());

        // 3. Dockerfile 생성
        DockerfileUtil.generateDockerfile(projectDir, framework);

        // 4. 실행 스크립트 호출
        String script = String.format("bash scripts/build_and_run.sh %s %d %s", uuid, port, framework);
        ProcessBuilder pb = new ProcessBuilder(script.split(" "));
        pb.directory(new File(".")); // 프로젝트 루트
        pb.inheritIO();

        Process proc = pb.start();
        try {
            int exitCode = proc.waitFor();
            if (exitCode != 0) {
                throw new IOException("도커 실행 실패: 종료 코드 " + exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 인터럽트 상태 복원 (권장)
            throw new IOException("도커 실행 중 인터럽트 발생", e);
        }

        return uuid + ":" + port;
    }
}
