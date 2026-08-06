package org.cn.liuwt.llmwiki.bootstrap;

import org.cn.liuwt.llmwiki.domain.service.harness.PythonProcessRunner;
import org.cn.liuwt.llmwiki.domain.service.harness.PythonProcessRunner.PythonEnvironmentCheckResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PythonEnvironmentBootstrap implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(PythonEnvironmentBootstrap.class);

    @Override
    public void run(ApplicationArguments args) {
        LOGGER.info("Checking Python environment...");
        PythonEnvironmentCheckResult result = PythonProcessRunner.checkEnvironment();

        if (!result.ok()) {
            LOGGER.warn("");
            LOGGER.warn("================ PYTHON ENVIRONMENT WARNING ================");
            LOGGER.warn("  Python command : {}", result.pythonCommand());
            LOGGER.warn("  Python version : {}", result.pythonVersion());
            if (!result.missingRequired().isEmpty()) {
                LOGGER.warn("");
                LOGGER.warn("  [REQUIRED] Missing packages (PDF/DOCX/XLSX/PPTX parsing will FAIL):");
                for (String pkg : result.missingRequired()) {
                    LOGGER.warn("    - {}", pkg);
                }
                LOGGER.warn("");
                LOGGER.warn("  Fix: pip install {}", String.join(" ", result.missingRequired()));
            }
            if (!result.missingOptional().isEmpty()) {
                LOGGER.warn("");
                LOGGER.warn("  [OPTIONAL] Missing packages (some formats may have reduced quality):");
                for (String pkg : result.missingOptional()) {
                    LOGGER.warn("    - {}", pkg);
                }
                LOGGER.warn("");
                LOGGER.warn("  Fix: pip install {}", String.join(" ", result.missingOptional()));
            }
            LOGGER.warn("=============================================================");
            LOGGER.warn("");
        } else {
            LOGGER.info("Python environment OK: {} ({})",
                    result.pythonCommand(), result.pythonVersion());
            if (!result.missingOptional().isEmpty()) {
                LOGGER.info("Optional packages not installed: {}", result.missingOptional());
            }
        }
    }
}
