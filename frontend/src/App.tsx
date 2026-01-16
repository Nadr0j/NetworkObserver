import { useEffect, useMemo, useState } from "react";
import {
  Chart as ChartJS,
  CategoryScale,
  LinearScale,
  PointElement,
  LineElement,
  Tooltip,
  Legend,
} from "chart.js";
import { Line } from "react-chartjs-2";

ChartJS.register(CategoryScale, LinearScale, PointElement, LineElement, Tooltip, Legend);

type WindowAvailability = {
  window_available: boolean;
  quality_score: number;
  quality_state: string;
};

type WindowRow = {
  timestamp: string;
  targets?: Record<string, string>;
  availability?: WindowAvailability;
  [key: string]: any;
};

type MetricOption = {
  key: string;
  label: string;
  unit: string;
};

const metricOptions: MetricOption[] = [
  { key: "loss_pct", label: "Loss", unit: "%" },
  { key: "rtt_ms_p50", label: "RTT p50", unit: "ms" },
  { key: "rtt_ms_p90", label: "RTT p90", unit: "ms" },
  { key: "rtt_ms_max", label: "RTT max", unit: "ms" },
  { key: "jitter_ms_p90", label: "Jitter p90", unit: "ms" },
];

const palette = [
  "#d96c2c",
  "#2a7f62",
  "#1f4b99",
  "#b7465c",
  "#8552c7",
  "#2f9bd7",
  "#c0a144",
  "#2b2b2b",
];

export default function App() {
  const [rows, setRows] = useState<WindowRow[]>([]);
  const [selectedTargets, setSelectedTargets] = useState<Record<string, boolean>>({});
  const [selectedMetrics, setSelectedMetrics] = useState<Record<string, boolean>>({
    loss_pct: true,
    rtt_ms_p50: false,
    rtt_ms_p90: true,
    rtt_ms_max: false,
    jitter_ms_p90: true,
  });
  const [isMobile, setIsMobile] = useState(() => window.innerWidth <= 820);
  const [summaryWindowFilter, setSummaryWindowFilter] = useState<number | "all">(() =>
    window.innerWidth <= 820 ? 60 : "all"
  );
  const [metricWindowFilters, setMetricWindowFilters] = useState<
    Record<string, number | "all">
  >({});
  const [metricTargetSelections, setMetricTargetSelections] = useState<
    Record<string, Record<string, boolean>>
  >({});
  const [currentFile, setCurrentFile] = useState<File | null>(null);
  const [logUrl, setLogUrl] = useState("/logs/network_observer.jsonl");
  const [error, setError] = useState<string | null>(null);

  const targets = useMemo(() => {
    if (rows.length === 0) return [];
    const targetMap = rows[0].targets ?? {};
    return Object.keys(targetMap);
  }, [rows]);

  const windowOptions: Array<{ label: string; value: number | "all" }> = [
    { label: "Last 30", value: 30 },
    { label: "Last 60", value: 60 },
    { label: "Last 180", value: 180 },
    { label: "All", value: "all" },
  ];

  const summaryRows = useMemo(() => {
    if (summaryWindowFilter === "all") return rows;
    return rows.slice(-summaryWindowFilter);
  }, [rows, summaryWindowFilter]);

  const labels = useMemo(
    () =>
      summaryRows.map((row) =>
        new Date(row.timestamp).toLocaleTimeString([], {
          hour: "2-digit",
          minute: "2-digit",
          second: isMobile ? undefined : "2-digit",
        })
      ),
    [summaryRows, isMobile]
  );

  const summaryDatasets = useMemo(() => {
    const activeTargets = targets.filter((target) => selectedTargets[target]);
    const activeMetrics = metricOptions.filter((metric) => selectedMetrics[metric.key]);

    const result: any[] = [];
    let colorIndex = 0;

    activeTargets.forEach((target) => {
      activeMetrics.forEach((metric) => {
        const data = summaryRows.map((row) => row[target]?.[metric.key] ?? null);
        const color = palette[colorIndex % palette.length];
        result.push({
          label: `${target} ${metric.label}`,
          data,
          borderColor: color,
          backgroundColor: `${color}33`,
          borderWidth: isMobile ? 2.6 : 2,
          pointRadius: isMobile ? 0 : 2,
          pointHoverRadius: isMobile ? 4 : 5,
          pointHitRadius: 8,
          tension: 0.25,
          spanGaps: true,
        });
        colorIndex += 1;
      });
    });

    return result;
  }, [summaryRows, isMobile, selectedMetrics, selectedTargets, targets]);

  const summaryChartData = useMemo(
    () => ({
      labels,
      datasets: summaryDatasets,
    }),
    [labels, summaryDatasets]
  );

  const chartOptions = useMemo(
    () => ({
      responsive: true,
      maintainAspectRatio: false,
      interaction: {
        mode: "index" as const,
        intersect: false,
      },
      plugins: {
        legend: {
          display: !isMobile,
          position: "bottom" as const,
          labels: {
            color: "#1e1f24",
            boxWidth: 14,
            padding: 12,
          },
        },
        tooltip: {
          mode: "index" as const,
          intersect: false,
          padding: 10,
          titleMarginBottom: 6,
          bodySpacing: 4,
        },
      },
      scales: {
        x: {
          ticks: {
            color: "#1e1f24",
            maxRotation: 0,
            autoSkip: true,
            maxTicksLimit: isMobile ? 4 : 8,
            padding: isMobile ? 6 : 8,
            font: {
              size: isMobile ? 10 : 12,
            },
          },
          grid: {
            display: !isMobile,
            color: "rgba(0,0,0,0.08)",
          },
        },
        y: {
          ticks: {
            color: "#1e1f24",
            maxTicksLimit: isMobile ? 4 : 6,
            padding: isMobile ? 6 : 8,
            font: {
              size: isMobile ? 10 : 12,
            },
          },
          grid: {
            color: isMobile ? "rgba(0,0,0,0.06)" : "rgba(0,0,0,0.08)",
          },
        },
      },
    }),
    [isMobile]
  );

  const metricChartOptions = useMemo(
    () => ({
      responsive: true,
      maintainAspectRatio: false,
      interaction: {
        mode: "index" as const,
        intersect: false,
      },
      plugins: {
        legend: {
          display: false,
        },
        tooltip: {
          mode: "index" as const,
          intersect: false,
          padding: 10,
          titleMarginBottom: 6,
          bodySpacing: 4,
        },
      },
      scales: {
        x: {
          ticks: {
            color: "#1e1f24",
            maxRotation: 0,
            autoSkip: true,
            maxTicksLimit: isMobile ? 3 : 6,
            padding: isMobile ? 6 : 8,
            font: {
              size: isMobile ? 10 : 12,
            },
          },
          grid: {
            display: false,
          },
        },
        y: {
          ticks: {
            color: "#1e1f24",
            maxTicksLimit: isMobile ? 4 : 6,
            padding: isMobile ? 6 : 8,
            font: {
              size: isMobile ? 10 : 12,
            },
          },
          grid: {
            color: isMobile ? "rgba(0,0,0,0.06)" : "rgba(0,0,0,0.08)",
          },
        },
      },
    }),
    [isMobile]
  );

  const handleFileChange = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0] ?? null;
    if (!file) return;
    setCurrentFile(file);
    await loadFile(file);
  };

  const loadFile = async (file: File) => {
    try {
      const text = await file.text();
      const parsed = text
        .split(/\r?\n/)
        .map((line) => line.trim())
        .filter(Boolean)
        .map((line) => JSON.parse(line));

      setRows(parsed);
      if (parsed.length > 0) {
        const discoveredTargets = parsed[0]?.targets ?? {};
        const targetState: Record<string, boolean> = {};
        Object.keys(discoveredTargets).forEach((target) => {
          targetState[target] = true;
        });
        setSelectedTargets(targetState);
        setMetricTargetSelections((prev) => {
          if (Object.keys(prev).length > 0) return prev;
          const next: Record<string, Record<string, boolean>> = {};
          metricOptions.forEach((metric) => {
            next[metric.key] = { ...targetState };
          });
          return next;
        });
        setMetricWindowFilters((prev) => {
          if (Object.keys(prev).length > 0) return prev;
          const next: Record<string, number | "all"> = {};
          metricOptions.forEach((metric) => {
            next[metric.key] = isMobile ? 30 : 60;
          });
          return next;
        });
      } else {
        setSelectedTargets({});
        setMetricTargetSelections({});
        setMetricWindowFilters({});
      }
      setError(null);
    } catch (err) {
      setError("Could not parse JSONL file. Make sure it contains one JSON object per line.");
    }
  };

  const loadFromUrl = async (url: string) => {
    try {
      const response = await fetch(url, { cache: "no-store" });
      if (!response.ok) {
        throw new Error("request_failed");
      }
      const text = await response.text();
      const parsed = text
        .split(/\r?\n/)
        .map((line) => line.trim())
        .filter(Boolean)
        .map((line) => JSON.parse(line));

      setRows(parsed);
      if (parsed.length > 0) {
        const discoveredTargets = parsed[0]?.targets ?? {};
        const targetState: Record<string, boolean> = {};
        Object.keys(discoveredTargets).forEach((target) => {
          targetState[target] = true;
        });
        setSelectedTargets(targetState);
        setMetricTargetSelections((prev) => {
          if (Object.keys(prev).length > 0) return prev;
          const next: Record<string, Record<string, boolean>> = {};
          metricOptions.forEach((metric) => {
            next[metric.key] = { ...targetState };
          });
          return next;
        });
        setMetricWindowFilters((prev) => {
          if (Object.keys(prev).length > 0) return prev;
          const next: Record<string, number | "all"> = {};
          metricOptions.forEach((metric) => {
            next[metric.key] = isMobile ? 30 : 60;
          });
          return next;
        });
      } else {
        setSelectedTargets({});
        setMetricTargetSelections({});
        setMetricWindowFilters({});
      }
      setError(null);
    } catch (err) {
      setError("Could not fetch JSONL from URL. Check the path and that the log is served.");
    }
  };

  useEffect(() => {
    loadFromUrl(logUrl);
  }, []);

  useEffect(() => {
    const handleResize = () => {
      setIsMobile(window.innerWidth <= 820);
    };
    window.addEventListener("resize", handleResize);
    return () => window.removeEventListener("resize", handleResize);
  }, []);

  const handleReload = async () => {
    if (currentFile) {
      await loadFile(currentFile);
      return;
    }
    if (logUrl) {
      await loadFromUrl(logUrl);
    }
  };

  const handleUrlLoad = async () => {
    if (!logUrl) return;
    setCurrentFile(null);
    await loadFromUrl(logUrl);
  };

  return (
    <div className="app">
      <header className="hero">
        <div>
          <p className="eyebrow">NetworkObserver</p>
          <h1>See the shape of your connection.</h1>
          <p className="lede">
            Load the JSONL log and explore latency, loss, and jitter. Toggle what you want, reload when
            new data lands. The backend serves <strong>/logs/network_observer.jsonl</strong> by default.
          </p>
        </div>
          <div className="hero-card">
          <label className="file-label">
            <span>Log URL (same server)</span>
            <input
              type="text"
              value={logUrl}
              onChange={(event) => setLogUrl(event.target.value)}
              placeholder="/logs/network_observer.jsonl"
            />
          </label>
          <button onClick={handleUrlLoad}>Load from URL</button>
          <label className="file-label">
            <span>Load log file</span>
            <input type="file" accept=".jsonl,.txt,application/json" onChange={handleFileChange} />
          </label>
          <button className="ghost" onClick={handleReload} disabled={!currentFile && !logUrl}>
            Reload
          </button>
          {error && <p className="error">{error}</p>}
        </div>
      </header>

      <section className="panel">
        <div className="panel-header">
          <h2>Summary view</h2>
          <p>{rows.length} windows loaded</p>
        </div>
        <div className="summary-controls">
          <div className="control-block">
            <h3>Window</h3>
            <div className="window-controls">
              {windowOptions.map((option) => (
                <button
                  key={option.label}
                  className={`chip ${summaryWindowFilter === option.value ? "active" : ""}`}
                  type="button"
                  onClick={() => setSummaryWindowFilter(option.value)}
                >
                  {option.label}
                </button>
              ))}
            </div>
          </div>
          <div className="control-block">
            <h3>Targets</h3>
            {targets.length === 0 && <p className="muted">Load a file to see targets.</p>}
            <div className="toggle-row">
              {targets.map((target) => (
                <label key={target} className="toggle">
                  <input
                    type="checkbox"
                    checked={!!selectedTargets[target]}
                    onChange={(event) =>
                      setSelectedTargets((prev) => ({
                        ...prev,
                        [target]: event.target.checked,
                      }))
                    }
                  />
                  <span>{target}</span>
                </label>
              ))}
            </div>
          </div>
          <div className="control-block">
            <h3>Metrics</h3>
            <div className="toggle-row">
              {metricOptions.map((metric) => (
                <label key={metric.key} className="toggle">
                  <input
                    type="checkbox"
                    checked={!!selectedMetrics[metric.key]}
                    onChange={(event) =>
                      setSelectedMetrics((prev) => ({
                        ...prev,
                        [metric.key]: event.target.checked,
                      }))
                    }
                  />
                  <span>{metric.label}</span>
                </label>
              ))}
            </div>
          </div>
        </div>
        <div className="chart">
          {rows.length === 0 ? (
            <div className="empty">No data yet. Load the JSONL log to begin.</div>
          ) : (
            <Line data={summaryChartData} options={chartOptions} />
          )}
        </div>
      </section>

      <section className="metrics-grid">
        {metricOptions.map((metric) => {
          const windowFilter = metricWindowFilters[metric.key] ?? (isMobile ? 30 : 60);
          const metricRows =
            windowFilter === "all" ? rows : rows.slice(-Number(windowFilter));
          const metricLabels = metricRows.map((row) =>
            new Date(row.timestamp).toLocaleTimeString([], {
              hour: "2-digit",
              minute: "2-digit",
            })
          );
          const targetSelection = metricTargetSelections[metric.key] ?? {};
          const activeTargets = targets.filter((target) => targetSelection[target]);
          const metricDatasets = activeTargets.map((target, index) => {
            const data = metricRows.map((row) => row[target]?.[metric.key] ?? null);
            const color = palette[index % palette.length];
            return {
              label: target,
              data,
              borderColor: color,
              backgroundColor: `${color}33`,
              borderWidth: isMobile ? 2.4 : 2,
              pointRadius: 0,
              pointHoverRadius: 4,
              pointHitRadius: 8,
              tension: 0.25,
              spanGaps: true,
            };
          });
          return (
            <div className="metric-card" key={metric.key}>
              <div className="metric-header">
                <div>
                  <h3>{metric.label}</h3>
                  <p className="muted">{metric.unit}</p>
                </div>
                <div className="window-controls">
                  {windowOptions.map((option) => (
                    <button
                      key={option.label}
                      className={`chip ${windowFilter === option.value ? "active" : ""}`}
                      type="button"
                      onClick={() =>
                        setMetricWindowFilters((prev) => ({
                          ...prev,
                          [metric.key]: option.value,
                        }))
                      }
                    >
                      {option.label}
                    </button>
                  ))}
                </div>
              </div>
              <div className="metric-targets">
                {targets.map((target) => (
                  <label key={`${metric.key}-${target}`} className="toggle">
                    <input
                      type="checkbox"
                      checked={!!targetSelection[target]}
                      onChange={(event) =>
                        setMetricTargetSelections((prev) => ({
                          ...prev,
                          [metric.key]: {
                            ...(prev[metric.key] ?? {}),
                            [target]: event.target.checked,
                          },
                        }))
                      }
                    />
                    <span>{target}</span>
                  </label>
                ))}
              </div>
              <div className="chart metric-chart">
                {rows.length === 0 ? (
                  <div className="empty">No data yet.</div>
                ) : (
                  <Line
                    data={{ labels: metricLabels, datasets: metricDatasets }}
                    options={metricChartOptions}
                  />
                )}
              </div>
            </div>
          );
        })}
      </section>

      <section className="summary">
        {rows.slice(-3).map((row) => (
          <div key={row.timestamp} className="summary-card">
            <div>
              <h4>{new Date(row.timestamp).toLocaleString()}</h4>
              <p className="muted">Quality: {row.availability?.quality_state ?? "Unknown"}</p>
            </div>
            <div className="score">
              {row.availability?.quality_score ?? "--"}
              <span>score</span>
            </div>
          </div>
        ))}
      </section>
    </div>
  );
}
