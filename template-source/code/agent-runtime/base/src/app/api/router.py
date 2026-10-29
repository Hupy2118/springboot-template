"""自动发现生成的业务 Endpoint 路由，避免多个 Unit 争写公共组合点。"""

from __future__ import annotations

from fastapi import APIRouter, Depends
from importlib import import_module
from pkgutil import iter_modules

from app.api.security import trusted_business_context


# 生成器在此 include_router；基础设施统一验证可信 Principal。
business_router = APIRouter(dependencies=[Depends(trusted_business_context)])


def include_generated_business_routers() -> None:
    """按模块名稳定加载生成的 Endpoint，每个模块只拥有自己的 router。"""

    package = import_module("app.api.endpoints")
    for module in sorted(iter_modules(package.__path__), key=lambda item: item.name):
        loaded = import_module(f"app.api.endpoints.{module.name}")
        router = getattr(loaded, "router", None)
        if not isinstance(router, APIRouter):
            raise RuntimeError(f"业务 Endpoint 模块 {module.name} 缺少 APIRouter router。")
        business_router.include_router(router)


include_generated_business_routers()
