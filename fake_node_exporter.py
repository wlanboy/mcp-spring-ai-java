import http.server

METRICS = """\
node_memory_MemTotal_bytes 8589934592
node_memory_MemAvailable_bytes 4294967296
node_memory_SwapTotal_bytes 2147483648
node_memory_SwapFree_bytes 2147483648
node_load1 0.5
node_load5 0.4
node_load15 0.3
node_boot_time_seconds 1700000000
node_cpu_seconds_total{cpu="0",mode="user"} 1000
node_cpu_seconds_total{cpu="0",mode="system"} 500
node_cpu_seconds_total{cpu="0",mode="idle"} 8500
node_uname_info{domainname="(none)",machine="x86_64",nodename="testhost",release="6.1.0",sysname="Linux",version="#1"} 1
node_filesystem_size_bytes{device="/dev/sda1",fstype="ext4",mountpoint="/"} 107374182400
node_filesystem_avail_bytes{device="/dev/sda1",fstype="ext4",mountpoint="/"} 53687091200
node_hwmon_temp_celsius{chip="coretemp",sensor="temp1"} 45.0
node_hwmon_temp_crit_celsius{chip="coretemp",sensor="temp1"} 100.0
node_pressure_cpu_waiting_seconds_total 10
node_pressure_io_waiting_seconds_total 5
node_pressure_io_stalled_seconds_total 1
node_pressure_memory_waiting_seconds_total 2
node_pressure_memory_stalled_seconds_total 0
node_network_receive_bytes_total{device="eth0"} 1048576
node_network_transmit_bytes_total{device="eth0"} 1048576
node_network_receive_errs_total{device="eth0"} 0
node_network_transmit_errs_total{device="eth0"} 0
node_network_receive_drop_total{device="eth0"} 0
node_network_transmit_drop_total{device="eth0"} 0
node_disk_read_bytes_total{device="sda"} 1048576
node_disk_written_bytes_total{device="sda"} 1048576
"""

class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.end_headers()
        self.wfile.write(METRICS.encode())
    def log_message(self, format, *args):
        pass

http.server.HTTPServer(("localhost", 9100), Handler).serve_forever()
