from __future__ import annotations

import uvicorn

from app.settings import load_settings
from app.server.deployment import bind_server_config, validate_workers


def main() -> None:
    """读取非敏感监听配置并启动 Agent Runtime。"""

    settings = load_settings()
    workers = validate_workers()
    config = uvicorn.Config(
        "app.server.app:app",
        host=settings.host,
        port=settings.port,
        log_level="info",
        reload=False,
        workers=workers,
    )
    bind_server_config(config)
    uvicorn.Server(config).run()


if __name__ == "__main__":
    main()

