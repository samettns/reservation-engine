# Sellout before / after stage 5b

> ⚠️ **Preliminary: single run per cell**, before and after measured back to back in one session.

Requests/second (all answers) and p99, 1,000 units, 20,000 requests. `redis` = Redis + PostgreSQL.

| strategy | shards | VUs | before req/s | after req/s | change | before p99 ms | after p99 ms | after unsold |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| atomic | 1 | 10 | 1168 | 1222 | +5% | 50 | 58 | 0 |
| atomic | 1 | 100 | 2074 | 2364 | +14% | 482 | 545 | 0 |
| atomic | 1 | 1.000 | 2122 | 2457 | +16% | 3321 | 3789 | 0 |
| atomic | 16 | 10 | 1192 | 3552 | +198% | 14 | 13 | 0 |
| atomic | 16 | 100 | 1308 | 5324 | +307% | 256 | 120 | 0 |
| atomic | 16 | 1.000 | 1564 | 4314 | +176% | 2324 | 1127 | 0 |
| pessimistic-precheck | 1 | 10 | 993 | 1421 | +43% | 36 | 43 | 0 |
| pessimistic-precheck | 1 | 100 | 1410 | 2615 | +85% | 745 | 567 | 0 |
| pessimistic-precheck | 1 | 1.000 | 1980 | 2822 | +43% | 3826 | 3602 | 0 |
| pessimistic-precheck | 16 | 10 | 1033 | 3835 | +271% | 19 | 15 | 0 |
| pessimistic-precheck | 16 | 100 | 1356 | 3844 | +184% | 206 | 186 | 0 |
| pessimistic-precheck | 16 | 1.000 | 1186 | 4017 | +239% | 2560 | 1613 | 0 |
| redis | 1 | 10 | 964 | 1318 | +37% | 58 | 44 | 0 |
| redis | 1 | 100 | 2217 | 2823 | +27% | 577 | 493 | 0 |
| redis | 1 | 1.000 | 1917 | 3089 | +61% | 4717 | 3429 | 0 |
| redis | 16 | 10 | 2686 | 2902 | +8% | 9 | 15 | 0 |
| redis | 16 | 100 | 4161 | 6336 | +52% | 103 | 88 | 0 |
| redis | 16 | 1.000 | 4487 | 6120 | +36% | 1446 | 974 | 0 |
