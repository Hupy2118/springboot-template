"""浏览器声明的有界页面上下文，与可信身份保持独立。"""
import json
from pydantic import BaseModel, ConfigDict, Field, StrictStr, model_validator


class PageContextItem(BaseModel):
    """只接受前端合同中的展示上下文项，不接受身份或权限字段。"""

    model_config = ConfigDict(extra="forbid", frozen=True)
    id: StrictStr = Field(min_length=1, max_length=128)
    label: StrictStr = Field(min_length=1, max_length=256)
    value: StrictStr = Field(max_length=4096)


class PageContext(BaseModel):
    """保留页面和 action 身份作为非可信业务输入，不能用于授权判定。"""

    model_config = ConfigDict(populate_by_name=True, extra="forbid", frozen=True)
    page_id: StrictStr = Field(alias="pageId", min_length=1, max_length=128)
    action_id: StrictStr = Field(alias="actionId", min_length=1, max_length=128)
    context_items: list[PageContextItem] = Field(alias="contextItems", max_length=20)

    @model_validator(mode="after")
    def validate_total_size(self):
        """限制上下文总量和重复标识，防止无界输入进入模型。"""
        if len({item.id for item in self.context_items}) != len(self.context_items):
            raise ValueError("页面上下文标识不能重复。")
        if len(json.dumps(self.model_dump(by_alias=True), ensure_ascii=False)) > 16384:
            raise ValueError("页面上下文超过大小上限。")
        return self
