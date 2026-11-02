"""在官方 AG-UI 类型解析之前限制原始 HTTP 输入，不相信 Content-Length。"""
from __future__ import annotations

import asyncio
import json
import time

from fastapi import HTTPException, Request

from app.server.execution import RUN_SECONDS

REQUEST_BYTES = 256 * 1024


async def read_run_payload(request: Request, received_at: float) -> dict:
    """逐块计数并限制接收期限，超出直接拒绝且不解析或执行模型。"""
    body = bytearray()
    try:
        async with asyncio.timeout(max(0, received_at + RUN_SECONDS - time.monotonic())):
            async for chunk in request.stream():
                if len(body) + len(chunk) > REQUEST_BYTES:
                    raise HTTPException(status_code=413, detail="AG-UI 请求超过 256 KiB。")
                body.extend(chunk)
    except TimeoutError:
        raise HTTPException(status_code=408, detail="AG-UI 请求接收超时。") from None
    try:
        payload = json.loads(body)
    except (ValueError, UnicodeDecodeError):
        raise HTTPException(status_code=422, detail="AG-UI 请求必须是 JSON 对象。") from None
    if not isinstance(payload, dict):
        raise HTTPException(status_code=422, detail="AG-UI 请求必须是 JSON 对象。")
    return payload
