<?php
// 远程拦截设备名单：App 每次启动拉取并缓存到本地（断网也生效）
// 名单在 blocklist.txt 里维护，每行一个设备型号（Build.MODEL），# 开头为注释
header('Content-Type: application/json; charset=utf-8');

$file = __DIR__ . '/blocklist.txt';
$models = [];
if (is_file($file)) {
    foreach (file($file, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES) as $line) {
        $line = trim($line);
        if ($line === '' || strpos($line, '#') === 0) continue;
        $models[] = $line;
    }
}

echo json_encode(['ok' => true, 'models' => $models], JSON_UNESCAPED_UNICODE);
