-- ============================================================
-- Flyway Migration V2: Security - Deprecate raw_password column
--
-- This migration adds a comment to mark raw_password as deprecated
-- and clears existing raw passwords for security.
--
-- The column is kept for backward compatibility but will be
-- removed in a future migration.
-- ============================================================

-- Clear all raw passwords for security (they should never have been stored)
UPDATE users SET raw_password = NULL WHERE raw_password IS NOT NULL;

-- Add comment marking the column as deprecated (PostgreSQL specific)
COMMENT ON COLUMN users.raw_password IS 'DEPRECATED: Do not use. Will be removed in future version. Security risk.';

