**Başarılı rezervasyon / sn**

| Strateji | Mod | no-shed | after-100 | after-20 |
|---|---|---|---|---|
| atomic | contention | 16.0 | 213.4 | 153.9 |
| naive | contention | 41.6 | 223.3 | 231.0 |
| optimistic | contention | 68.5 | 195.6 | 192.9 |
| pessimistic | contention | 19.6 | 219.9 | 221.1 |
| redis | contention | 21.6 | 198.7 | 239.3 |
| atomic | sellout | 84.3 | 88.8 | 127.4 |
| naive | sellout | 136.2 | 234.5 | 265.5 |
| optimistic | sellout | 124.8 | 115.9 | 134.2 |
| pessimistic | sellout | 73.8 | 105.5 | 71.7 |
| redis | sellout | 102.0 | 121.5 | 67.1 |

**Başarılı isteğin p99'u, ms (tek deneme)**

| Strateji | Mod | no-shed | after-100 | after-20 |
|---|---|---|---|---|
| atomic | contention | 12,735 | 2,436 | 2,151 |
| naive | contention | 7,770 | 2,126 | 1,131 |
| optimistic | contention | 5,404 | 2,638 | 1,228 |
| pessimistic | contention | 8,771 | 2,200 | 1,097 |
| redis | contention | 13,806 | 2,612 | 1,029 |
| atomic | sellout | 3,100 | 2,440 | 1,102 |
| naive | sellout | 2,682 | 2,414 | 991 |
| optimistic | sellout | 4,137 | 1,944 | 1,206 |
| pessimistic | sellout | 3,197 | 2,102 | 1,226 |
| redis | sellout | 3,704 | 2,260 | 1,078 |

**Başarılı isteğin p99'u, ms (ilk denemeden 201'e, Retry-After beklemeleri dahil)**

| Strateji | Mod | no-shed | after-100 | after-20 |
|---|---|---|---|---|
| atomic | contention | 16,085 | 11,038 | 12,147 |
| naive | contention | 15,965 | 11,153 | 9,360 |
| optimistic | contention | 8,561 | 10,763 | 9,399 |
| pessimistic | contention | 9,475 | 11,299 | 9,427 |
| redis | contention | 18,786 | 10,781 | 9,476 |
| atomic | sellout | 7,217 | 5,455 | 3,608 |
| naive | sellout | 6,390 | 13,465 | 9,386 |
| optimistic | sellout | 7,368 | 3,942 | 5,621 |
| pessimistic | sellout | 8,415 | 4,167 | 3,623 |
| redis | sellout | 7,412 | 4,764 | 4,009 |

**Sellout sonunda satılmadan kalan stok (1.000'den)**

| Strateji | Mod | no-shed | after-100 | after-20 |
|---|---|---|---|---|
| atomic | contention | - | - | - |
| naive | contention | - | - | - |
| optimistic | contention | - | - | - |
| pessimistic | contention | - | - | - |
| redis | contention | - | - | - |
| atomic | sellout | 0 | 0 | 0 |
| naive | sellout | 965 | 831 | 864 |
| optimistic | sellout | 0 | 0 | 240 |
| pessimistic | sellout | 0 | 0 | 0 |
| redis | sellout | 0 | 0 | 0 |

**Toplam 503 sayısı (tekrar denemeler dahil)**

| Strateji | Mod | no-shed | after-100 | after-20 |
|---|---|---|---|---|
| atomic | contention | 38,769 | 120,326 | 81,929 |
| naive | contention | 39,074 | 114,107 | 136,549 |
| optimistic | contention | 34,554 | 114,314 | 68,821 |
| pessimistic | contention | 28,966 | 114,713 | 136,660 |
| redis | contention | 43,407 | 113,397 | 127,010 |
| atomic | sellout | 28,775 | 25,159 | 28,869 |
| naive | sellout | 13,550 | 145,773 | 140,205 |
| optimistic | sellout | 19,657 | 28,622 | 7,450 |
| pessimistic | sellout | 33,557 | 31,207 | 39,332 |
| redis | sellout | 20,097 | 29,132 | 40,313 |

**500 oranı**

| Strateji | Mod | no-shed | after-100 | after-20 |
|---|---|---|---|---|
| atomic | contention | 0.0% | 0.0% | 0.0% |
| naive | contention | 0.0% | 0.0% | 0.0% |
| optimistic | contention | 0.0% | 0.0% | 0.0% |
| pessimistic | contention | 0.0% | 0.0% | 0.0% |
| redis | contention | 0.0% | 0.0% | 0.0% |
| atomic | sellout | 0.0% | 0.0% | 0.0% |
| naive | sellout | 0.0% | 0.0% | 0.0% |
| optimistic | sellout | 0.0% | 0.0% | 0.0% |
| pessimistic | sellout | 0.0% | 0.0% | 0.0% |
| redis | sellout | 0.0% | 0.0% | 0.0% |

**10 denemede vazgeçen istemci**

| Strateji | Mod | no-shed | after-100 | after-20 |
|---|---|---|---|---|
| atomic | contention | 0 | 9393 | 5334 |
| naive | contention | 56 | 8793 | 11931 |
| optimistic | contention | 0 | 8734 | 3600 |
| pessimistic | contention | 0 | 8568 | 12180 |
| redis | contention | 504 | 8666 | 10810 |
| atomic | sellout | 0 | 0 | 0 |
| naive | sellout | 0 | 10731 | 11942 |
| optimistic | sellout | 0 | 0 | 0 |
| pessimistic | sellout | 0 | 0 | 138 |
| redis | sellout | 0 | 0 | 242 |

**Ölen instance (yanıtsız kalan / OOM ile kapanıp yeniden başlayan)**

| Strateji | Mod | no-shed | after-100 | after-20 |
|---|---|---|---|---|
| atomic | contention | - | - | - |
| naive | contention | - | - | - |
| optimistic | contention | - | - | - |
| pessimistic | contention | - | - | - |
| redis | contention | - | - | - |
| atomic | sellout | - | - | - |
| naive | sellout | - | - | - |
| optimistic | sellout | - | - | - |
| pessimistic | sellout | - | - | - |
| redis | sellout | - | - | - |

