# DO NOT EDIT THIS FILE - See: https://eclipse.dev/jetty/documentation/

[description]
Demo Proxy Webapp

[environment]
ee8

[tags]
demo
webapp

[depends]
ee8-deploy

[files]
basehome:modules/demo.d/ee8-demo-proxy.properties|webapps/ee8-demo-proxy.properties
maven://org.eclipse.jetty.ee8.demos/jetty-ee8-demo-proxy-webapp/${jetty.version}/war|webapps/ee8-demo-proxy.war
