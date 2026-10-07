SET NAMES UTF8;

-- H2（MySQL 兼容模式）测试库结构：与 mysql/schema.sql 等价，去掉 MySQL 专有语法（KEY 内联索引、ENGINE 子句等）

DROP TABLE IF EXISTS sale_out_item;
DROP TABLE IF EXISTS sale_out;
DROP TABLE IF EXISTS cellar_batch;
DROP TABLE IF EXISTS member;
DROP TABLE IF EXISTS wine;

CREATE TABLE wine (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    code       VARCHAR(32)  NOT NULL UNIQUE,
    name       VARCHAR(128) NOT NULL,
    region     VARCHAR(16)  NOT NULL,
    type       VARCHAR(16)  NOT NULL,
    stock      INT          NOT NULL,
    price      DECIMAL(10,2) NOT NULL,
    status     VARCHAR(16)  NOT NULL,
    created_at DATETIME
);

CREATE TABLE cellar_batch (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    wine_id      BIGINT       NOT NULL,
    batch_no     VARCHAR(32)  NOT NULL UNIQUE,
    bottle_count INT          NOT NULL,
    sold_count   INT          NOT NULL DEFAULT 0,
    cellar_date  DATE         NOT NULL,
    status       VARCHAR(16)  NOT NULL,
    created_at   DATETIME
);

CREATE TABLE member (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    phone      VARCHAR(24)  NOT NULL UNIQUE,
    name       VARCHAR(32)  NOT NULL,
    level      VARCHAR(16)  NOT NULL,
    points     INT          NOT NULL DEFAULT 0,
    created_at DATETIME
);

CREATE TABLE sale_out (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id     BIGINT,
    total         DECIMAL(12,2) NOT NULL,
    points_earned INT           NOT NULL DEFAULT 0,
    status        VARCHAR(16)   NOT NULL,
    created_at    DATETIME
);

CREATE TABLE sale_out_item (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    sale_id  BIGINT NOT NULL,
    wine_id  BIGINT NOT NULL,
    batch_id BIGINT NOT NULL,
    qty      INT    NOT NULL
);

-- 测试数据：酒款 1 有两个可售批次（FIFO 演示），酒款 2 仅有窖藏中批次（放行门槛演示）
INSERT INTO wine (id, code, name, region, type, stock, price, status, created_at) VALUES
(1, 'W-1001', '测试解百纳', '国产', '红', 80, 100.00, '在售', CURRENT_TIMESTAMP),
(2, 'W-1002', '测试窖藏酒', '国产', '红',  0, 200.00, '在售', CURRENT_TIMESTAMP),
(3, 'W-1003', '测试停售酒', '法国', '红', 10, 300.00, '停售', CURRENT_TIMESTAMP);

INSERT INTO cellar_batch (id, wine_id, batch_no, bottle_count, sold_count, cellar_date, status, created_at) VALUES
(1, 1, 'B-T-01', 50,  0, '2025-01-01', '可售',   CURRENT_TIMESTAMP),
(2, 1, 'B-T-02', 30,  0, '2025-02-01', '可售',   CURRENT_TIMESTAMP),
(3, 2, 'B-T-03', 40,  0, '2025-03-01', '窖藏中', CURRENT_TIMESTAMP),
(4, 3, 'B-T-04', 10,  0, '2025-01-05', '可售',   CURRENT_TIMESTAMP);

INSERT INTO member (id, phone, name, level, points, created_at) VALUES
(1, '13900000001', '会员甲', '普通', 1900, CURRENT_TIMESTAMP);
