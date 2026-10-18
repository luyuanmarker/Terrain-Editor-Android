# 设备使用统计（宝塔面板部署）

给「地形编辑器」App 加的设备使用上报：谁、什么设备、什么时间打开了 App。

## 一、部署到宝塔

1. 宝塔里新建一个网站（或使用已有站点），PHP 版本选 7.0 以上即可。
2. 把本目录（`config.php`、`collect.php`、`index.php`）上传到网站根目录下的 `telemetry` 文件夹，
   例如：`/www/wwwroot/你的域名/telemetry/`。
3. 浏览器打开 `https://你的域名/telemetry/index.php` 就能看到统计页面。
4. 无需数据库，记录自动写入同目录的 `data.txt`（宝塔默认 www 用户可写，无需额外设置）。

## 二、App 端配置

打开工程 `app/src/main/java/com/xckeji/bj/MainActivity.java`，改顶部两行：

```java
private static final String TELEMETRY_URL = "https://你的域名/telemetry/collect.php";
private static final String TELEMETRY_SECRET = "wc4_editor_2026";
```

`TELEMETRY_SECRET` 必须和服务器 `telemetry/config.php` 里的 `TELEMETRY_SECRET` 一致，防止别人乱上报。
改完重新打包 APK 安装即可。

## 三、上报内容与时机

- App 每次打开：上报一次 `open`（设备品牌/型号、Android 版本、App 版本、安装ID、IP、时间）。
- App 使用中：每 10 分钟上报一次 `heartbeat`，可以看到"正在使用"。
- 同一台设备用同一 `install_id` 区分；App 被系统杀掉后无法上报（安卓后台限制）。
- 时间以服务器时间为准，记录在服务器，改不了也删不了（除非清空）。

## 四、安全建议

- 用 HTTPS，别用裸 IP+HTTP。
- 在宝塔给 `telemetry` 目录开启「目录保护/密码访问」，这样统计页只有你能看。
- 把 `config.php` 和 App 里的密钥改成你自己的随机字符串。

## 五、远程公告

目录里还有 `announcement.php` + `announcement.txt`（远程公告功能）：

- 公告内容在 `announcement.txt` 里改：第一行是标题，后面的行是正文，改完保存即可，App 下次打开会弹窗显示。
- 想停用公告，把 `announcement.txt` 清空或删除即可。
- 两个文件也要一起上传到服务器的 `telemetry` 文件夹。

## 六、远程拦截设备

新增 `blocklist.php` + `blocklist.txt`（远程拦截名单）：

- 在统计页（`index.php`）底部"远程拦截设备"框里输入设备型号（Build.MODEL），点添加即可；
  也可以直接改 `blocklist.txt`，每行一个型号，`#` 开头是注释。
- App 每次打开会拉取名单并缓存到本地，之后即使断网，被拦截的设备也会在打开时静默退出，
  不进入编辑器、没有任何提示。
- 使用中也会检查一次：如果正在用的时候被加入名单，会直接退出。
- 上传时记得把 `blocklist.php` 和 `blocklist.txt` 一起传到服务器的 `telemetry` 文件夹。
