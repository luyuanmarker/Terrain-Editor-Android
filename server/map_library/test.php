<?php
// BTL 地图库诊断页：浏览器打开看权限是否正常
require __DIR__ . '/config.php';

header('Content-Type: text/plain; charset=utf-8');
echo "PHP 版本: " . PHP_VERSION . "\n";
echo "PHP 用户: " . (function_exists('posix_getpwuid') && function_exists('posix_geteuid')
        ? (posix_getpwuid(posix_geteuid())['name'] ?? '?') : '未知') . "\n";
echo "mbstring: " . (extension_loaded('mbstring') ? '有' : '无（已用兼容写法，不影响）') . "\n";
echo "\n-- 目录/文件可写检查 --\n";
$checks = [
    'map_library 目录' => __DIR__,
    'maps 目录' => MAPS_DIR,
    'thumbs 目录' => THUMBS_DIR,
    'index.json（不存在则看目录）' => INDEX_FILE,
];
foreach ($checks as $label => $p) {
    $exists = file_exists($p);
    $writable = is_writable($p) ? '可写' : '不可写';
    $dir = is_dir($p);
    echo ($dir ? '[目录] ' : '[文件] ') . $label . ": " . ($exists ? '' : '不存在 ') . $writable . "\n";
}
$maps = map_lib_read_index();
echo "\nindex.json 当前记录数: " . count($maps) . "\n";
if (is_dir(MAPS_DIR)) {
    $n = count(glob(MAPS_DIR . '/*.btl') ?: []);
    echo "maps/ 里的 btl 文件数: " . $n . "\n";
}
