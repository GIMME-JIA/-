# 点评项目
## 启动配置
1. 执行sql脚本：hm-dianping/src/main/resources/db/hmdp.sql
2. 配置文件：
- hm-dianping/src/main/resources/application.yaml
  - MySQL数据库的账号密码设置以及Redis的服务器ip、端口、账号密码设置
- hm-dianping/src/main/java/com/hmdp/config/RedissonConfig.java
  - Redis的服务器ip、端口配置
3. 确保在无中文路径下启动nginx

**启动项目，访问localhost:8080，大功告成！**
