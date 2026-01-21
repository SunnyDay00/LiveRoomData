CREATE TABLE IF NOT EXISTS live_room_records (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    app_name TEXT,
    homeid TEXT,
    homename TEXT,
    fansnumber TEXT,
    homeip TEXT,
    dayuesenumber TEXT,
    monthuesenumber TEXT,
    ueseid TEXT,
    uesename TEXT,
    consumption TEXT,
    ueseip TEXT,
    summaryconsumption TEXT,
    record_time TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);
