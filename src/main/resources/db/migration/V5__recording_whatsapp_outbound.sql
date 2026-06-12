-- Persist WhatsApp outbound lifecycle per recording (Twilio SID + status).
-- Aligns with async-send + webhook pattern; complements in-memory teacher status UI.

ALTER TABLE recordings ADD COLUMN IF NOT EXISTS whatsapp_outbound_status VARCHAR(32);
ALTER TABLE recordings ADD COLUMN IF NOT EXISTS whatsapp_outbound_message_id VARCHAR(64);
ALTER TABLE recordings ADD COLUMN IF NOT EXISTS whatsapp_outbound_detail VARCHAR(512);
ALTER TABLE recordings ADD COLUMN IF NOT EXISTS whatsapp_outbound_updated_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_recordings_whatsapp_outbound_msg
    ON recordings (whatsapp_outbound_message_id)
    WHERE whatsapp_outbound_message_id IS NOT NULL;
