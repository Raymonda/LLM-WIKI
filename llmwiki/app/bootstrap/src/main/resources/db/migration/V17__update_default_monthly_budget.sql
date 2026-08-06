ALTER TABLE scope_budget MODIFY COLUMN monthly_budget INT NOT NULL DEFAULT 1000000;

ALTER TABLE scope MODIFY COLUMN monthly_budget INT NOT NULL DEFAULT 1000000;

UPDATE scope_budget SET monthly_budget = 1000000 WHERE monthly_budget = 50000;

UPDATE scope SET monthly_budget = 1000000 WHERE monthly_budget = 50000;