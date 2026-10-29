"""验证当前 MySQL 注入及业务实体边界。"""
import pytest
from pydantic import ValidationError
from app.domain.base import BusinessEntity
from app.infrastructure.database import BusinessDatabase
from app.infrastructure.database_settings import DatabaseSettings


def test_business_entity_rejects_unknown():
    """拒绝未声明的业务字段。"""
    class Example(BusinessEntity):
        """当前测试实体。"""
        name: str
    with pytest.raises(ValidationError):
        Example(name="a", unknown=1)


@pytest.mark.asyncio
async def test_disabled_database_is_explicit():
    """无数据源时不能悄悄写入本地 SQLite。"""
    database = BusinessDatabase(DatabaseSettings())
    assert await database.probe() == "not_required"
    with pytest.raises(RuntimeError, match="未启用"):
        async with database.transaction():
            pass
