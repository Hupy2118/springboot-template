@echo off
set ROOT=%~dp0
call "%ROOT%mvnw.cmd" -q -f "%ROOT%assembly\pom.xml" exec:java -Dexec.mainClass=com.cmbchina.template.backend.maintenance.BackendTemplateCli -Dexec.args="%*"
