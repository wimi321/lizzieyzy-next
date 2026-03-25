# 常见问题与排错

## 1. 程序打不开

优先检查三件事：

- 你下载的包是不是和系统对应
- 是否完整解压 / 安装完成
- 是否被系统安全策略拦截

### Windows

- 安装器版：重新运行 `windows64.with-katago.installer.exe`
- NVIDIA 极速版：确认你下载的是 `windows64.nvidia.installer.exe` 或 `windows64.nvidia.portable.zip`
- 便携版：确认你启动的是 `LizzieYzy Next.exe`
- 不要再从旧的 `.bat` 路径排查当前主发布版

### macOS

如果被系统拦住：

1. 先尝试打开一次
2. 打开 `系统设置 -> 隐私与安全性`
3. 点击 `仍要打开`
4. 再次启动

### Linux

优先从终端启动：

```bash
chmod +x start-linux64.sh
./start-linux64.sh
```

这样最容易看到 Java、权限或库依赖报错。

## 2. 第一次启动没有自动配好引擎

新维护版会优先自动检测：

- 内置 KataGo
- 默认权重
- 默认配置文件

如果自动配置失败：

1. 确认你下载的是 `with-katago` 包
2. 确认包内 `weights/default.bin.gz` 仍然存在
3. 确认包内 `engines/katago/` 没被误删
4. 重新启动一次程序

如果还是不行，再进入手工设置。

## 3. 野狐棋谱没有抓到结果

先确认三件事：

- 你输入的 **野狐昵称** 是否正确
- 该账号最近确实有公开棋谱
- 网络没有暂时性异常

注意：

- 现在默认是 **昵称搜索**，程序会自动找到对应账号
- 如果昵称输错，可能会找不到对应账号
- 如果该账号没有最新公开棋谱，返回空结果是正常现象

## 4. 想替换权重文件

可以直接替换默认权重，但请注意文件名和路径。

默认位置：

- Windows / Linux：`Lizzieyzy/weights/default.bin.gz`
- macOS：`LizzieYzy Next.app/Contents/app/weights/default.bin.gz`

替换后如果启动异常，请先恢复原版权重确认是不是新权重本身的问题。

## 5. 想用自己熟悉的引擎，不用内置 KataGo

推荐做法：

- Windows：直接下载 `windows64.without.engine.portable.zip`
- macOS / Linux：继续用当前主推荐包，在软件里把引擎路径改成你自己的 KataGo

如果你只是想换权重，保留内置 KataGo 即可。

## 6. 反馈问题时最好带什么

最有帮助的是这四项：

- 安装包文件名
- 操作系统和版本
- 是否是普通 `with-katago`、`nvidia`，还是 `without.engine`
- 完整报错截图或复现步骤

相关入口：

- [安装指南](INSTALL.md)
- [发布包说明](PACKAGES.md)
- [GitHub Issues](https://github.com/wimi321/lizzieyzy-next/issues)
