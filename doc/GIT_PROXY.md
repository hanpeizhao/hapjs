# Git 走系统代理推送 GitHub 的原理说明

> 本文档说明为什么 `git push` 会间歇性失败（`Connection timed out` /
> `Connection was reset`），以及 `git -c http.proxy=...` 这条临时配置命令的
> 工作原理与可选的永久配置方式。

## 1. 现象

- 浏览器访问 GitHub 一切正常，但 `git push` / `git clone` 时好时坏，
  失败时典型报错：
  - `fatal: unable to access 'https://github.com/...': Connection timed out`
  - `fatal: unable to access 'https://github.com/...': Recv failure: Connection was reset`

## 2. 前置概念：代理是什么、127.0.0.1:7890 从哪来

**代理 = 中间转发的跑腿程序。** 你把本想直接发给 GitHub 的请求交给它，
它替你重新发出去，收到响应后再转回给你。对 GitHub 来说，访问者变成了
代理的出口服务器而不是你的真实 IP；直连链路上的干扰也看不到你和代理
之间加密过的内容。

**127.0.0.1 = 回环地址（loopback）。** 一个永远指向"本机自己"的特殊 IP，
发往它的数据包不经过网卡和网线，只在本机内部流转。所以
`127.0.0.1:7890` 的含义是：**找本电脑上占用 7890 号"门"的那个程序**，
而不是网上某台服务器。

**7890 = 端口号 = 门牌号。** 一台电脑同时跑着几十个联网程序，靠端口
区分"这个响应是给谁的"。每个联网程序启动时占住一个端口；代理客户端
（Clash 系）默认监听 7890，v2rayN 默认 10809——这只是软件作者的默认
选择，理论上可以是任何空闲端口。

完整链路（4 步）：

1. git 把请求塞进本机 7890 门（Clash 从门里取出）；
2. Clash 经加密隧道把请求交给境外出口服务器；
3. 出口服务器访问 GitHub（GitHub 看到的访问者是出口服务器）；
4. 响应沿原路送回 git。

git 配置里的 `http.proxy` 做的事只有一件：告诉 git"别直连，把请求
塞进这扇门"。

## 3. 原理拆解

### 3.1 为什么浏览器能访问而 git 不能

Windows 的"系统代理"是保存在注册表里的（`Internet Settings` 键）：

```
HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings
    ProxyEnable = 1                    （代理开关）
    ProxyServer = 127.0.0.1:7890       （代理地址：本机代理客户端监听的端口）
```

- **浏览器**（Edge/Chrome 等）读取这份注册表，所以所有流量先发给本机
  `127.0.0.1:7890`，由代理客户端（Clash / v2rayN 等）转发出境；
- **git 不读取 Windows 系统代理**。git 的 HTTPS 传输由 libcurl 实现，
  libcurl 默认只认环境变量（`http_proxy` / `https_proxy`），不会去查
  IE/系统代理设置。

于是出现"浏览器正常、git 直连"的错位：git 直连 github.com，而直连链路
受网络环境影响（丢包/被重置），表现为间歇性超时或 reset。

### 3.2 `git -c http.proxy=...` 做了什么

```powershell
git -c http.proxy=http://127.0.0.1:7890 push origin main
```

`-c key=value` 是 git 的**命令行级配置注入**：仅对本次命令生效，优先级
高于环境变量和所有配置文件（~/.gitconfig、仓库 .git/config），命令结束
即失效，**不会写入任何配置文件**。

`http.proxy` 指定后，git 的 HTTPS 流量改为先发往 `127.0.0.1:7890` 的
本地代理客户端，由它转发到 github.com——与浏览器同一条出口链路，
因此恢复可用。

### 3.3 配置优先级（从高到低）

1. `git -c` 命令行参数
2. 环境变量 `HTTPS_PROXY` / `HTTP_PROXY`
3. 仓库 `.git/config`（`git config --local`）
4. 用户级 `~/.gitconfig`（`git config --global`）
5. 系统级 `/etc/gitconfig`

## 4. 三种使用方式

| 方式 | 命令 | 适用场景 |
|---|---|---|
| 临时（本次命令） | `git -c http.proxy=http://127.0.0.1:7890 push` | 偶尔推送，不想留配置 |
| 会话级（当前终端） | `$env:HTTPS_PROXY="http://127.0.0.1:7890"` 后正常用 git | 批量 git 操作的临时窗口 |
| 永久（推荐） | 见下方 | 日常开发 |

**推荐的永久配置**——只让 `github.com` 走代理，其他 git 源（公司
GitLab、gitee 等）不受影响：

```powershell
git config --global http.https://github.com.proxy http://127.0.0.1:7890
```

取消配置（代理客户端没开时，不要留着这条，否则所有 GitHub 请求都会失败）：

```powershell
git config --global --unset http.https://github.com.proxy
```

## 5. 注意事项

- 代理地址/端口以本机代理客户端实际监听为准（Clash 默认 `7890`，
  v2rayN 默认 `10809`）；确认方式：设置 → 网络和 Internet → 代理，
  或 `reg query "HKCU\...\Internet Settings" /v ProxyServer`。
- **代理客户端必须处于运行状态**，否则配了代理的 git 请求会直接
  `Failed to connect to 127.0.0.1`。
- SSH 协议（`git@github.com:...`）不走 `http.proxy`，需另行配置
  `~/.ssh/config` 的 `ProxyCommand`；本项目远程是 HTTPS，不涉及。
- 验证代理是否生效：
  `git config --get http.https://github.com.proxy`（查配置），
  `curl -x http://127.0.0.1:7890 -I https://github.com`（测链路）。
