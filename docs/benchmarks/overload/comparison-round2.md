**Başarılı rezervasyon / sn**

| Strateji | Mod | http-100 | db-20 |
|---|---|---|---|
| atomic | contention | 237.2 | 183.0 |
| naive | contention | 243.2 | 200.6 |
| optimistic | contention | 229.7 | 179.6 |
| pessimistic | contention | 234.1 | 191.4 |
| redis | contention | 218.2 | 168.9 |
| atomic | sellout | 109.8 | 98.1 |
| naive | sellout | 321.2 | 255.6 |
| optimistic | sellout | 101.7 | 86.9 |
| pessimistic | sellout | 108.8 | 91.1 |
| redis | sellout | 113.7 | 96.3 |

**Başarılı isteğin p99'u, ms (tek deneme)**

| Strateji | Mod | http-100 | db-20 |
|---|---|---|---|
| atomic | contention | 2,045 | 3,438 |
| naive | contention | 2,043 | 2,598 |
| optimistic | contention | 2,098 | 2,661 |
| pessimistic | contention | 2,131 | 2,286 |
| redis | contention | 2,410 | 2,517 |
| atomic | sellout | 1,579 | 1,232 |
| naive | sellout | 1,698 | 1,312 |
| optimistic | sellout | 1,630 | 1,695 |
| pessimistic | sellout | 1,488 | 1,644 |
| redis | sellout | 1,933 | 1,639 |

**Başarılı isteğin p99'u, ms (ilk denemeden 201'e, Retry-After beklemeleri dahil)**

| Strateji | Mod | http-100 | db-20 |
|---|---|---|---|
| atomic | contention | 22,480 | 24,105 |
| naive | contention | 22,915 | 23,360 |
| optimistic | contention | 22,792 | 23,495 |
| pessimistic | contention | 22,277 | 23,278 |
| redis | contention | 22,396 | 23,870 |
| atomic | sellout | 3,521 | 4,150 |
| naive | sellout | 20,971 | 20,877 |
| optimistic | sellout | 3,591 | 5,498 |
| pessimistic | sellout | 3,389 | 5,069 |
| redis | sellout | 3,645 | 4,839 |

**Sellout sonunda satılmadan kalan stok (1.000'den)**

| Strateji | Mod | http-100 | db-20 |
|---|---|---|---|
| atomic | contention | - | - |
| naive | contention | - | - |
| optimistic | contention | - | - |
| pessimistic | contention | - | - |
| redis | contention | - | - |
| atomic | sellout | 0 | 0 |
| naive | sellout | 725 | 610 |
| optimistic | sellout | 0 | 0 |
| pessimistic | sellout | 0 | 0 |
| redis | sellout | 0 | 0 |

**Toplam 503 sayısı (tekrar denemeler dahil)**

| Strateji | Mod | http-100 | db-20 |
|---|---|---|---|
| atomic | contention | 57,857 | 55,107 |
| naive | contention | 57,277 | 56,241 |
| optimistic | contention | 58,375 | 57,009 |
| pessimistic | contention | 58,444 | 58,042 |
| redis | contention | 60,075 | 57,277 |
| atomic | sellout | 16,845 | 14,483 |
| naive | sellout | 92,510 | 115,583 |
| optimistic | sellout | 17,004 | 17,219 |
| pessimistic | sellout | 17,126 | 17,629 |
| redis | sellout | 14,304 | 13,841 |

**500 oranı**

| Strateji | Mod | http-100 | db-20 |
|---|---|---|---|
| atomic | contention | 0.0% | 0.0% |
| naive | contention | 0.0% | 0.0% |
| optimistic | contention | 0.0% | 0.0% |
| pessimistic | contention | 0.0% | 0.0% |
| redis | contention | 0.0% | 0.0% |
| atomic | sellout | 0.0% | 0.0% |
| naive | sellout | 0.0% | 0.0% |
| optimistic | sellout | 0.0% | 0.0% |
| pessimistic | sellout | 0.0% | 0.0% |
| redis | sellout | 0.0% | 0.0% |

**10 denemede vazgeçen istemci**

| Strateji | Mod | http-100 | db-20 |
|---|---|---|---|
| atomic | contention | 3238 | 2874 |
| naive | contention | 3112 | 2744 |
| optimistic | contention | 3130 | 2959 |
| pessimistic | contention | 3250 | 3028 |
| redis | contention | 3010 | 3231 |
| atomic | sellout | 0 | 0 |
| naive | sellout | 5867 | 6540 |
| optimistic | sellout | 0 | 0 |
| pessimistic | sellout | 0 | 0 |
| redis | sellout | 0 | 0 |

**Ölen instance (yanıtsız kalan / OOM ile kapanıp yeniden başlayan)**

| Strateji | Mod | http-100 | db-20 |
|---|---|---|---|
| atomic | contention | - | - |
| naive | contention | - | - |
| optimistic | contention | - | - |
| pessimistic | contention | - | - |
| redis | contention | - | - |
| atomic | sellout | - | - |
| naive | sellout | - | - |
| optimistic | sellout | - | - |
| pessimistic | sellout | - | - |
| redis | sellout | - | - |

