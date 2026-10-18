<?php
// APK 自动投递接口：编译后由 publish_apk.sh 用令牌 POST 上来，覆盖式保存
// 部署位置建议：网站根目录下 apk_upload.php，访问 https://你的域名/apk_upload.php

define('APK_TOKEN', 'wc4_apk_2026');          // 令牌，改成你自己的字符串
define('APK_DIR', __DIR__ . '/apk');          // 存放目录，需可写
define('MAX_APK_SIZE', 200 * 1024 * 1024);    // 200MB 上限

header('Content-Type: application/json; charset=utf-8');

if (!is_dir(APK_DIR)) @mkdir(APK_DIR, 0755, true);
// 读取最新版本信息不需要令牌（App 检查更新用）
if ($_SERVER['REQUEST_METHOD'] === 'GET') {
    $latest = [];
    if (is_file(APK_DIR . '/latest.json')) {
        $latest = json_decode(file_get_contents(APK_DIR . '/latest.json'), true) ?: [];
    }
    echo json_encode(['ok' => true, 'latest' => $latest], JSON_UNESCAPED_UNICODE);
    exit;
}

$token = $_SERVER['HTTP_X_TOKEN'] ?? ($_POST['token'] ?? '');
if (!hash_equals(APK_TOKEN, (string)$token)) {
    echo json_encode(['ok' => false, 'error' => '令牌错误'], JSON_UNESCAPED_UNICODE);
    exit;
}
if (!is_writable(APK_DIR)) {
    echo json_encode(['ok' => false, 'error' => 'apk 目录不可写，请在宝塔设置权限'], JSON_UNESCAPED_UNICODE);
    exit;
}

if (empty($_FILES['apk']) || $_FILES['apk']['error'] !== UPLOAD_ERR_OK) {
    echo json_encode(['ok' => false, 'error' => '未收到 apk 文件'], JSON_UNESCAPED_UNICODE);
    exit;
}
$f = $_FILES['apk'];
if ($f['size'] > MAX_APK_SIZE) {
    echo json_encode(['ok' => false, 'error' => 'apk 超过大小上限'], JSON_UNESCAPED_UNICODE);
    exit;
}

$name = isset($_POST['name']) ? preg_replace('/[^A-Za-z0-9._\-]/', '_', $_POST['name']) : 'TerrainEditor.apk';
if (strtolower(substr($name, -4)) !== '.apk') $name .= '.apk';

$dest = APK_DIR . '/' . $name;
if (!move_uploaded_file($f['tmp_name'], $dest)) {
    echo json_encode(['ok' => false, 'error' => '保存失败，请检查目录权限'], JSON_UNESCAPED_UNICODE);
    exit;
}

$base = 'https://' . ($_SERVER['HTTP_HOST'] ?? '')
    . str_replace('/apk_upload.php', '', $_SERVER['SCRIPT_NAME']);
$info = [
    'file' => $name,
    'size' => filesize($dest),
    'time' => date('Y-m-d H:i:s'),
    'url' => $base . '/apk/' . $name,
    'versionCode' => isset($_POST['versionCode']) ? (int)$_POST['versionCode'] : 0,
    'versionName' => isset($_POST['versionName']) ? preg_replace('/[^A-Za-z0-9._\-]/', '', $_POST['versionName']) : '',
    'notes' => isset($_POST['notes']) ? trim(strip_tags(substr($_POST['notes'], 0, 800))) : '',
];
@file_put_contents(APK_DIR . '/latest.json', json_encode($info, JSON_UNESCAPED_UNICODE));
echo json_encode(array_merge(['ok' => true], $info), JSON_UNESCAPED_UNICODE);
