# Java File Manager

一个基于 Java 17 的轻量文件管理系统，使用 JDK 自带 `HttpServer` 实现，无需 Spring Boot 或第三方依赖。

## 功能

- 用户注册、登录、退出
- 管理员查看用户、启用/禁用用户、调整用户角色
- 文件上传、下载
- 创建文件夹、按文件夹分类浏览
- 将文件移动到指定文件夹
- 分享链接生成与访问
- 文件预览：
  - 图片：浏览器内联预览
  - 视频：HTML5 视频播放器
  - 文本文件：安全转义后预览
  - Word `.docx`：提取正文 XML 文本预览
  - Excel `.xlsx`：提取工作表 XML 表格预览
  - 其他 Office/二进制文件：提供文件信息与下载入口

## 运行

```powershell
javac -encoding UTF-8 -d out src/main/java/com/example/filemanager/FileManagerApplication.java
java -cp out com.example.filemanager.FileManagerApplication
```

打开：

```text
http://localhost:8080
```

默认管理员：

```text
用户名：admin
密码：admin123
```

首次运行会在项目目录下创建：

- `data/users.tsv`
- `data/files.tsv`
- `data/shares.tsv`
- `storage/`

## 说明

`.docx` 和 `.xlsx` 预览通过解析 Office Open XML 包中的 XML 内容实现，适合基础预览；如果需要高保真分页、批注、复杂样式、旧版 `.doc`/`.xls` 预览，建议后续集成 LibreOffice 转 PDF、OnlyOffice 或 WOPI。
