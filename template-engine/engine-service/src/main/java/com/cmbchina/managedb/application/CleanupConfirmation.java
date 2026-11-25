package com.cmbchina.managedb.application;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 资源清理确认凭据，封装预览指纹与双确认标志，避免 confirm 方法参数过多。
 */
@Getter
@RequiredArgsConstructor
public final class CleanupConfirmation {
    /** 服务端生成的预览指纹，格式为 64 位十六进制 SHA-256。 */
    private final String previewHash;
    /** 调用方是否明确确认执行清理。 */
    private final boolean confirmed;
    /** 调用方是否明确同意数据丢失。 */
    private final boolean allowDataLoss;
}
