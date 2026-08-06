-- V21: Add ruling_brief_json column to lint_finding for AI自治闭环裁决简报
ALTER TABLE lint_finding ADD COLUMN ruling_brief_json TEXT COMMENT '裁决简报JSON（证据、推荐方案、风险标签）' AFTER extra;