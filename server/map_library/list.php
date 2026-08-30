<?php
require __DIR__ . '/config.php';

$maps = map_lib_read_index();
usort($maps, function ($a, $b) {
    return strcmp($b['time'], $a['time']); // 新的在前
});

$base = 'https://' . ($_SERVER['HTTP_HOST'] ?? '') . str_replace('/list.php', '', $_SERVER['SCRIPT_NAME']);
foreach ($maps as &$m) {
    $m['thumb_url'] = $base . '/thumbs/' . rawurlencode($m['thumb']);
}
unset($m);

map_lib_json(['ok' => true, 'maps' => $maps]);
