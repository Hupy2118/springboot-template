"""受控单 worker 拓扑和 checkpoint 命名空间的操作系统排他锁。"""
from __future__ import annotations

import hashlib
import os
from uvicorn import Config

from app.settings import RuntimeSettings

_server_config: Config | None = None
_server_pid: int | None = None


def bind_server_config(config: Config) -> None:
    """由受控入口绑定即将实际执行的 Uvicorn 配置，不接受 HTTP 自报拓扑。"""
    global _server_config, _server_pid
    if type(config.workers) is not int or config.workers != 1 or config.reload or config.host != "127.0.0.1" or config.app != "app.server.app:app":
        raise RuntimeError("Runtime 只支持受控入口的 loopback 单 worker 配置。")
    _server_config = config
    _server_pid = os.getpid()


def validate_workers() -> int:
    """要求启动器显式声明单 worker，并拒绝宿主环境中的多 worker 配置。"""
    if os.getenv("AGENT_RUNTIME_WORKERS") != "1":
        raise RuntimeError("AGENT_RUNTIME_WORKERS 必须显式为 1。")
    for name in ("WEB_CONCURRENCY", "UVICORN_WORKERS"):
        if name in os.environ and os.environ[name] != "1":
            raise RuntimeError(f"{name} 与 Runtime 单 worker 合同冲突。")
    return 1


class DeploymentLock:
    """持有内核文件锁；锁文件可以保留，但不能用 PID 文件冒充执行权。"""

    def __init__(self, settings: RuntimeSettings) -> None:
        """绑定实际 checkpoint 路径，同一存储即共享互斥，防止 Profile 别名绕过。"""
        self.settings = settings
        self.file = None
        self.pid = None
        self.workers = None

    def acquire(self) -> None:
        """在 lifespan 开放路由之前取得非阻塞排他锁，冲突立即启动失败。"""
        validate_workers()
        if _server_config is None or _server_pid != os.getpid() or _server_config.workers != 1 or _server_config.reload:
            raise RuntimeError("无法核验实际服务配置，请使用 agent-runtime 受控入口启动。")
        self.workers = _server_config.workers
        path = self.settings.checkpoint_db_path.parent / "runtime.lock"
        path.parent.mkdir(parents=True, exist_ok=True)
        handle = path.open("a+b")
        try:
            if os.name == "nt":
                import msvcrt
                handle.seek(0, os.SEEK_END)
                if handle.tell() == 0:
                    handle.write(b"\0")
                    handle.flush()
                handle.seek(0)
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            handle.close()
            raise RuntimeError("当前 checkpoint 命名空间已由其他 Runtime 持有。") from None
        self.file, self.pid = handle, os.getpid()

    def close(self) -> None:
        """在服务资源收口后释放内核锁，不删除可能被其他进程打开的锁文件。"""
        if self.file is not None:
            self.file.close()
            self.file = None

    def facts(self, accepting_runs: bool) -> dict:
        """只公开实际持锁进程的非敏感事实，身份和绝对路径使用摘要。"""
        return {
            "workers": self.workers,
            "processId": self.pid,
            "profile": self.settings.runtime_profile,
            "applicationSha256": hashlib.sha256(str(self.settings.runtime_root.resolve()).encode()).hexdigest(),
            "namespaceSha256": hashlib.sha256(self.settings.namespace.encode()).hexdigest(),
            "processLockHeld": self.file is not None and not self.file.closed and self.pid == os.getpid(),
            "acceptingRuns": accepting_runs,
        }
