package com.cmbchina.managedb.application;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.application.query.GetDatasourceResourceQuery;
import com.cmbchina.managedb.application.view.DatasourceResourceView;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.repository.DatasourceResourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 查询原注册信息，不生成新编码或重新分配挂载。
 */
@Service
@RequiredArgsConstructor
public final class DatasourceResourceQueryService implements DatasourceResourceQueries {
    private final DatasourceResourceRepository repository;

    /**
     * 恢复原状态并投影，未接用户系统时不执行虚构归属校验。
     */
    @Override
    public DatasourceResourceView get(GetDatasourceResourceQuery query) {
        return new DatasourceResourceView(
                repository.findByDatasourceKey(query.getDatasourceKey()).orElseThrow(() -> new BizException(ResourceErrorCode.RESOURCE_NOT_FOUND)));
    }
}
