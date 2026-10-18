<?php
require __DIR__ . '/config.php';

// 远程拦截设备名单（后台输入设备型号管理）
$blockFile = __DIR__ . '/blocklist.txt';
$blockMsg = '';
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $key = $_POST['key'] ?? '';
    $act = $_POST['action'] ?? '';
    if ($key !== TELEMETRY_SECRET) {
        $blockMsg = '密钥错误，未修改';
    } else {
        $lines = [];
        if (is_file($blockFile)) {
            $lines = file($blockFile, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
        }
        $model = trim($_POST['model'] ?? '');
        if ($act === 'add_block' && $model !== '') {
            $found = false;
            foreach ($lines as $lm) {
                $t = trim($lm);
                if ($t !== '' && strpos($t, '#') !== 0 && strcasecmp($t, $model) === 0) {
                    $found = true;
                }
            }
            if (!$found) $lines[] = $model;
            $blockMsg = '已添加拦截：' . $model;
        } elseif ($act === 'del_block' && $model !== '') {
            $lines = array_values(array_filter($lines, function ($lm) use ($model) {
                return strcasecmp(trim($lm), $model) !== 0;
            }));
            $blockMsg = '已移除拦截：' . $model;
        }
        if ($model !== '' && ($act === 'add_block' || $act === 'del_block')) {
            @file_put_contents($blockFile, implode("\n", $lines) . "\n");
        }
    }
}
$blockedModels = [];
if (is_file($blockFile)) {
    foreach (file($blockFile, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES) as $line) {
        $line = trim($line);
        if ($line !== '' && strpos($line, '#') !== 0) $blockedModels[] = $line;
    }
}
// 停用版本名单管理
$verFile = __DIR__ . '/versions.txt';
$verMsg = '';
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $key = $_POST['key'] ?? '';
    $act = $_POST['action'] ?? '';
    if ($key === TELEMETRY_SECRET && ($act === 'add_ver' || $act === 'del_ver')) {
        $lines = [];
        if (is_file($verFile)) {
            $lines = file($verFile, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
        }
        $ver = trim($_POST['ver'] ?? '');
        if ($ver !== '') {
            if ($act === 'add_ver') {
                $found = false;
                foreach ($lines as $lv) {
                    if (strcasecmp(trim($lv), $ver) === 0) $found = true;
                }
                if (!$found) $lines[] = $ver;
                $verMsg = '已停用版本：' . $ver;
            } else {
                $lines = array_values(array_filter($lines, function ($lv) use ($ver) {
                    return strcasecmp(trim($lv), $ver) !== 0;
                }));
                $verMsg = '已恢复版本：' . $ver;
            }
            @file_put_contents($verFile, implode("\n", $lines) . "\n");
        }
    }
}
$blockedVersions = [];
if (is_file($verFile)) {
    foreach (file($verFile, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES) as $line) {
        $line = trim($line);
        if ($line !== '' && strpos($line, '#') !== 0) $blockedVersions[] = $line;
    }
}
$crashes = [];
if (is_file(__DIR__ . '/crash.log')) {
    $clines = file(__DIR__ . '/crash.log', FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
    $clines = array_reverse($clines);
    $crashes = array_slice($clines, 0, 10);
}

// 清空全部记录（页面里带确认）
if (isset($_GET['action']) && $_GET['action'] === 'clear') {
    @file_put_contents(DATA_FILE, '');
    header('Location: ' . strtok($_SERVER['REQUEST_URI'], '?'));
    exit;
}

$records = [];
if (is_file(DATA_FILE)) {
    $lines = file(DATA_FILE, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
    foreach ($lines as $line) {
        $r = json_decode($line, true);
        if (is_array($r)) $records[] = $r;
    }
}

$total = count($records);
$devices = [];
$today = date('Y-m-d');
$todayCount = 0;
$openCount = 0;
foreach ($records as $r) {
    if (!empty($r['install_id'])) $devices[$r['install_id']] = true;
    if (!empty($r['time']) && strpos($r['time'], $today) === 0) $todayCount++;
    if (($r['event'] ?? '') === 'open') $openCount++;
}
$uniqueDevices = count($devices);
$records = array_reverse($records); // 最新在前
$shown = array_slice($records, 0, PAGE_SIZE);

// CSV 导出（Excel 可直接打开）
if (isset($_GET['export']) && $_GET['export'] === 'csv') {
    header('Content-Type: text/csv; charset=utf-8');
    header('Content-Disposition: attachment; filename="telemetry_' . date('Ymd_His') . '.csv"');
    $out = fopen('php://output', 'w');
    fwrite($out, "\xEF\xBB\xBF");
    fputcsv($out, ['时间', '事件', '品牌', '型号', '系统', 'SDK', '版本', '安装ID', 'IP']);
    foreach (array_reverse($records) as $r) {
        fputcsv($out, [
            $r['time'] ?? '', $r['event'] ?? '', $r['brand'] ?? '', $r['model'] ?? '',
            $r['android'] ?? '', $r['sdk'] ?? '', $r['app_version'] ?? '',
            $r['install_id'] ?? '', $r['ip'] ?? ''
        ]);
    }
    fclose($out);
    exit;
}
?>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>设备使用统计</title>
<style>
* { box-sizing: border-box; }
body { font-family: system-ui, "PingFang SC", "Microsoft YaHei", sans-serif; background: #0f172a; color: #e2e8f0; margin: 0; padding: 20px; }
.wrap { max-width: 1100px; margin: 0 auto; }
h1 { font-size: 20px; margin: 0 0 16px; }
.cards { display: flex; gap: 12px; flex-wrap: wrap; margin-bottom: 18px; }
.card { flex: 1; min-width: 140px; background: #1e293b; border: 1px solid #334155; border-radius: 10px; padding: 14px 16px; }
.card .num { font-size: 26px; font-weight: 700; color: #60a5fa; }
.card .lab { font-size: 12px; color: #94a3b8; margin-top: 2px; }
.tools { margin-bottom: 12px; display: flex; gap: 10px; }
.tools a, .tools button { color: #e2e8f0; background: #1e293b; border: 1px solid #334155; border-radius: 8px; padding: 7px 14px; text-decoration: none; font-size: 13px; cursor: pointer; }
.tools .danger { color: #fca5a5; border-color: #7f1d1d; }
.block-card { background: #1e293b; border: 1px solid #334155; border-radius: 10px; padding: 16px 18px; margin-bottom: 18px; }
.block-card h2 { font-size: 16px; margin: 0 0 6px; }
.block-card .muted, .msg, .block-list .muted { color: #94a3b8; font-size: 12.5px; margin: 4px 0; }
.block-card .msg { color: #86efac; }
.block-form { display: flex; gap: 8px; margin: 10px 0; flex-wrap: wrap; }
.block-form input[type=text] { flex: 1; min-width: 180px; background: #0f172a; border: 1px solid #334155; color: #e2e8f0; border-radius: 8px; padding: 8px 10px; font-size: 13px; }
.block-form button, .block-list button { color: #e2e8f0; background: #334155; border: 1px solid #475569; border-radius: 8px; padding: 8px 14px; font-size: 13px; cursor: pointer; }
.block-form button:hover { background: #475569; }
.block-list { list-style: none; padding: 0; margin: 8px 0 0; }
.block-list li { display: flex; align-items: center; justify-content: space-between; background: #0f172a; border: 1px solid #334155; border-radius: 8px; padding: 8px 12px; margin-bottom: 6px; font-size: 13px; }
.block-list button.danger { color: #fca5a5; border-color: #7f1d1d; background: transparent; }
.crash-item { background: #0f172a; border: 1px solid #7f1d1d; border-radius: 8px; padding: 8px 12px; margin-bottom: 6px; font-size: 12.5px; }
.crash-item .muted { color: #94a3b8; }
.crash-item pre { white-space: pre-wrap; word-break: break-all; font-size: 11px; color: #fca5a5; margin-top: 6px; max-height: 220px; overflow: auto; }
table { width: 100%; border-collapse: collapse; background: #1e293b; border-radius: 10px; overflow: hidden; font-size: 13px; }
th, td { text-align: left; padding: 9px 10px; border-bottom: 1px solid #334155; white-space: nowrap; }
th { background: #243349; color: #93c5fd; font-weight: 600; }
tr:hover td { background: #26354a; }
.ev { display: inline-block; padding: 2px 8px; border-radius: 20px; font-size: 12px; }
.ev.open { background: #14532d; color: #86efac; }
.ev.heartbeat { background: #1e3a8a; color: #93c5fd; }
.empty { text-align: center; color: #64748b; padding: 40px; }
</style>
</head>
<body>
<div class="wrap">
  <h1>📊 设备使用统计</h1>
  <div class="cards">
    <div class="card"><div class="num"><?php echo $total; ?></div><div class="lab">总记录数</div></div>
    <div class="card"><div class="num"><?php echo $uniqueDevices; ?></div><div class="lab">设备数（按安装ID）</div></div>
    <div class="card"><div class="num"><?php echo $todayCount; ?></div><div class="lab">今日记录</div></div>
    <div class="card"><div class="num"><?php echo $openCount; ?></div><div class="lab">累计打开次数</div></div>
  </div>
  <div class="tools">
    <a href="?export=csv">导出 CSV</a>
    <button class="danger" onclick="if(confirm('确定清空全部记录？')) location.href='?action=clear'">清空记录</button>
  </div>
  <div class="block-card">
    <h2>远程拦截设备</h2>
    <p class="muted">输入设备型号（Build.MODEL）即可远程拦截：App 下次打开或正在使用时会被静默退出，断网也拦（名单已缓存在设备本地）。</p>
    <form method="post" class="block-form">
      <input type="hidden" name="action" value="add_block">
      <input type="hidden" name="key" value="<?php echo htmlspecialchars(TELEMETRY_SECRET); ?>">
      <input type="text" name="model" placeholder="输入设备型号，例如 25113PN0EC" required>
      <button type="submit">添加拦截</button>
    </form>
    <?php if ($blockMsg !== ''): ?><p class="msg"><?php echo htmlspecialchars($blockMsg); ?></p><?php endif; ?>
    <ul class="block-list">
      <?php if (empty($blockedModels)): ?>
        <li class="muted">暂无拦截设备</li>
      <?php else: ?>
        <?php foreach ($blockedModels as $bm): ?>
        <li>
          <span><?php echo htmlspecialchars($bm); ?></span>
          <form method="post" style="display:inline">
            <input type="hidden" name="action" value="del_block">
            <input type="hidden" name="key" value="<?php echo htmlspecialchars(TELEMETRY_SECRET); ?>">
            <input type="hidden" name="model" value="<?php echo htmlspecialchars($bm); ?>">
            <button type="submit" class="danger">移除</button>
          </form>
        </li>
        <?php endforeach; ?>
      <?php endif; ?>
    </ul>
  </div>
  <div class="block-card">
    <h2>停用版本（禁止进入 + 引导更新）</h2>
    <p class="muted">输入版本标识（versionName 或 应用名），命中的 App 打开时禁止进入并弹公告引导下载新版。可同时维护多条。</p>
    <form method="post" class="block-form">
      <input type="hidden" name="action" value="add_ver">
      <input type="hidden" name="key" value="<?php echo htmlspecialchars(TELEMETRY_SECRET); ?>">
      <input type="text" name="ver" placeholder="例如 1.3 或 v1.5内测版" required>
      <button type="submit">停用此版本</button>
    </form>
    <?php if ($verMsg !== ''): ?><p class="msg"><?php echo htmlspecialchars($verMsg); ?></p><?php endif; ?>
    <ul class="block-list">
      <?php if (empty($blockedVersions)): ?>
        <li class="muted">暂无停用版本</li>
      <?php else: ?>
        <?php foreach ($blockedVersions as $bv): ?>
        <li>
          <span><?php echo htmlspecialchars($bv); ?></span>
          <form method="post" style="display:inline">
            <input type="hidden" name="action" value="del_ver">
            <input type="hidden" name="key" value="<?php echo htmlspecialchars(TELEMETRY_SECRET); ?>">
            <input type="hidden" name="ver" value="<?php echo htmlspecialchars($bv); ?>">
            <button type="submit" class="danger">恢复</button>
          </form>
        </li>
        <?php endforeach; ?>
      <?php endif; ?>
    </ul>
  </div>
  <div class="block-card">
    <h2>崩溃日志（最近 10 条）</h2>
    <?php if (empty($crashes)): ?>
      <p class="muted">暂无崩溃记录。装新版 App 后如果还有闪退，会自动上报到这里。</p>
    <?php else: ?>
      <?php foreach ($crashes as $cline): $c = json_decode($cline, true); if (!is_array($c)) continue; ?>
      <div class="crash-item">
        <div><b><?php echo htmlspecialchars($c['time'] ?? ''); ?></b> · <?php echo htmlspecialchars($c['model'] ?? ''); ?> · <?php echo htmlspecialchars($c['android'] ?? ''); ?> · <?php echo htmlspecialchars($c['version'] ?? ''); ?></div>
        <details>
          <summary style="font-size:12px">查看堆栈</summary>
          <pre><?php echo htmlspecialchars($c['msg'] ?? ''); ?></pre>
        </details>
      </div>
      <?php endforeach; ?>
    <?php endif; ?>
  </div>
  <?php if (empty($shown)): ?>
    <div class="empty">还没有数据。App 打开后会向上报地址发送设备信息。</div>
  <?php else: ?>
    <table>
      <tr><th>时间</th><th>事件</th><th>设备</th><th>系统</th><th>App版本</th><th>IP</th><th>安装ID</th></tr>
      <?php foreach ($shown as $r): ?>
      <tr>
        <td><?php echo htmlspecialchars($r['time'] ?? ''); ?></td>
        <td><span class="ev <?php echo htmlspecialchars($r['event'] ?? ''); ?>"><?php echo htmlspecialchars($r['event'] ?? ''); ?></span></td>
        <td><?php echo htmlspecialchars(($r['brand'] ?? '') . ' ' . ($r['model'] ?? '')); ?></td>
        <td><?php echo 'Android ' . htmlspecialchars($r['android'] ?? '') . ' (SDK ' . htmlspecialchars((string)($r['sdk'] ?? '')) . ')'; ?></td>
        <td><?php echo htmlspecialchars(($r['app_label'] ?? '') . ' ' . ($r['app_version'] ?? '')); ?></td>
        <td><?php echo htmlspecialchars($r['ip'] ?? ''); ?></td>
        <td title="同一安装ID视为同一设备"><?php echo htmlspecialchars(substr($r['install_id'] ?? '', 0, 8)); ?></td>
      </tr>
      <?php endforeach; ?>
    </table>
    <p style="color:#64748b;font-size:12px;margin-top:10px;">
      共 <?php echo $total; ?> 条，仅显示最近 <?php echo PAGE_SIZE; ?> 条。导出 CSV 可看全部。
    </p>
  <?php endif; ?>
</div>
</body>
</html>
