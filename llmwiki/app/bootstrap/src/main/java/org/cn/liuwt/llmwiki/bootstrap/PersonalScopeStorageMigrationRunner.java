package org.cn.liuwt.llmwiki.bootstrap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ScopeDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.UserDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ScopeMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.UserMapper;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class PersonalScopeStorageMigrationRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(PersonalScopeStorageMigrationRunner.class);

    private static final String TEMP_PREFIX = "__migrate_personal_";

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ScopeMapper scopeMapper;

    @Autowired
    private StorageProvider storageProvider;

    @Value("${llmwiki.storage.provider:local}")
    private String storageProviderName;

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        if (!"local".equalsIgnoreCase(storageProviderName) && !"nas".equalsIgnoreCase(storageProviderName)) {
            LOGGER.info("storage provider is '{}', personal scope directory migration skipped", storageProviderName);
            return;
        }
        try {
            migratePersonalScopeDirectories();
        } catch (Exception e) {
            LOGGER.error("personal scope directory migration failed: {}", e.getMessage(), e);
        }
    }

    private void migratePersonalScopeDirectories() {
        List<UserDO> users = userMapper.selectList(
                new LambdaQueryWrapper<UserDO>().select(UserDO::getId, UserDO::getScopeId));
        Set<Long> nonPersonalScopeIds = scopeMapper.selectList(
                        new LambdaQueryWrapper<ScopeDO>()
                                .select(ScopeDO::getId, ScopeDO::getType)
                                .ne(ScopeDO::getType, "personal"))
                .stream()
                .map(ScopeDO::getId)
                .collect(Collectors.toSet());

        List<Job> jobs = new ArrayList<>();
        for (UserDO user : users) {
            Long oldId = user.getId();
            Long newId = user.getScopeId();
            if (oldId == null || newId == null || newId == 0L || newId.equals(oldId)) {
                continue;
            }
            if (nonPersonalScopeIds.contains(oldId)) {
                LOGGER.debug("skip personal scope dir {}: collides with non-personal scope id", oldId);
                continue;
            }
            jobs.add(new Job(String.valueOf(oldId), String.valueOf(newId)));
        }

        if (jobs.isEmpty()) {
            LOGGER.info("personal scope directory migration: nothing to do");
            return;
        }

        // Phase A: stage old dirs into unique temp names to break migration chains
        List<Job> staged = new ArrayList<>();
        for (Job job : jobs) {
            String temp = TEMP_PREFIX + job.oldScopeId;
            try {
                if (storageProvider.scopeDirectoryExists(job.oldScopeId)) {
                    if (storageProvider.scopeDirectoryExists(temp)) {
                        LOGGER.warn("source {} and temp {} both exist, skip to avoid data loss", job.oldScopeId, temp);
                        continue;
                    }
                    storageProvider.moveScopeDirectory(job.oldScopeId, temp);
                    staged.add(new Job(temp, job.newScopeId));
                } else if (storageProvider.scopeDirectoryExists(temp)) {
                    staged.add(new Job(temp, job.newScopeId));
                }
            } catch (Exception e) {
                LOGGER.error("failed to stage scope dir {} -> {}: {}", job.oldScopeId, temp, e.getMessage(), e);
            }
        }

        // Phase B: move staged temp dirs to final targets
        int moved = 0;
        int skipped = 0;
        for (Job job : staged) {
            try {
                if (storageProvider.scopeDirectoryExists(job.newScopeId)) {
                    LOGGER.warn("target scope dir {} already exists, skip {} -> {}",
                            job.newScopeId, job.oldScopeId, job.newScopeId);
                    skipped++;
                    continue;
                }
                storageProvider.moveScopeDirectory(job.oldScopeId, job.newScopeId);
                moved++;
            } catch (Exception e) {
                LOGGER.error("failed to move scope dir {} -> {}: {}", job.oldScopeId, job.newScopeId, e.getMessage(), e);
            }
        }

        LOGGER.info("personal scope directory migration complete: {} moved, {} skipped, {} jobs total",
                moved, skipped, jobs.size());
    }

    private static final class Job {
        final String oldScopeId;
        final String newScopeId;

        Job(String oldScopeId, String newScopeId) {
            this.oldScopeId = oldScopeId;
            this.newScopeId = newScopeId;
        }
    }
}