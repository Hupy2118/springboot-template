"""独立业务 MySQL 事务；checkpoint 仍由 persistence 模块管理。"""
from __future__ import annotations

import asyncio
import ssl
from contextlib import asynccontextmanager

import pymysql

from app.infrastructure.database_settings import DatabaseSettings


async def _io(function, *args):
    """在线程中执行阻塞驱动调用，取消时等驱动退出后才关闭连接。"""
    pending = asyncio.create_task(asyncio.to_thread(function, *args))
    try:
        return await asyncio.shield(pending)
    except asyncio.CancelledError:
        try:
            await pending
        finally:
            raise


class AsyncCursor:
    """为生成的 Repository 提供最小异步游标合同。"""
    def __init__(self, cursor):
        """保存当前事务拥有的游标。"""
        self._cursor = cursor

    @property
    def lastrowid(self):
        """返回驱动确认的插入身份。"""
        return self._cursor.lastrowid

    @property
    def rowcount(self):
        """返回本次语句实际影响行数。"""
        return self._cursor.rowcount

    async def fetchone(self):
        """读取一条字典记录。"""
        return await _io(self._cursor.fetchone)

    async def fetchall(self):
        """读取本次游标剩余记录。"""
        return await _io(self._cursor.fetchall)


class BusinessTransaction:
    """限制业务语句为参数化 DML/查询，不提供建表接口。"""
    def __init__(self, connection):
        """记录当前连接和待清理游标。"""
        self.connection = connection
        self.cursors = []

    async def execute(self, sql: str, parameters=()):
        """使用 PyMySQL 的 %s 参数绑定执行单条业务语句。"""
        if not isinstance(sql, str) or not sql.strip() or sql.lstrip().split()[0].upper() not in {"SELECT", "INSERT", "UPDATE", "DELETE", "WITH"}:
            raise ValueError("业务事务只允许查询和 DML；表结构由平台实体流程管理。")
        cursor = self.connection.cursor()
        self.cursors.append(cursor)
        await _io(cursor.execute, sql, parameters)
        return AsyncCursor(cursor)


class BusinessDatabase:
    """按独立连接执行事务，不在启动时创建表、迁移或 seed。"""
    def __init__(self, settings: DatabaseSettings):
        """记录当前进程注入的数据库设置。"""
        self.settings = settings

    def _connect(self):
        """建立连接并核对真实 TLS，不接受驱动静默降级。"""
        connection = pymysql.connect(**self.settings.connect_options(), cursorclass=pymysql.cursors.DictCursor)
        if self.settings.ssl_mode != "DISABLED" and not isinstance(connection._sock, ssl.SSLSocket):
            connection.close()
            raise RuntimeError("数据库 TLS 未建立；已拒绝降级为明文连接。")
        return connection

    @asynccontextmanager
    async def transaction(self):
        """异常含取消时回滚，成功提交并关闭当前连接。"""
        connection = await _io(self._connect)
        transaction = BusinessTransaction(connection)
        try:
            await _io(connection.begin)
            yield transaction
            await _io(connection.commit)
        except BaseException:
            await _io(connection.rollback)
            raise
        finally:
            for cursor in transaction.cursors:
                await _io(cursor.close)
            await _io(connection.close)

    async def probe(self):
        """只读探测实际数据库连接，不修改业务数据。"""
        if not self.settings.enabled:
            return "not_required"
        async with self.transaction() as transaction:
            cursor = await transaction.execute("SELECT 1 AS ready")
            row = await cursor.fetchone()
            if row != {"ready": 1}:
                raise RuntimeError("数据库探测未返回就绪结果。")
        return "ready"
