-- ============================================================
-- Flyway Migration V1: Baseline Schema
-- Host Student Meeting Application
--
-- This migration creates the baseline schema from existing tables.
-- It will be skipped if tables already exist (baseline-on-migrate=true).
-- ============================================================

-- Users table
CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    teacher_name VARCHAR(255) NOT NULL,
    username VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    raw_password VARCHAR(255),
    display_name VARCHAR(255) NOT NULL,
    role VARCHAR(50) NOT NULL,
    email VARCHAR(255),
    phone VARCHAR(50),
    profile_image VARCHAR(512),
    teacher_logo VARCHAR(512),
    active BOOLEAN DEFAULT true,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    last_login_at TIMESTAMP
);

-- Meetings table
CREATE TABLE IF NOT EXISTS meetings (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    meeting_code VARCHAR(50) UNIQUE,
    host_id BIGINT REFERENCES users(id),
    status VARCHAR(50),
    scheduled_at TIMESTAMP,
    started_at TIMESTAMP,
    ended_at TIMESTAMP,
    recording_enabled BOOLEAN DEFAULT false,
    chat_enabled BOOLEAN DEFAULT true,
    max_participants INTEGER DEFAULT 50,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

-- Meeting participants
CREATE TABLE IF NOT EXISTS meeting_participants (
    id BIGSERIAL PRIMARY KEY,
    meeting_id BIGINT REFERENCES meetings(id) ON DELETE CASCADE,
    user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    joined_at TIMESTAMP,
    left_at TIMESTAMP,
    role VARCHAR(50)
);

-- Recordings table
CREATE TABLE IF NOT EXISTS recordings (
    id BIGSERIAL PRIMARY KEY,
    meeting_id BIGINT REFERENCES meetings(id) ON DELETE SET NULL,
    recorded_by_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    file_name VARCHAR(255),
    file_path VARCHAR(512),
    content_type VARCHAR(100),
    file_size BIGINT,
    duration_seconds BIGINT,
    status VARCHAR(50),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

-- Transcripts table
CREATE TABLE IF NOT EXISTS transcripts (
    id BIGSERIAL PRIMARY KEY,
    recording_id BIGINT REFERENCES recordings(id) ON DELETE CASCADE,
    user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    content TEXT,
    speaker_name VARCHAR(255),
    start_time_seconds INTEGER,
    end_time_seconds INTEGER,
    language VARCHAR(10) DEFAULT 'en',
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

-- Chat messages table
CREATE TABLE IF NOT EXISTS chat_messages (
    id BIGSERIAL PRIMARY KEY,
    meeting_id BIGINT REFERENCES meetings(id) ON DELETE CASCADE,
    sender_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    content TEXT NOT NULL,
    message_type VARCHAR(50),
    created_at TIMESTAMP
);

-- Schedules table
CREATE TABLE IF NOT EXISTS schedules (
    id BIGSERIAL PRIMARY KEY,
    teacher_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    scheduled_start_time TIMESTAMP NOT NULL,
    scheduled_end_time TIMESTAMP,
    active BOOLEAN DEFAULT true,
    notification_sent BOOLEAN DEFAULT false,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

-- Notifications table
CREATE TABLE IF NOT EXISTS notifications (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    message TEXT,
    type VARCHAR(50),
    is_read BOOLEAN DEFAULT false,
    created_at TIMESTAMP
);

-- Indexes for performance
CREATE INDEX IF NOT EXISTS idx_users_teacher_name ON users(teacher_name);
CREATE INDEX IF NOT EXISTS idx_users_username ON users(username);
CREATE INDEX IF NOT EXISTS idx_meetings_host_id ON meetings(host_id);
CREATE INDEX IF NOT EXISTS idx_meetings_status ON meetings(status);
CREATE INDEX IF NOT EXISTS idx_meeting_participants_meeting_id ON meeting_participants(meeting_id);
CREATE INDEX IF NOT EXISTS idx_meeting_participants_user_id ON meeting_participants(user_id);
CREATE INDEX IF NOT EXISTS idx_recordings_meeting_id ON recordings(meeting_id);
CREATE INDEX IF NOT EXISTS idx_recordings_recorded_by ON recordings(recorded_by_id);
CREATE INDEX IF NOT EXISTS idx_transcripts_recording_id ON transcripts(recording_id);
CREATE INDEX IF NOT EXISTS idx_schedules_teacher_id ON schedules(teacher_id);
CREATE INDEX IF NOT EXISTS idx_notifications_user_id ON notifications(user_id);

