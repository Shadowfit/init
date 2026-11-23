samples per cell: 30  (rows dropped for missing stats: 0)

### A1 / B median ratio - list (TOP 20 ... ORDER BY start_time DESC)

| window \ status | FAILED 2% | CANCELLED 8% | IN_PROGRESS 20% | COMPLETED 70% |
|---|--:|--:|--:|--:|
| 1d | **22.96x** (MySQL 8.93x) | **6.73x** (MySQL 2.33x) | **3.67x** (MySQL 2.28x) | **1.17x** (MySQL 1.24x) |
| 7d | **19.16x** (MySQL 21.62x) | **6.43x** (MySQL 14.94x) | **5.76x** (MySQL 6.32x) | LOSE **0.98x** (MySQL 1.10x) |
| 30d | **18.43x** (MySQL 14.33x) | **6.17x** (MySQL 3.75x) | **4.22x** (MySQL 0.89x) | **1.25x** (MySQL 0.91x) |
| all | **19.96x** (MySQL 0.26x) | **6.93x** (MySQL 0.10x) | **3.84x** (MySQL 0.07x) | **1.05x** (MySQL 0.03x) |

### A1 / B median ratio - count (COUNT_BIG(*))

| window \ status | FAILED 2% | CANCELLED 8% | IN_PROGRESS 20% | COMPLETED 70% |
|---|--:|--:|--:|--:|
| 1d | **2662.65x** (MySQL 85.00x) | **1778.98x** (MySQL 40.00x) | **1329.07x** (MySQL 18.30x) | **521.31x** (MySQL 6.14x) |
| 7d | **1598.28x** (MySQL 183.60x) | **564.47x** (MySQL 47.60x) | **296.13x** (MySQL 18.40x) | **76.16x** (MySQL 5.78x) |
| 30d | **596.56x** (MySQL 191.00x) | **153.42x** (MySQL 45.70x) | **72.80x** (MySQL 17.80x) | **19.38x** (MySQL 5.49x) |
| all | **1734.25x** (MySQL 31.80x) | **468.30x** (MySQL 8.17x) | **81.93x** (MySQL 3.25x) | **75.15x** (MySQL 1.24x) |

### absolute medians (ms) - SQL Server LocalDB 2019 Express, this laptop. DO NOT compare to the MySQL EC2 ms.

- list  A0: min 111.040  median-of-cells 131.087  max 501.744 ms
- list  A1: min 0.147  median-of-cells 0.770  max 3.169 ms
- list  B: min 0.117  median-of-cells 0.130  max 0.160 ms
- count A0: min 103.267  median-of-cells 116.154  max 127.647 ms
- count A1: min 105.601  median-of-cells 133.119  max 152.719 ms
- count B: min 0.047  median-of-cells 0.251  max 7.255 ms

### worst cell (all-time, COMPLETED) per arm, ms

- list: A0=501.744  A1=0.147  B=0.140
- count: A0=111.542  A1=128.548  B=1.710

### where does B lose to A1 (ratio < 1)?

- list: COMPLETED/7d 0.98x
- count: none

### noise: max IQR/median over cells (%) per arm

- A0 list: 40%
- A0 count: 41%
- A1 list: 108%
- A1 count: 59%
- B list: 104%
- B count: 142%

### logical reads (pages) median - a few cells

- COMPLETED/all/list: A0=5152  A1=96  B=66
- COMPLETED/all/count: A0=5152  A1=5152  B=59
- COMPLETED/30d/list: A0=5152  A1=96  B=66
- COMPLETED/30d/count: A0=5152  A1=5152  B=239
- FAILED/30d/list: A0=5152  A1=2318  B=66
- FAILED/30d/count: A0=5152  A1=5152  B=10
- FAILED/all/list: A0=5152  A1=2320  B=66
- FAILED/all/count: A0=5152  A1=5152  B=6
