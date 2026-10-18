# BTL 读写无头回归测试

不改安卓工程、不需要设备，直接用桌面 JDK 编译 `FileParser` + `model` 跑真实游戏地图，
用来验证「读入 → 保存」是否忠实、以及「改地形后保存」是否只改该改的地方。

## 用法

```bash
# 语料目录里放真实游戏 .btl（例如从游戏 APK 的 assets/stage/ 解出来）
sh tools/btl-regression/run.sh /path/to/gamedata
```

脚本会自动从 `app/src/main/java` 复制源码、补 `android.graphics.Bitmap` / `org.json` 桩类并编译，然后跑三个测试：

| 测试 | 作用 | 期望结果 |
|---|---|---|
| `RT` | 不改任何东西直接保存 | 全部文件与原文件一致（允许的差异只有「海洋格省规划 → 65535」） |
| `Bulk` | 每个文件涂 3 格地形后保存 | 保存异常 0；头部/归属/尾部改动 0；地形段只改被涂格与受影响邻居 |
| `Conq` | 征服地图流程（`conquest{N}.btl` + `world{N}.bin`） | btl 头/地形/归属/尾段零改动；bin 只改被涂格 |

单文件模式：

```bash
sh tools/btl-regression/run.sh --conq 征服/conquest1.btl 游戏/world2.bin
sh tools/btl-regression/run.sh --bin  游戏/world.bin
sh tools/btl-regression/run.sh --binresize 游戏/world.bin
sh tools/btl-regression/run.sh --one  地图/stage10103.btl paint:3,3,1 paint:5,5,2
```

### `--binresize`：世界底图本体扩缩 + 配套征服 BTL

覆盖新增的「整张 world.bin 扩展/收缩」与「由底图生成配套征服 BTL」：

| 断言 | 说明 |
|---|---|
| 尺寸/长度/头部宽高 | YSAE 改 0x8/0xC，裸格式改 0x0/0x4；长度 = 头 + 宽×高×16 + 宽×高×2 |
| 旧地形整体平移 | 逐格比对，应 0 格不符 |
| 新格填充 | 选海洋 → 地形组 1、省规划 0xFFFF |
| 省规划重映射 | 代表格底图绝对格号同步平移，特殊值 0/0xFFFF 原样 |
| 扩缩往返 | 同方向「扩 N → 缩 N」应逐字节还原（地形段 + 省规划段都 0 差异） |
| 配套 BTL | v3、截取 (0,2)、窗口 = 底图宽 × (底图高-4)、省规划全 0xFFFF、归属全 0xFF，且能被重新读回 |

参考：`world.bin`（148×54）→ 生成 `148×50` 的 btl，与官方 `conquest2.btl` 的窗口约定完全一致；
`world2.bin`（148×84）→ `148×80`，与官方 `conquest1.btl` 一致。

## 已知的合法差异

- **海洋格省规划 → 65535**：官方 MapEdit（`Wc4MapBinDAO.checkMapTerrainIds`）与熊编辑器保存时都会强制，本项目跟随。
- **改地形后的邻居格**：`MapData.finishPaint` 会按官方海岸线算法更新被涂格及其六邻居的装饰层，属正常行为。
- 原始文件「地块总数 0x58」为 0 或明显异常（如 `stage10001.btl`）时会补上省规划/归属段，长度会变长。
