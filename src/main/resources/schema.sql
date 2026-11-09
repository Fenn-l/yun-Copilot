-- 企业智能客服与工单 Copilot 数据库结构（MySQL 8）
-- 所有业务元数据、消息、知识切片（含向量）与工单数据均落库 MySQL

CREATE TABLE IF NOT EXISTS cs_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    password VARCHAR(255) NOT NULL,
    nickname VARCHAR(64) NOT NULL DEFAULT '',
    email VARCHAR(128) DEFAULT NULL,
    role VARCHAR(32) NOT NULL DEFAULT 'USER',
    status TINYINT NOT NULL DEFAULT 1,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

CREATE TABLE IF NOT EXISTS cs_conversation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_no VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL DEFAULT '新会话',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_conv_no (conversation_no),
    KEY idx_conv_user (user_id, status, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话主表';

CREATE TABLE IF NOT EXISTS cs_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    message_type VARCHAR(32) NOT NULL DEFAULT 'TEXT',
    token_count INT NOT NULL DEFAULT 0,
    citations LONGTEXT DEFAULT NULL,
    handoff_required TINYINT NOT NULL DEFAULT 0,
    handoff_reason VARCHAR(64) DEFAULT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_msg_conv (conversation_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话消息表';

CREATE TABLE IF NOT EXISTS kb_base (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(500) DEFAULT NULL,
    owner_id BIGINT NOT NULL,
    visibility VARCHAR(32) NOT NULL DEFAULT 'PUBLIC',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_kb_owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库';

CREATE TABLE IF NOT EXISTS kb_document (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    knowledge_base_id BIGINT NOT NULL,
    document_name VARCHAR(255) NOT NULL,
    file_url VARCHAR(500) NOT NULL,
    file_type VARCHAR(32) NOT NULL,
    file_size BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'UPLOADED',
    error_message VARCHAR(1000) DEFAULT NULL,
    chunk_count INT NOT NULL DEFAULT 0,
    process_version INT NOT NULL DEFAULT 0,
    embedding_model VARCHAR(128) DEFAULT NULL,
    embedding_dim INT DEFAULT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_doc_kb (knowledge_base_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库文档';

CREATE TABLE IF NOT EXISTS kb_chunk (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    embedding MEDIUMBLOB DEFAULT NULL,
    embedding_dim INT DEFAULT NULL,
    embedding_model VARCHAR(128) DEFAULT NULL,
    page_number INT DEFAULT NULL,
    char_count INT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_chunk_doc (document_id),
    KEY idx_chunk_kb (knowledge_base_id, embedding_model)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档切片与向量';

CREATE TABLE IF NOT EXISTS cs_ticket (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    ticket_no VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    conversation_id BIGINT DEFAULT NULL,
    assignee_id BIGINT DEFAULT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    category VARCHAR(64) NOT NULL DEFAULT 'OTHER',
    priority VARCHAR(32) NOT NULL DEFAULT 'MEDIUM',
    status VARCHAR(32) NOT NULL DEFAULT 'NEW',
    version INT NOT NULL DEFAULT 0,
    resolved_time DATETIME DEFAULT NULL,
    closed_time DATETIME DEFAULT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ticket_no (ticket_no),
    KEY idx_ticket_list (status, assignee_id, create_time),
    KEY idx_ticket_user (user_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工单主表';

CREATE TABLE IF NOT EXISTS cs_ticket_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    ticket_id BIGINT NOT NULL,
    operator_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    old_status VARCHAR(32) DEFAULT NULL,
    new_status VARCHAR(32) DEFAULT NULL,
    content TEXT DEFAULT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_event_ticket (ticket_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工单流转记录';

CREATE TABLE IF NOT EXISTS cs_ai_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT DEFAULT NULL,
    message_id BIGINT DEFAULT NULL,
    ticket_id BIGINT DEFAULT NULL,
    scene VARCHAR(32) NOT NULL DEFAULT 'CHAT',
    provider VARCHAR(32) DEFAULT NULL,
    model_name VARCHAR(128) DEFAULT NULL,
    prompt_tokens INT NOT NULL DEFAULT 0,
    completion_tokens INT NOT NULL DEFAULT 0,
    latency_ms BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'SUCCESS',
    error_message TEXT DEFAULT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_run_conv (conversation_id),
    KEY idx_run_time (create_time),
    KEY idx_run_scene (scene)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 调用记录';

CREATE TABLE IF NOT EXISTS cs_feedback (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    message_id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    rating TINYINT NOT NULL,
    comment VARCHAR(1000) DEFAULT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_feedback_msg_user (message_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 答案评价';

CREATE TABLE IF NOT EXISTS ai_setting (
    id BIGINT PRIMARY KEY,
    provider VARCHAR(32) NOT NULL,
    base_url VARCHAR(500) NOT NULL,
    api_key VARCHAR(500) NOT NULL,
    chat_model VARCHAR(128) NOT NULL,
    embedding_model VARCHAR(128) NOT NULL,
    embedding_dim INT NOT NULL DEFAULT 1024,
    temperature DOUBLE NOT NULL DEFAULT 0.3,
    max_tokens INT NOT NULL DEFAULT 1500,
    updated_by BIGINT DEFAULT NULL,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 模型运行配置';

CREATE TABLE IF NOT EXISTS ai_provider_key (
    provider VARCHAR(32) PRIMARY KEY,
    api_key VARCHAR(500) NOT NULL,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='按提供方独立保存的 API Key';
