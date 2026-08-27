package com.cmbchina.backend.auth.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 要求当前登录人至少拥有一个指定资源点。
 *
 * <p>可标注在 Controller 类或接口方法上；方法注解优先于类注解。</p>
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface RequireAnyResource {

    String[] value();
}
