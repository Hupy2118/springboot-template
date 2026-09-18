# 如果是非国产化，使用以下nginx镜像
# FROM csbase.registry.cmbchina.cn/paas/cmb-nginx-1.22:x86-latest

# 如果是国产化，使用以下nginx镜像
FROM csbase.registry.cmbchina.cn/paas/cmb-nginx-1.22:c86-latest

# 如果部署区域未开启HTTP2，可通过使用以下Nginx镜像合并静态资源请求
# FROM csbase.registry.cmbchina.cn/tool/nginx-with-http-concat:v1.20.1

# 拷贝应用根目录
COPY ./dist  /opt/public

EXPOSE 8080

# 启动nginx
CMD ["/usr/cs/start-nginx.sh"]
# 