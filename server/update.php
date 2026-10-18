<?php
// App 检查更新：读取 apk/latest.json（由 apk_upload.php 上传时写入），无需令牌
header('Content-Type: application/json; charset=utf-8');

$dir = __DIR__ . '/apk';
$file = $dir . '/latest.json';
if (!is_file($file)) {
    echo json_encode(['ok' => false, 'error' => '还没有发布过 APK'], JSON_UNESCAPED_UNICODE);
    exit;
}
$info = json_decode(file_get_contents($file), true);
if (!is_array($info)) {
    echo json_encode(['ok' => false, 'error' => 'latest.json 格式错误'], JSON_UNESCAPED_UNICODE);
    exit;
}
// 老文件没有 url 时补上
if (empty($info['url']) && !empty($info['file'])) {
    $base = 'https://' . ($_SERVER['HTTP_HOST'] ?? '')
        . str_replace('/update.php', '', $_SERVER['SCRIPT_NAME']);
    $info['url'] = $base . '/apk/' . $info['file'];
}
$info['ok'] = true;
echo json_encode($info, JSON_UNESCAPED_UNICODE);
