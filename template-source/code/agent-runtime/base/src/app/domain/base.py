"""业务领域对象的严格输入边界。"""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict


class BusinessEntity(BaseModel):
    """为后续生成的业务 Entity 提供拒绝未声明字段的基础模型。"""

    model_config = ConfigDict(extra="forbid", validate_assignment=True)
