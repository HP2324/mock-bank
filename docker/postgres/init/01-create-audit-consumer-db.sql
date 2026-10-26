-- Runs once, when the postgres volume is first created. The consumer service owns its own
-- database instead of reading mock-bank's tables.
CREATE DATABASE audit_consumer OWNER mockbank;
