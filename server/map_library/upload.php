<?php
require __DIR__ . '/config.php';

$name = isset($_POST['name']) ? trim(strip_tags(mb_substr($_POST['name'], 0, 60))) : '';
$author = isset($_POST['author']) ? trim(strip_tags(mb_substr($_POST['author'], 0, 30))) : '';
$desc = isset($_POST['desc']) ? trim(strip_tags(mb_substr($_POST['desc'], 0, 300))) : '';

if (empty($name)) $name = '未命名地图';
if (empty($author)) $author = '匿名';

if (empty($_FILES['btl']) || $_FILES['btl']['error'] !== UPLOAD_ERR_OK) {
    map_lib_json(['ok' => false, 'error' => '未收到 btl 文件']);
}
$btl = $_FILES['btl'];
$ext = strtolower(pathinfo($btl['name'], PATHINFO_EXTENSION));
if ($ext !== 'btl') {
    map_lib_json(['ok' => false, 'error' => '只支持 .btl 文件']);
}
if ($btl['size'] > MAX_BTL_SIZE) {
    map_lib_json(['ok' => false, 'error' => '文件超过 10MB 上限']);
}

$id = date('YmdHis') . '_' . mt_rand(100, 999);
$file = $id . '.btl';
if (!move_uploaded_file($btl['tmp_name'], MAPS_DIR . '/' . $file)) {
    map_lib_json(['ok' => false, 'error' => '保存失败，请检查目录写入权限']);
}

$thumb = '';
if (!empty($_FILES['thumb']) && $_FILES['thumb']['error'] === UPLOAD_ERR_OK) {
    $t = $_FILES['thumb'];
    if ($t['size'] <= MAX_THUMB_SIZE) {
        $thumb = $id . '.png';
        @move_uploaded_file($t['tmp_name'], THUMBS_DIR . '/' . $thumb);
    }
}

$maps = map_lib_read_index();
$maps[] = [
    'id' => $id,
    'file' => $file,
    'thumb' => $thumb,
    'name' => $name,
    'author' => $author,
    'desc' => $desc,
    'size' => (int)$btl['size'],
    'time' => date('Y-m-d H:i:s'),
];
map_lib_write_index($maps);

map_lib_json(['ok' => true, 'id' => $id, 'name' => $name]);
