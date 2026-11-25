package com.cmbchina.template.application;

import com.cmbchina.template.core.v3.V3UpdateResult;

import java.util.Map;

public interface TemplatePackageWriter {
    byte[] generatedProject(Map<String, byte[]> project, Map<String, Object> state);

    byte[] updatePackage(V3UpdateResult result);

    byte[] updatePackageV4(V3UpdateResult result);
}
