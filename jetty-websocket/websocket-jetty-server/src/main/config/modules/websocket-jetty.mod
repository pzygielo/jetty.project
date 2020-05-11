DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enable the Jetty WebSocket API for deployed web applications.

[tags]
websocket

[depend]
client
annotations

[lib]
lib/websocket/websocket-core-${jetty.version}.jar
lib/websocket/websocket-util-${jetty.version}.jar
lib/websocket/websocket-util-server-${jetty.version}.jar
lib/websocket/websocket-jetty-api-${jetty.version}.jar
lib/websocket/websocket-jetty-common-${jetty.version}.jar
lib/websocket/websocket-jetty-server-${jetty.version}.jar

[jpms]
# The implementation needs to access method handles in
# classes that are in the web application classloader.
add-reads: org.eclipse.jetty.websocket.jetty.common=ALL-UNNAMED
