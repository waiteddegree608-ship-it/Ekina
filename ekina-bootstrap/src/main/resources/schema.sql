-- Enable PGvector Extension
CREATE EXTENSION IF NOT EXISTS vector;

-- Core Users Table
CREATE TABLE IF NOT EXISTS users (
    id VARCHAR(64) PRIMARY KEY,
    nickname VARCHAR(128),
    created_at TIMESTAMP NOT NULL
);

-- User Profiles Table
CREATE TABLE IF NOT EXISTS user_profiles (
    user_id VARCHAR(64) PRIMARY KEY,
    age INTEGER,
    gender VARCHAR(10),
    job VARCHAR(128),
    location VARCHAR(256),
    habits TEXT,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_user_profile FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- User Memories (Vector-indexed short-term memories)
CREATE TABLE IF NOT EXISTS user_memories (
    id UUID PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    content TEXT NOT NULL,
    embedding vector(1024) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_user_memory FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Tasks and Alarms (ToDo item list)
CREATE TABLE IF NOT EXISTS todo_tasks (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    content TEXT NOT NULL,
    target_time TIMESTAMP NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_user_todo FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Public Shared RAG Knowledge Base Chunks
CREATE TABLE IF NOT EXISTS public_knowledge_chunks (
    id UUID PRIMARY KEY,
    domain_tag VARCHAR(64) NOT NULL,
    title VARCHAR(256),
    content TEXT NOT NULL,
    embedding vector(1024) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

-- Serialized Workflow State Snapshot Storage (Kryo recovery)
CREATE TABLE IF NOT EXISTS workflow_states (
    workflow_id VARCHAR(64) PRIMARY KEY,
    state_bytes BYTEA NOT NULL
);

-- WeChat Outbound Message Durable Queue Table
CREATE TABLE IF NOT EXISTS wechat_outbound_msgs (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    message_type VARCHAR(20) NOT NULL,
    content TEXT,
    payload BYTEA,
    priority INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    retry_count INTEGER NOT NULL,
    next_send_time TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    error_message TEXT
);

-- High-performance HNSW indices on PGvector embeddings for fast approximate nearest neighbor search
CREATE INDEX IF NOT EXISTS idx_user_memories_embedding ON user_memories USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_public_knowledge_chunks_embedding ON public_knowledge_chunks USING hnsw (embedding vector_cosine_ops);
