## Overview
The project is called NetworkObserver. The purpose of network observer is to understand, from the perspective of
a minipc on my home network, the quality of the network connection.

## Backend
The NetworkObserver will aggregate data over 60s. At the end of each 60s period the program will create summary
statistics for that 60s window and log it into a log file in a json line format. 

Targets and thresholds are configurable via a local config file. Per-target metrics are computed separately
for each configured target (e.g., gateway vs public).

We want to be able to answer the following questions with the summary statistics
- What is the typical latency to the local gateway and to the public internet?
- How often do we see latency spikes beyond normal (e.g., >2x median)?
- What is the jitter within each 60s window?
- Does latency change by time of day?
- What percentage of packets are lost in each 60s window?
- Are there brief outage windows (100% loss) and how long do they last?
- How often do we see intermittent loss (e.g., 1-5%)?
- What is the achieved download/upload throughput during periodic tests?
- Are we hitting a ceiling vs. advertised ISP speeds?
- Does throughput degrade when latency/jitter spikes?
- How variable is performance within a window (variance/percentiles)?
- Are there recurring patterns (hourly/daily) of degradation?
- How frequently do we transition between "good" and "poor" states?
- What percentage of time is the connection usable (latency/loss under thresholds)?
- How many minutes per day are effectively "offline"?
- Is the problem inside the LAN (gateway latency) or upstream (public endpoint latency)?
- Are issues correlated with Wi-Fi vs Ethernet (if both are available)?
- Can we classify each window into Good/Warning/Bad?
- What is the rolling 24h uptime and average quality score?

## Summary stats (json line per 60s window)
We store one json object per 60s window. Thresholds (loss/jitter/latency) are configurable.
Per-target metrics are captured for each configured target, while a small set of window-level fields
captures context and availability.

Example schema:
```json
{
  "timestamp": "2025-01-01T12:00:00Z",
  "window_start": "2025-01-01T12:00:00Z",
  "window_end": "2025-01-01T12:00:59Z",
  "interface": "eth0",
  "link_type": "wired",
  "ssid": null,
  "targets": {
    "gateway": "192.168.1.1",
    "public": "1.1.1.1"
  },
  "gateway": {
    "sent": 60,
    "received": 60,
    "loss_pct": 0.0,
    "rtt_ms_min": 1.2,
    "rtt_ms_p50": 1.6,
    "rtt_ms_p90": 2.3,
    "rtt_ms_p99": 3.1,
    "rtt_ms_max": 3.4,
    "rtt_ms_mean": 1.7,
    "rtt_ms_stddev": 0.3,
    "jitter_ms_p50": 0.2,
    "jitter_ms_p90": 0.6,
    "rtt_spike_count": 0,
    "outage_seconds": 0
  },
  "public": {
    "sent": 60,
    "received": 59,
    "loss_pct": 1.7,
    "rtt_ms_min": 12.4,
    "rtt_ms_p50": 15.8,
    "rtt_ms_p90": 28.2,
    "rtt_ms_p99": 40.1,
    "rtt_ms_max": 44.6,
    "rtt_ms_mean": 16.4,
    "rtt_ms_stddev": 4.2,
    "jitter_ms_p50": 1.1,
    "jitter_ms_p90": 3.8,
    "rtt_spike_count": 1,
    "outage_seconds": 0
  },
  "throughput": {
    "sampled": true,
    "dl_mbps": 185.2,
    "ul_mbps": 18.4,
    "test_duration_s": 10,
    "test_source": "iperf3"
  },
  "availability": {
    "window_available": true,
    "quality_score": 92,
    "quality_state": "Good"
  }
}
```

Definitions:
- timestamp: ISO-8601 timestamp for the end of the 60s window (UTC).
- window_start: ISO-8601 start time for the 60s window (UTC).
- window_end: ISO-8601 end time for the 60s window (UTC).
- interface: Network interface name (e.g., eth0, wlan0).
- link_type: wired or wifi.
- ssid: Wi-Fi SSID when on wifi; null otherwise.
- targets.gateway: Local gateway IP or hostname.
- targets.public: Public endpoint IP or hostname.
- sent: Number of probes sent in the window for the target.
- received: Number of probes received in the window for the target.
- loss_pct: Packet loss percentage for the target over the window.
- rtt_ms_min: Minimum round-trip time in milliseconds.
- rtt_ms_p50: Median round-trip time in milliseconds.
- rtt_ms_p90: 90th percentile round-trip time in milliseconds.
- rtt_ms_p99: 99th percentile round-trip time in milliseconds.
- rtt_ms_max: Maximum round-trip time in milliseconds.
- rtt_ms_mean: Mean round-trip time in milliseconds.
- rtt_ms_stddev: Standard deviation of round-trip time in milliseconds.
- jitter_ms_p50: Median absolute difference between successive RTTs in milliseconds.
- jitter_ms_p90: 90th percentile absolute difference between successive RTTs in milliseconds.
- rtt_spike_count: Count of RTT samples exceeding the spike threshold (configurable).
- outage_seconds: Total seconds with 100% loss within the window.
- throughput.sampled: Whether a throughput test ran in the window.
- throughput.dl_mbps: Download throughput in megabits per second.
- throughput.ul_mbps: Upload throughput in megabits per second.
- throughput.test_duration_s: Duration of the throughput test in seconds.
- throughput.test_source: Tool or endpoint used for the throughput test.
- availability.window_available: True if loss/jitter/latency are under configured thresholds.
- availability.quality_score: 0-100 score derived from loss/jitter/latency thresholds.
- availability.quality_state: Good, Warning, or Bad classification from thresholds.

## Config (json)
Behavior is controlled by a local json config file. This config defines targets, probe cadence,
and thresholds used for spike detection and availability classification.

Example config:
```json
{
  "window_seconds": 60,
  "probe_interval_seconds": 1,
  "targets": {
    "gateway": "192.168.1.1",
    "public": "1.1.1.1"
  },
  "thresholds": {
    "loss_pct_max": 2.0,
    "rtt_ms_p90_max": 100,
    "jitter_ms_p90_max": 30,
    "rtt_spike_factor": 2.0
  },
  "throughput": {
    "enabled": true,
    "interval_minutes": 30,
    "test_duration_s": 10,
    "source": "iperf3",
    "server": "iperf3.example.net",
    "port": 5201,
    "port_range": [5201, 5210],
    "retries": 2,
    "retry_delay_ms": 1000
  }
}
```

Config definitions:
- window_seconds: Aggregation window length in seconds.
- probe_interval_seconds: Time between probes per target.
- targets: Map of target names to IPs or hostnames.
- thresholds.loss_pct_max: If a target's loss_pct exceeds this, availability for that target is false.
- thresholds.rtt_ms_p90_max: If a target's rtt_ms_p90 exceeds this, availability for that target is false.
- thresholds.jitter_ms_p90_max: If a target's jitter_ms_p90 exceeds this, availability for that target is false.
- thresholds.rtt_spike_factor: An RTT sample is a spike when rtt_ms > (rtt_spike_factor * rtt_ms_p50) for that target.
- throughput.enabled: When true, schedule throughput tests and emit throughput fields; when false, omit them.
- throughput.interval_minutes: Minimum time between throughput tests.
- throughput.test_duration_s: Duration of a throughput test.
- throughput.source: Tool or endpoint used for throughput tests.
- throughput.server: iperf3 server address; if missing, throughput tests are skipped.
- throughput.port: Optional iperf3 port (defaults to 5201).
- throughput.port_range: Optional two-item list [start, end] to rotate ports across retries.
- throughput.retries: Number of additional attempts after the first failure.
- throughput.retry_delay_ms: Delay between retries in milliseconds.

## Running
Backend (Kotlin):
1) Install Gradle and a JDK 17+.
2) Run: `cd backend && gradle run --args="--config ../config.json"`
3) Metrics are written to `backend/logs/network_observer.jsonl` when running from `backend/`.
4) Application logs are written to `backend/logs/network_observer_app.log` and also printed to stdout.
5) The backend also starts a web server at `http://0.0.0.0:8080` by default.

Frontend (Vite + React):
1) Run: `cd frontend && npm install`
2) Build: `npm run build`
3) The backend serves the built frontend at `http://<backend-ip>:8080`.
4) The UI loads `/logs/network_observer.jsonl` by default.

LAN access:
- Find your backend machine's LAN IP (e.g., `192.168.1.x`) and open `http://<ip>:8080` on your phone.
- If needed, you can override the web server settings with `--host`, `--port`, or `--frontend-dir`.

Quick start:
- `./scripts/start.sh` (builds frontend if needed, then runs backend)

## Docker
Run everything (backend, web server, iperf3) in a container:
1) Build and start: `docker compose up --build`
2) Logs written to `backend/logs/`
3) Access UI at `http://<host-ip>:8080`

Notes:
- Config is mounted from `config.json`.
- iperf3 is installed inside the container, so throughput tests work without local installs.


## Frontend
We will also create a frontend that is mobile-first that visualizes the data in the json line file. The user
can also refresh the graph at any point by pressing a "reload" button. The user can toggle on and off on the graph
which data is displayed. The interface should provide the user with sufficient data views to easily visually answer
all of the desired questions.
