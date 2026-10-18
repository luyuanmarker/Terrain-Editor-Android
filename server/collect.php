<?php
require __DIR__ . '/config.php';

header('Content-Type: application/json; charset=utf-8');

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    http_response_code(405);
    echo json_encode(['ok' => false, 'msg' => 'method not allowed']);
    exit;
}

$raw = file_get_contents('php://input');
$d = json_decode($raw, true);
if (!is_array($d)) {
    $d = $_POST; // 兼容普通表单提交
}

if (empty($d['secret']) || $d['secret'] !== TELEMETRY_SECRET) {
    http_response_code(403);
    echo json_encode(['ok' => false, 'msg' => 'bad secret']);
    exit;
}

// 取客户端 IP（兼容 CDN/反代）
$ip = $_SERVER['HTTP_X_FORWARDED_FOR'] ?? $_SERVER['REMOTE_ADDR'] ?? '';
if (strpos($ip, ',') !== false) {
    $ip = trim(explode(',', $ip)[0]);
}

$record = [
    'time' => date('Y-m-d H:i:s'),
    'event' => isset($d['event']) ? substr((string)$d['event'], 0, 20) : 'open',
    'install_id' => isset($d['install_id']) ? substr((string)$d['install_id'], 0, 64) : '',
    'brand' => isset($d['brand']) ? mb_substr((string)$d['brand'], 0, 40) : '',
    'model' => isset($d['model']) ? mb_substr((string)$d['model'], 0, 60) : '',
    'android' => isset($d['android_version']) ? substr((string)$d['android_version'], 0, 20) : '',
    'sdk' => isset($d['sdk']) ? (int)$d['sdk'] : 0,
    'app_version' => isset($d['app_version']) ? substr((string)$d['app_version'], 0, 30) : '',
    'app_label' => isset($d['app_label']) ? mb_substr((string)$d['app_label'], 0, 30) : '',
    'ip' => substr($ip, 0, 64),
    'msg' => isset($d['msg']) ? mb_substr((string)$d['msg'], 0, 300) : '',
];

$line = json_encode($record, JSON_UNESCAPED_UNICODE);
@file_put_contents(DATA_FILE, $line . "\n", FILE_APPEND | LOCK_EX);

// 崩溃事件额外写入 crash.log，便于远程诊断闪退
if (($d['event'] ?? '') === 'crash') {
    $crash = [
        'time' => $record['time'],
        'model' => ($d['brand'] ?? '') . ' ' . ($d['model'] ?? ''),
        'android' => ($d['android_version'] ?? '') . ' SDK' . (int)($d['sdk'] ?? 0),
        'version' => $d['app_version'] ?? '',
        'install_id' => $record['install_id'],
        'msg' => mb_substr((string)($d['msg'] ?? ''), 0, 4000),
    ];
    @file_put_contents(__DIR__ . '/crash.log',
        json_encode($crash, JSON_UNESCAPED_UNICODE) . "\n", FILE_APPEND | LOCK_EX);
}

echo json_encode(['ok' => true]);
