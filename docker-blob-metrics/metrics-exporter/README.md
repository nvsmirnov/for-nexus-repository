docker compose project for exporting metrics gathered by Nexus Tasks script

To add to prometheus:
```
  - job_name: nexus-docker-stats
    scrape_interval: 1h # not needed because it will anyway discard series with the same timestamp
    scheme: http
    metrics_path: /metrics/dockerBlobSizes.prom
    static_configs:
      - targets: ['server-with-exporter-docker-compose.domain.tld:9100']
```
