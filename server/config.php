<?php
// ===== 设备使用统计（宝塔面板部署）=====

// 上报密钥：必须与 App 内 MainActivity 的 TELEMETRY_SECRET 一致
define('TELEMETRY_SECRET', 'wc4_editor_2026');

// 数据文件（自动创建，保持 PHP 有写入权限即可，无需数据库）
define('DATA_FILE', __DIR__ . '/data.txt');

// 查看页最多显示多少条
define('PAGE_SIZE', 200);
