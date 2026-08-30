<?php
// ===== BTL 地图库（宝塔面板部署，文件 + JSON 索引，无需数据库）=====

define('MAPLIB_DIR', __DIR__);
define('MAPS_DIR', __DIR__ . '/maps');       // 存放 .btl
define('THUMBS_DIR', __DIR__ . '/thumbs');   // 存放缩略图 .png
define('INDEX_FILE', __DIR__ . '/index.json');

// 上传大小上限（字节）
define('MAX_BTL_SIZE', 10 * 1024 * 1024);
define('MAX_THUMB_SIZE', 1024 * 1024);

if (!is_dir(MAPS_DIR)) @mkdir(MAPS_DIR, 0755, true);
if (!is_dir(THUMBS_DIR)) @mkdir(THUMBS_DIR, 0755, true);

function map_lib_read_index() {
    if (!is_file(INDEX_FILE)) return [];
    $raw = @file_get_contents(INDEX_FILE);
    $arr = json_decode($raw, true);
    return is_array($arr) ? $arr : [];
}

function map_lib_write_index($arr) {
    // 防并发写坏：先写临时文件再改名
    $tmp = INDEX_FILE . '.tmp';
    @file_put_contents($tmp, json_encode($arr, JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT));
    @rename($tmp, INDEX_FILE);
}

function map_lib_json($data) {
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode($data, JSON_UNESCAPED_UNICODE);
    exit;
}
