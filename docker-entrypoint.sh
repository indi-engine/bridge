#!/bin/bash
set -exu -o pipefail

ls -la
# -Dlogback.configurationFile tells logback exactly where to find its config
# inside the shaded JAR the file is on the classpath as logback.xml by default,
# but passing it explicitly avoids any classpath ordering surprises.
java -Dlogback.configurationFile=/app/logback.xml -Dtopic.prefix=mysql -Ddebug=false -jar bridge.jar
