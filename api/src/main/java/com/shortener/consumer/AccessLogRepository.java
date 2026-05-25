package com.shortener.consumer;

import org.springframework.data.cassandra.repository.CassandraRepository;

public interface AccessLogRepository extends CassandraRepository<AccessLogEntry, AccessLogKey> {}
