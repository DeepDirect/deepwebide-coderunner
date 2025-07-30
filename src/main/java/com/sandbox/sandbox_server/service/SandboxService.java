package com.sandbox.sandbox_server.service;

import com.sandbox.sandbox_server.util.DockerfileUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Slf4j
@Service
public class SandboxService {

    private static final int DOCKER_TIMEOUT_SECONDS = 300;

    /**
     * 프로젝트 실행 전체 프로세스 (S3 다운로드 → 압축 해제 → Dockerfile 생성 → 도커 실행)
     * @return 실행 결과 문자열(컨테이너명:포트)
     */
    public String runProject(String uuid, String s3Url, String framework, int port) throws IOException {
        log.info("Starting project execution - uuid: {}, framework: {}, port: {}", uuid, framework, port);

        Path projectDir = Paths.get("uploads", uuid);
        Files.createDirectories(projectDir);

        try {
            // 1. S3에서 ZIP 다운로드
            Path zipPath = downloadFromS3(s3Url, projectDir);

            // 2. 압축 해제
            unzip(zipPath.toFile(), projectDir.toFile());

            // 3. 프로젝트 구조 정규화 (옵션)
            normalizeProjectStructure(projectDir.toFile());

            // 4. Dockerfile 생성
            DockerfileUtil.generateDockerfile(projectDir, framework);

            // 5. Docker 빌드 및 실행
            runDockerContainer(uuid, port, framework);

            log.info("Project execution completed - uuid: {}, port: {}", uuid, port);
            return uuid + ":" + port;

        } catch (Exception e) {
            log.error("Project execution failed - uuid: {}", uuid, e);
            cleanupResources(uuid);
            throw new IOException("Project execution failed: " + e.getMessage(), e);
        }
    }

    private Path downloadFromS3(String s3Url, Path projectDir) throws IOException {
        log.debug("Downloading file from S3: {}", s3Url);

        Path zipPath = projectDir.resolve("project.zip");
        try (InputStream in = new URL(s3Url).openStream()) {
            Files.copy(in, zipPath, StandardCopyOption.REPLACE_EXISTING);
        }

        long fileSize = Files.size(zipPath);
        log.debug("S3 download completed: {} ({} bytes)", zipPath, fileSize);
        return zipPath;
    }

    private void unzip(File zipFile, File destDir) throws IOException {
        log.debug("Extracting zip file: {}", zipFile.getName());

        try (ZipFile zip = new ZipFile(zipFile)) {
            zip.stream()
                    .filter(entry -> !isMacOSMetadata(entry.getName()))
                    .forEach(entry -> {
                        try {
                            extractZipEntry(zip, entry, destDir);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }

        // 압축 파일 삭제
        Files.deleteIfExists(zipFile.toPath());
        log.debug("Zip extraction completed");
    }

    private boolean isMacOSMetadata(String entryName) {
        return entryName.contains("__MACOSX") ||
                entryName.contains(".DS_Store") ||
                entryName.contains("/._") ||
                entryName.startsWith("._");
    }

    private void extractZipEntry(ZipFile zip, ZipEntry entry, File destDir) throws IOException {
        File destFile = new File(destDir, entry.getName());

        // 디렉토리 트래버설 방지
        if (!destFile.getCanonicalPath().startsWith(destDir.getCanonicalPath())) {
            throw new IOException("Entry is outside of the target dir: " + entry.getName());
        }

        if (entry.isDirectory()) {
            destFile.mkdirs();
        } else {
            destFile.getParentFile().mkdirs();
            try (InputStream in = zip.getInputStream(entry);
                 FileOutputStream out = new FileOutputStream(destFile)) {
                in.transferTo(out);
            }

            // 실행 권한
            if (entry.getName().endsWith("gradlew") || entry.getName().endsWith(".sh")) {
                destFile.setExecutable(true);
                log.debug("Set executable permission for: {}", entry.getName());
            }
        }
    }

    private void normalizeProjectStructure(File destDir) throws IOException {
        // (옵션) 프로젝트 폴더 내부에 실제 소스가 또 들어있으면 루트로 올려주는 작업
        // 생략 가능: 이미 ZIP 구조가 맞는 경우 그대로 진행
    }

    /**
     * 도커 빌드 및 컨테이너 실행
     */
    private void runDockerContainer(String uuid, int port, String framework) throws IOException, InterruptedException {
        log.info("Running docker container - uuid: {}, port: {}, framework: {}", uuid, port, framework);

        File scriptFile = new File("scripts/build_and_run.sh");
        if (!scriptFile.exists()) {
            throw new IOException("Build script not found: " + scriptFile.getAbsolutePath());
        }

        ProcessBuilder pb = new ProcessBuilder(
                "bash", "-x", "scripts/build_and_run.sh", uuid, String.valueOf(port), framework
        );

        pb.directory(new File("."));
        pb.redirectErrorStream(true);

        log.info("Executing command: {}", String.join(" ", pb.command()));

        Process process = pb.start();

        // 실시간 출력 로깅
        StringBuilder output = new StringBuilder();
        CompletableFuture<Void> outputFuture = CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("DOCKER [{}]: {}", uuid, line);
                    output.append(line).append("\n");
                }
            } catch (IOException e) {
                log.warn("Failed to read process output", e);
            }
        });

        boolean finished = process.waitFor(DOCKER_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (!finished) {
            log.error("Docker process timed out after {} seconds", DOCKER_TIMEOUT_SECONDS);
            process.destroyForcibly();
            throw new IOException("Docker execution timed out after " + DOCKER_TIMEOUT_SECONDS + " seconds");
        }

        // 출력 스레드 완료 대기
        try {
            outputFuture.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Failed to wait for output thread completion", e);
        }

        int exitCode = process.exitValue();
        log.info("Docker process completed with exit code: {}", exitCode);

        if (exitCode != 0) {
            throw new IOException("Docker execution failed:\n" + output);
        }
    }

    private void cleanupResources(String uuid) {
        // 프로젝트 디렉토리 삭제 등 정리 로직 (선택)
        Path projectDir = Paths.get("uploads", uuid);
        try {
            if (Files.exists(projectDir)) {
                Files.walk(projectDir)
                        .sorted((a, b) -> b.compareTo(a)) // 하위부터 삭제
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        } catch (IOException e) {
            log.warn("Failed to cleanup project directory: {}", projectDir, e);
        }
    }
}
