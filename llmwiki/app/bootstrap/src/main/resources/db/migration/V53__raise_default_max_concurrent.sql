-- 默认并发从 1 调整为 25：列默认值 + 存量旧默认值（个人库 1 / 团队库 3）一并上调
ALTER TABLE scope MODIFY COLUMN max_concurrent INT NOT NULL DEFAULT 25;

UPDATE scope SET max_concurrent = 25 WHERE max_concurrent IN (1, 3);
