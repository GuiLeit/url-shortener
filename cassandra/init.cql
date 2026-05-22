CREATE KEYSPACE IF NOT EXISTS shortener
  WITH replication = { 'class': 'SimpleStrategy', 'replication_factor': 1 };

CREATE TABLE IF NOT EXISTS shortener.urls_by_shortcode (
    short_code text PRIMARY KEY,
    long_url   text,
    url_id     bigint,
    created_at timestamp
);

CREATE TABLE IF NOT EXISTS shortener.requests_by_url (
    short_code   text,
    time_bucket  text,
    request_time timestamp,
    request_id   timeuuid,
    ip_address   text,
    user_agent   text,
    referer      text,
    PRIMARY KEY ((short_code, time_bucket), request_time, request_id)
) WITH CLUSTERING ORDER BY (request_time DESC);

CREATE TABLE IF NOT EXISTS shortener.access_counts (
    short_code text,
    day        date,
    count      counter,
    PRIMARY KEY ((short_code), day)
);
