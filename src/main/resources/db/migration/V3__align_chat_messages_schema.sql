-- ============================================================
-- V3: Align chat_messages schema with ChatMessage entity
-- Entity expects: message (TEXT NOT NULL), sent_at (TIMESTAMP)
-- Baseline V1 had: content (TEXT), created_at (TIMESTAMP), message_type
-- ============================================================

-- Rename 'content' -> 'message' (only if old column exists & new doesn't)
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'chat_messages' AND column_name = 'content'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'chat_messages' AND column_name = 'message'
    ) THEN
        ALTER TABLE chat_messages RENAME COLUMN content TO message;
    END IF;
END $$;

-- Rename 'created_at' -> 'sent_at' (only if old column exists & new doesn't)
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'chat_messages' AND column_name = 'created_at'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'chat_messages' AND column_name = 'sent_at'
    ) THEN
        ALTER TABLE chat_messages RENAME COLUMN created_at TO sent_at;
    END IF;
END $$;

-- Add columns if they still don't exist (fresh DB safety)
ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS message TEXT;
ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS sent_at TIMESTAMP;

-- Enforce NOT NULL on message (entity contract)
UPDATE chat_messages SET message = '' WHERE message IS NULL;
ALTER TABLE chat_messages ALTER COLUMN message SET NOT NULL;

-- Drop legacy column if present
ALTER TABLE chat_messages DROP COLUMN IF EXISTS message_type;

