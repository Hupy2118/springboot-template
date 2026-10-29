"""业务 MySQL 连接合同，不执行 DDL 或读取 SQLite 配置。"""
from __future__ import annotations

import os
import ssl
from dataclasses import dataclass, field


@dataclass(frozen=True)
class DatabaseSettings:
    """保存分离字段，禁止把凭据拼入 URL 或日志。"""
    enabled: bool = False
    host: str = ""
    port: int = 3306
    username: str = ""
    password: str = field(default="", repr=False)
    name: str = ""
    ssl_mode: str = "DISABLED"

    def connect_options(self) -> dict:
        """构建 PyMySQL 参数；启用 TLS 时禁止静默明文降级。"""
        if not self.enabled:
            raise RuntimeError("业务数据库未启用，请先配置当前应用的数据源。")
        options = dict(host=self.host, port=self.port, user=self.username, password=self.password,
                       database=self.name, charset="utf8mb4", autocommit=False,
                       connect_timeout=5, read_timeout=15, write_timeout=15)
        if self.ssl_mode == "DISABLED":
            options["ssl_disabled"] = True
        else:
            context = ssl.create_default_context()
            context.check_hostname = self.ssl_mode == "VERIFY_IDENTITY"
            if self.ssl_mode in {"PREFERRED", "REQUIRED"}:
                context.verify_mode = ssl.CERT_NONE
            options["ssl"] = context
        return options


def load_database_settings() -> DatabaseSettings:
    """严格解析当前数据库环境，缺失和不支持的参数直接失败。"""
    prefix = "AGENT_RUNTIME_DATABASE_"
    enabled = os.getenv(prefix + "ENABLED", "false")
    if enabled not in {"true", "false"}:
        raise RuntimeError(prefix + "ENABLED 必须为 true/false。")
    if enabled == "false":
        return DatabaseSettings()
    if os.getenv(prefix + "DRIVER") != "pymysql" or os.getenv(prefix + "ALLOW_DDL", "false") != "false":
        raise RuntimeError("Runtime 业务库只支持 pymysql 且禁止启动 DDL。")
    fields = {key: os.getenv(prefix + key, "") for key in ("HOST", "USERNAME", "PASSWORD", "NAME")}
    if not all(fields.values()):
        raise RuntimeError("Runtime 业务数据库连接字段未完整注入。")
    try:
        port = int(os.getenv(prefix + "PORT", "3306"))
    except ValueError:
        raise RuntimeError("Runtime 数据库端口无效。") from None
    mode = os.getenv(prefix + "SSL_MODE", "DISABLED")
    if not 1 <= port <= 65535 or mode not in {"DISABLED", "PREFERRED", "REQUIRED", "VERIFY_CA", "VERIFY_IDENTITY"}:
        raise RuntimeError("Runtime 数据库端口或 SSL_MODE 无效。")
    return DatabaseSettings(True, fields["HOST"], port, fields["USERNAME"], fields["PASSWORD"], fields["NAME"], mode)
