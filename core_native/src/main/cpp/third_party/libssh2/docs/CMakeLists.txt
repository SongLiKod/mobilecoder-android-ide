# 精简占位：本仓库裁剪第三方源码时移除了 libssh2 的 man 手册（docs/*.xml），
# 而上游 CMakeLists.txt 在第 469 行无条件 add_subdirectory(docs)。
# Android APK 不需要生成/安装 man 手册，故此处定义空工程以使配置通过。
# 若日后完整引入上游 docs/ 目录，本文件会被上游版本覆盖，行为自动恢复正常。
