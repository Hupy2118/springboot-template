CREATE TABLE IF NOT EXISTS `base`.`engine_whitelist` (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_id VARBINARY(16) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_member_id (member_id)
) ENGINE=InnoDB;
