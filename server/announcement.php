<?php
// 远程公告：App 启动时拉取，有新公告就弹窗显示
// 公告内容在 announcement.txt 里改：第一行=标题，之后每行=内容
header('Content-Type: application/json; charset=utf-8');

$file = __DIR__ . '/announcement.txt';
$title = '';
$content = '';
if (is_file($file)) {
    $lines = file($file, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
    if (count($lines) > 0) {
        $title = trim($lines[0]);
    }
    if (count($lines) > 1) {
        $content = trim(implode("\n", array_slice($lines, 1)));
    }
}

// 停用版本名单（versions.txt）：命中的版本禁止进入，弹公告引导下载新版
$vfile = __DIR__ . '/versions.txt';
$blocked = [];
if (is_file($vfile)) {
    foreach (file($vfile, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES) as $line) {
        $line = trim($line);
        if ($line !== '' && strpos($line, '#') !== 0) $blocked[] = $line;
    }
}

echo json_encode([
    'ok' => true,
    'title' => $title,
    'content' => $content,
    'hash' => md5($title . "\n" . $content),
    'blocked_versions' => $blocked,
], JSON_UNESCAPED_UNICODE);
