ALTER TABLE schema_config ADD COLUMN config_value_structured JSON DEFAULT NULL AFTER config_value;
ALTER TABLE schema_config_version ADD COLUMN config_value_structured JSON DEFAULT NULL AFTER config_value;
