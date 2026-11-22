# Penguin suit

Penguin suit 是一款用于连接 Minecraft 与 QQ 群的 Fabric 服务端 mod，基于 **PenguinClient-Fabric** 和 **Chat Remastered**，扩充了 **PenguinClient-Fabric** 的部分功能，提供双向图文转发、跨端 @、跨端消息回复和事件通知等服务。

**这是一款服务端 mod，支持 Minecraft Java 版 26.2，当前版本：`0.9.5+26.2`，需要搭配 Chat Remastered 0.5.1+26.2 和 PenguinClient-Fabric v1.1.7 使用**

Penguin suit 通过运行时 Mixin 接入两个模组。客户端无需安装 Penguin suit 即可使用，但如果你希望在游戏中查看图片、播放 GIF、发送图片或使用回复界面，则需要安装匹配版本的 Chat Remastered

## 功能

| 功能 | 说明 |
| --- | --- |
| 聊天互通 | QQ 群与游戏聊天互通，两个方向可分别设置开关和消息格式 |
| 图片与 GIF 转发 | 将图片、GIF 和 QQ 表情进行转发，并借助 Chat Remastered 在客户端显示出来；支持动画、静态与文字模式 |
| 双向 @ | 转发 QQ 群中的 @，支持在游戏内通过 `/at` 来 @ 别人，支持双向跨端 @ |
| 认证绑定 | 通过三步认证将玩家 UUID 与对应群内的 QQ OpenID 进行绑定 |
| 回复转发 | 使用 Chat Remastered 的 Reply 功能进行消息回复，并同步显示在 QQ 群内，QQ 群内的回复消息也会通过此功能显示到游戏中 |
| 游戏事件 | 转发玩家进退服、取得进度和死亡消息等游戏事件 |
| 假人与机器人过滤 | 可自定义玩家名的前后缀过滤规则，也可自动识别或手动排除有关机器人的信息 |

### 客户端兼容性

| 客户端 | 游戏内表现 |
| --- | --- |
| 安装 Chat Remastered | 可接收图片、GIF 与图片组，并使用其图片发送和回复界面 |
| 未安装 Chat Remastered | 可以加入服务器，接收文字和 @ 提示，但图片将显示为文字占位 |

占位文字由具体消息路径决定，例如 `[图片]`、`[photo]` 或表情名称

## 环境与依赖

| 组件 | 要求 |
| --- | --- |
| Java | 运行要求 **Java 25 或以上** |
| Fabric Loader | **0.19.3 或以上** |
| Fabric API | 适用于 26.2 的 **0.157.0 或以上版本** |
| Fabric Language Kotlin | **1.13.13+kotlin.2.4.10** |
| PenguinClient-Fabric | **1.1.7 的 mc26.2 发布包** |
| Chat Remastered | **0.5.1+26.2** |
| MixinExtras | **0.5.5**，已内置于 Penguin suit |

## 使用方法

### 消息格式

默认格式为：

| 方向 | 模板 | 文字效果示例 |
| --- | --- | --- |
| QQ → 游戏 | `<[QQ群] {name}> {message}` | `<[QQ群] 昵称> 大家好` |
| 游戏 → QQ | `[MC] {name}：{message}` | `[MC] Alex：大家好` |

分别修改配置文件中的 `QQtogame.format` 和 `gametoQQ.format` 即可更换前缀或样式。格式留空时沿用 Penguin 对应设置，图片沿用了 Chat Remastered 的图片显示方式

### 提及成员

使用 `/at` 发送，在后方参数内输入 `@` 后可选择对象，支持多目标

```text
/at @Alex#MC @Steve#QQ 来看一下
```

- `#MC` 表示游戏玩家，`#QQ` 表示 QQ 成员
- 存在重名时，后方会自动添加编号，例如 `@Steve#QQ6A`
- 没有来源后缀的正文 `@名字` 是普通文字，不触发 @ 提示
- 使用 Chat Remastered 发送图片时，正文中也可使用 @
- 有绑定关系的对象可同时在游戏和 QQ 群内被 @，其中映射关系如下

| 对象状态 | 游戏内 | QQ 群 |
| --- | --- | --- |
| 已绑定，游戏在线 | `@游戏ID`，提醒目标玩家 | 有效 QQ @ |
| 已绑定，游戏离线 | `@群昵称` | 有效 QQ @ |
| 未绑定，游戏玩家在线 | `@游戏ID`，提醒目标玩家 | 纯文字 `@游戏ID` |
| 未绑定，游戏玩家离线 | 发送失败 | 不转发 |
| 已记录但未绑定的 QQ 成员 | `@群昵称` | 有效 QQ @ |

@ 提示的颜色、文案和提示音可自定义

成员名单通过玩家入服与发言、QQ 群发言及可用的成员事件动态维护

### 双向身份绑定

1. 在游戏执行 `/qqbind start` 开始绑定流程，开始时会向你发送一条配对码，点击配对码可复制，默认 5 分钟有效
2. 使用自己的 QQ 在对应群发送 `/绑定认证 配对码`，按机器人的接收方式，必要时可先 @机器人
3. 回到游戏内进行核对，确认后再执行 `/qqbind confirm` 完成绑定

如果被别人误领，重新执行 `/qqbind start` 即可作废旧申请并生成新码

### 游戏事件与过滤

转发游戏事件至 QQ 群内：

```text
Alex完成了挑战[探索的时光]
Steve落地过猛
```

默认会过滤以 `bot_` 开头或以 `_fake` 结尾的假人，过滤前后缀可自定义

## 配置与管理

主配置：`config/penguin-suit.jsonc`，完整默认值与中文注释见 [examples/penguin-suit.jsonc](examples/penguin-suit.jsonc)。

| 分类 | 内容 |
| --- | --- |
| `QQtogame` | QQ → 游戏的文字、图片、表情、GIF、@、群事件、下载及队列 |
| `gametoQQ` | 游戏 → QQ 的文字、图片、@、上传与游戏事件 |
| `media` / `cache` | 媒体设置 / 缓存设置 |
| `mentions` / `replies` / `authentication` | @、回复与身份认证设置 |
| `filters` / `directory` | 过滤设置与成员目录设置 |
| `network` / `messages` / `logs` | QQ API 超时、文案模板及日志设置 |

大多数设置修改后执行 `/penguinsuit reload` 即可生效，`QQtogame.queue.workerThreads` 和 `QQtogame.queue.capacity` 需要重启生效。注意：重载会取消待完成认证与未发送的出站任务

常用管理命令（需要服务器管理权限）

| 命令 | 用途 |
| --- | --- |
| `/penguinsuit status` | 查看各模块状态 |
| `/penguinsuit reload` | 重载配置 |
| `/penguinsuit config list` | 列出配置项 |
| `/penguinsuit config get <key>` | 查询配置值 |
| `/penguinsuit config set <key> <value>` | 校验并保存配置，再按配置项要求重载或重启 |
| `/penguinsuit members list [page]` | 查看已记录的成员 |
| `/penguinsuit bot exclude <成员>` | 手动排除目标 |
| `/penguinsuit bot include <成员>` | 撤销手动排除 |
| `/penguinsuit cache preview` | 查看缓存清理预览 |
| `/penguinsuit cache expired confirm` | 清理已过期的转发缓存 |
| `/penguinsuit cache clear confirm` | 清理已过期的的媒体缓存 |

## 缓存与数据

以下路径均相对于服务端运行目录：

| 路径 | 内容 |
| --- | --- |
| `config/penguin-suit.jsonc` | 主配置 |
| `config/penguin-suit/members.json` | 已记录的成员名单 |
| `config/penguin-suit/bindings.json` | 绑定记录 |
| `config/penguin-suit/replies.json` | 回复消息索引 |
| `config/penguin-suit/owned-media.json` | 入站图片索引 |
| `config/penguin-suit/qq-materials.json` | 上传缓存索引 |
| `srvcashe/` | Chat Remastered 服务端媒体文件 |

QQ → 游戏图片缓存默认上限为 **256 MiB、256 张、保留 24 小时**，每 60 秒检查是否过期，投递保护期为 120 秒。达到上限时优先删除最旧且不受保护的缓存，当没有可清理空间时图片会回退为文字

回复索引缓存默认上限为 7 天、20,000 条、32 MiB，与图片缓存分开设置

## 已知 Bug

- **QQ 引用关联：** QQ 机器人接口存在引用关联异常，相关问题见 [Issue #7](https://github.com/tencent-connect/qqbot-nodejs/issues/7)。部分收到的引用索引无法对应到原消息，且可能缺少原发送者信息，因此此类回复会丢失原消息指向

本模组仍存在一些尚未验证的功能，欢迎提交反馈，反馈问题请一并提供服务端日志

## 文档与相关项目

- [配置说明与示例](examples/penguin-suit.jsonc)

上游项目：

- [HuHoBot / PenguinClient-Fabric](https://github.com/HuHoBot/PenguinClient-Fabric)：机器人连接与基础消息转发
- [Errnick-code / Chat_Remastered_MC](https://github.com/Errnick-code/Chat_Remastered_MC)：图片、GIF 与回复的游戏内展示
- [HuHoBot 文档](https://huhobot.txssb.cn/)：机器人及相关功能配置

## 开源许可

AGPL3.0 License
