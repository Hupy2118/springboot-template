#!/usr/bin/env sh
set -eu
mvn -f template-engine/pom.xml -pl engine-core -am -Pstage2-verification verify
