<?php
// ===== BTL 地图库（宝塔面板部署，文件 + JSON 索引，无需数据库）=====

define('MAPLIB_DIR', __DIR__);
define('MAPS_DIR', __DIR__ . '/maps');       // 存放 .btl
define('THUMBS_DIR', __DIR__ . '/thumbs');   // 存放缩略图 .png
define('INDEX_FILE', __DIR__ . '/index.json');

// 上传大小上限（字节）
define('MAX_BTL_SIZE', 10 * 1024 * 1024);
define('MAX_THUMB_SIZE', 1024 * 1024);

// 尽量确保目录存在（失败也不致命，upload.php 会再检查）
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
    $ok = @file_put_contents($tmp, json_encode($arr, JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT));
    if ($ok === false) return false;
    if (!@rename($tmp, INDEX_FILE)) {
        // 改名失败（跨设备/权限），退回直接写
        if (@file_put_contents(INDEX_FILE, json_encode($arr, JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT)) === false) {
            return false;
        }
    }
    return true;
}

/** UTF-8 安全的截断（不依赖 mbstring）。 */
function map_lib_cut($s, $max) {
    if (strlen($s) <= $max) return $s;
    $out = '';
    $n = 0;
    $i = 0;
    $len = strlen($s);
    while ($i < $len && $n < $max) {
        $c = ord($s[$i]);
        $bytes = $c < 0x80 ? 1 : ($c < 0xE0 ? 2 : ($c < 0xF0 ? 3 : 4));
        $out .= substr($s, $i, $bytes);
        $i += $bytes;
        $n++;
    }
    return $out;
}

function map_lib_json($data) {
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode($data, JSON_UNESCAPED_UNICODE);
    exit;
}
