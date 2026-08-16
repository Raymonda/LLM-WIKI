package org.cn.liuwt.llmwiki.domain.service.harness;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PythonProcessRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void buildScrubbedEnvironment_keepsAllowlistedVariables() {
        Map<String, String> source = new HashMap<>();
        source.put("PATH", "/usr/bin");
        source.put("HOME", "/home/tester");
        source.put("PYTHONIOENCODING", "ascii");

        Map<String, String> scrubbed = PythonProcessRunner.buildScrubbedEnvironment(source);

        assertEquals(3, scrubbed.size());
        assertEquals("/usr/bin", scrubbed.get("PATH"));
        assertEquals("/home/tester", scrubbed.get("HOME"));
        assertEquals("ascii", scrubbed.get("PYTHONIOENCODING"));
    }

    @Test
    void buildScrubbedEnvironment_dropsSensitiveKeys() {
        Map<String, String> source = new HashMap<>();
        source.put("PATH", "C:\\bin");
        source.put("OPENAI_API_KEY", "sk-secret");
        source.put("JWT_SECRET", "s3cr3t");
        source.put("DB_PASSWORD", "p@ss");

        Map<String, String> scrubbed = PythonProcessRunner.buildScrubbedEnvironment(source);

        assertTrue(scrubbed.containsKey("PATH"));
        assertFalse(scrubbed.containsKey("OPENAI_API_KEY"));
        assertFalse(scrubbed.containsKey("JWT_SECRET"));
        assertFalse(scrubbed.containsKey("DB_PASSWORD"));
    }

    @Test
    void buildScrubbedEnvironment_dropsNonAllowlistedKeys() {
        Map<String, String> source = new HashMap<>();
        source.put("PATH", "C:\\bin");
        source.put("MY_CUSTOM_VAR", "value");
        source.put("SPRING_PROFILES_ACTIVE", "prod");

        Map<String, String> scrubbed = PythonProcessRunner.buildScrubbedEnvironment(source);

        assertEquals(1, scrubbed.size());
        assertFalse(scrubbed.containsKey("MY_CUSTOM_VAR"));
        assertFalse(scrubbed.containsKey("SPRING_PROFILES_ACTIVE"));
    }

    @Test
    void buildScrubbedEnvironment_isCaseInsensitive() {
        Map<String, String> source = new HashMap<>();
        source.put("Path", "C:\\bin");
        source.put("JAVA_HOME", "C:\\jdk");

        Map<String, String> scrubbed = PythonProcessRunner.buildScrubbedEnvironment(source);

        assertTrue(scrubbed.containsKey("Path"));
        assertFalse(scrubbed.containsKey("JAVA_HOME"));
    }

    @Test
    void buildScrubbedEnvironment_handlesNullSource() {
        assertTrue(PythonProcessRunner.buildScrubbedEnvironment(null).isEmpty());
    }

    @Test
    void run_returnsJsonFromStdout() throws Exception {
        Assumptions.assumeTrue(pythonAvailable(), "Python 环境不可用，跳过");

        Path script = tempDir.resolve("ok.py");
        Files.writeString(script, "import sys\nsys.stdout.write('{\"engine\":\"test\"}')\n", StandardCharsets.UTF_8);
        PythonProcessRunner runner = new PythonProcessRunner(script, 30);

        String result = runner.run("nonexistent.txt", "txt");

        assertTrue(result.contains("\"engine\""));
    }

    @Test
    void run_reportsExitCodeOnFailure() throws Exception {
        Assumptions.assumeTrue(pythonAvailable(), "Python 环境不可用，跳过");

        Path script = tempDir.resolve("fail.py");
        Files.writeString(script, "import sys\nsys.exit(3)\n", StandardCharsets.UTF_8);
        PythonProcessRunner runner = new PythonProcessRunner(script, 30);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> runner.run("nonexistent.txt", "txt"));

        assertTrue(ex.getMessage().contains("exit=3"));
    }

    @Test
    void run_timesOutAndTerminatesProcess() throws Exception {
        Assumptions.assumeTrue(pythonAvailable(), "Python 环境不可用，跳过");

        Path script = tempDir.resolve("hang.py");
        Files.writeString(script, "import time\ntime.sleep(300)\n", StandardCharsets.UTF_8);
        PythonProcessRunner runner = new PythonProcessRunner(script, 2);

        long start = System.currentTimeMillis();
        RuntimeException ex = assertThrows(RuntimeException.class, () -> runner.run("nonexistent.txt", "txt"));
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(ex.getMessage().contains("超时"));
        assertTrue(elapsed < 20_000, "Dispose 静止未生效，超时路径耗时 " + elapsed + "ms");
    }

    @Test
    void run_passesCredentialsViaStdinNotArgv() throws Exception {
        Assumptions.assumeTrue(pythonAvailable(), "Python 环境不可用，跳过");

        Path script = tempDir.resolve("echo_creds.py");
        Files.writeString(script, String.join("\n",
                "import sys, json",
                "line = sys.stdin.readline()",
                "argvJoined = ' '.join(sys.argv)",
                "out = {",
                "    'keyInArgv': 'sk-test-secret' in argvJoined,",
                "    'hasStdinFlag': '--credentials-stdin' in argvJoined,",
                "    'stdinHasOcr': 'sk-test-secret-ocr' in line,",
                "    'stdinHasDiagram': 'sk-test-secret-diagram' in line,",
                "}",
                "sys.stdout.write(json.dumps(out))",
                ""), StandardCharsets.UTF_8);
        PythonProcessRunner runner = new PythonProcessRunner(script, 30);

        String result = runner.run("nonexistent.txt", "txt",
                true, "sk-test-secret-ocr", null, 20, 0.3,
                false, null,
                true, "sk-test-secret-diagram", "test-vl", 5);

        assertTrue(result.contains("\"hasStdinFlag\": true"), "argv 应含 --credentials-stdin 标志: " + result);
        assertTrue(result.contains("\"keyInArgv\": false"), "argv 不得包含任何凭据: " + result);
        assertTrue(result.contains("\"stdinHasOcr\": true"), "OCR 凭据应经 stdin 传递: " + result);
        assertTrue(result.contains("\"stdinHasDiagram\": true"), "图表凭据应经 stdin 传递: " + result);
    }

    private static boolean pythonAvailable() {
        try {
            Process p = new ProcessBuilder("python", "--version").redirectErrorStream(true).start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
