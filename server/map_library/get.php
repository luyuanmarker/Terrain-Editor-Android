<?php
require __DIR__ . '/config.php';

$id = isset($_GET['id']) ? preg_replace('/[^A-Za-z0-9_\-]/', '', $_GET['id']) : '';
$maps = map_lib_read_index();
$found = null;
foreach ($maps as $m) {
    if ($m['id'] === $id) { $found = $m; break; }
}
if (!$found) {
    map_lib_json(['ok' => false, 'error' => '地图不存在']);
}
$path = MAPS_DIR . '/' . $found['file'];
if (!is_file($path)) {
    map_lib_json(['ok' => false, 'error' => '文件缺失']);
}

header('Content-Type: application/octet-stream');
header('Content-Disposition: attachment; filename="' . $found['file'] . '"');
header('Content-Length: ' . filesize($path));
readfile($path);
