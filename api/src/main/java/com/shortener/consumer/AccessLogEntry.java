package com.shortener.consumer;

import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

@Table("requests_by_url")
public class AccessLogEntry {

    @PrimaryKey
    private AccessLogKey key;

    @Column("ip_address")
    private String ipAddress;

    @Column("user_agent")
    private String userAgent;

    @Column("referer")
    private String referer;

    public AccessLogEntry() {}

    public AccessLogEntry(AccessLogKey key, String ipAddress, String userAgent, String referer) {
        this.key = key;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.referer = referer;
    }

    public AccessLogKey getKey()     { return key; }
    public String getIpAddress()     { return ipAddress; }
    public String getUserAgent()     { return userAgent; }
    public String getReferer()       { return referer; }

    public void setKey(AccessLogKey k)       { this.key = k; }
    public void setIpAddress(String v)       { this.ipAddress = v; }
    public void setUserAgent(String v)       { this.userAgent = v; }
    public void setReferer(String v)         { this.referer = v; }
}
