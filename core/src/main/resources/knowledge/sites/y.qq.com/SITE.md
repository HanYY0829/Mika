---
name: y.qq.com
display: QQ音乐
description: QQ音乐网页版,提供歌曲、歌手、歌单、MV与排行榜
domains: ["y.qq.com"]
aliases: ["QQ音乐", "QQ Music", "qq音乐"]
apps: ["qqmusic"]
open: y.qq.com
mode: desktop
---
## 是什么
QQ音乐网站提供音乐馆、我的音乐、歌手、新碟、排行榜、分类歌单、MV 和数字专辑等内容。

## 页面与网址
- 首页:`https://y.qq.com/`。
- 排行榜详情:`https://y.qq.com/n/ryqq/toplist/<榜单数字id>`;例如 `62` 是飙升榜,`27` 是新歌榜。
- 歌手页:`https://y.qq.com/n/ryqq/singer/<歌手mid>`。
- 分类歌单:`https://y.qq.com/n/ryqq/category`。
- MV:`https://y.qq.com/n/ryqq/mvList`;MV 榜为 `https://y.qq.com/n/ryqq/mv_toplist`。

## 功能
- **排行榜**:公开榜单页可见榜单名、更新时间、歌曲数量、排名、歌名和歌手;部分榜单还显示上升或热度变化。
- **歌手与歌单**:歌手页集中展示该歌手的歌曲等信息,分类歌单按主题浏览歌单。
- **播放与会员内容**:歌曲能否完整播放受版权、登录和会员状态影响,页面可见 VIP 等标识。

## 已知现象
页面大量内容由动态加载产生,初次读取可能只有加载中、登录或会员入口,榜单文字尚未出现。榜单详情中的数字 id 是页面地址的一部分,不是排名。
