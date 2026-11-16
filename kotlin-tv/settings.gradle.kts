pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "HongguoTVNative"
include(":core", ":app")
// app-phone 是本地实验模块，永不入库（见 .gitignore:42）。
// 用条件包含保持 settings 与仓库内容一致：本地有目录才参与构建，CI / 新克隆自动跳过。
if (file("app-phone").exists()) include(":app-phone")
