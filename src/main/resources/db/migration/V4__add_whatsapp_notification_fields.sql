-- ============================================================
-- V4: Add WhatsApp notification fields to users table
-- Adds whatsapp_number, whatsapp_api_key (CallMeBot per-user key),
-- and whatsapp_notifications_enabled for teacher-level notifications.
-- ============================================================

ALTER TABLE users ADD COLUMN IF NOT EXISTS whatsapp_number VARCHAR(20);
ALTER TABLE users ADD COLUMN IF NOT EXISTS whatsapp_api_key VARCHAR(64);
ALTER TABLE users ADD COLUMN IF NOT EXISTS whatsapp_notifications_enabled BOOLEAN NOT NULL DEFAULT FALSE;
