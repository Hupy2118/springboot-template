from __future__ import annotations

import uvicorn

from app.settings import load_settings


def main() -> None:
    """读取非敏感监听配置并启动 Agent Runtime。"""

    settings = load_settings()
    uvicorn.run(
        "app.server.app:app",
        host=settings.host,
        port=settings.port,
        log_level="info",
        reload=False,
    )


if __name__ == "__main__":
    main()

