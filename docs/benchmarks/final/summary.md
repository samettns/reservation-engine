# Final benchmark (Aşama 8)

Ortalama ± standart sapma, n = tekrar sayısı. Aynı kod; her karşılaştırmada tek bir ayar farklı.
`naive` dışında fazla satış görülen koşu: 0.

### A. Strateji karşılaştırması (1 shard)

| Konfigürasyon | Strateji | Mod | Shard | VU | n | rezervasyon/sn | istek/sn | başarılı p99 ms | tüm istekler p99 ms | tekrarlar (rezervasyon/sn) |
|---|---|---|---|---|---|---|---|---|---|---|
| A-strategies | atomic | contention | 1 | 100 | 3 | 288 ± 41 | 289 ± 40 | 1,316 ± 226 | 1,315 ± 225 | 296 / 324 / 243 |
| A-strategies | atomic | contention | 1 | 1000 | 3 | 280 ± 26 | 603 ± 29 | 1,730 ± 116 | 1,562 ± 80 | 250 / 298 / 293 |
| A-strategies | atomic | sellout | 1 | 100 | 3 | 187 ± 39 | 3,736 ± 789 | 1,014 ± 89 | 415 ± 44 | 154 / 230 / 176 |
| A-strategies | atomic | sellout | 1 | 1000 | 3 | 118 ± 8 | 2,645 ± 150 | 1,353 ± 92 | 748 ± 28 | 110 / 124 / 120 |
| A-strategies | naive | contention | 1 | 100 | 3 | 294 ± 47 | 296 ± 46 | 1,431 ± 186 | 1,426 ± 184 | 281 / 347 / 256 |
| A-strategies | naive | contention | 1 | 1000 | 3 | 273 ± 32 | 593 ± 32 | 1,837 ± 114 | 1,622 ± 71 | 261 / 308 / 249 |
| A-strategies | naive | sellout | 1 | 100 | 3 | 406 ± 14 | 407 ± 14 | 1,140 ± 59 | 1,140 ± 58 | 398 / 421 / 397 |
| A-strategies | naive | sellout | 1 | 1000 | 3 | 385 ± 16 | 733 ± 19 | 1,526 ± 48 | 1,390 ± 39 | 367 / 396 / 392 |
| A-strategies | optimistic | contention | 1 | 100 | 3 | 293 ± 30 | 295 ± 30 | 1,272 ± 46 | 1,271 ± 45 | 266 / 289 / 325 |
| A-strategies | optimistic | contention | 1 | 1000 | 3 | 269 ± 21 | 594 ± 25 | 1,806 ± 111 | 1,609 ± 56 | 248 / 269 / 291 |
| A-strategies | optimistic | sellout | 1 | 100 | 3 | 156 ± 16 | 3,120 ± 317 | 1,200 ± 160 | 516 ± 48 | 139 / 158 / 170 |
| A-strategies | optimistic | sellout | 1 | 1000 | 3 | 114 ± 10 | 2,562 ± 187 | 1,424 ± 120 | 790 ± 96 | 103 / 122 / 117 |
| A-strategies | pessimistic | contention | 1 | 100 | 3 | 296 ± 33 | 297 ± 33 | 1,263 ± 145 | 1,260 ± 142 | 257 / 311 / 318 |
| A-strategies | pessimistic | contention | 1 | 1000 | 3 | 269 ± 28 | 593 ± 32 | 1,784 ± 156 | 1,617 ± 87 | 237 / 286 / 284 |
| A-strategies | pessimistic | sellout | 1 | 100 | 3 | 158 ± 23 | 3,155 ± 463 | 1,123 ± 155 | 496 ± 45 | 131 / 170 / 172 |
| A-strategies | pessimistic | sellout | 1 | 1000 | 3 | 113 ± 11 | 2,556 ± 197 | 1,613 ± 498 | 828 ± 138 | 100 / 120 / 117 |
| A-strategies | redis | contention | 1 | 100 | 3 | 294 ± 55 | 295 ± 55 | 1,291 ± 131 | 1,291 ± 131 | 232 / 335 / 315 |
| A-strategies | redis | contention | 1 | 1000 | 3 | 292 ± 39 | 632 ± 39 | 1,747 ± 173 | 1,570 ± 96 | 247 / 317 / 313 |
| A-strategies | redis | sellout | 1 | 100 | 3 | 175 ± 11 | 3,498 ± 228 | 1,201 ± 101 | 468 ± 33 | 166 / 171 / 188 |
| A-strategies | redis | sellout | 1 | 1000 | 3 | 130 ± 3 | 2,879 ± 54 | 1,480 ± 67 | 748 ± 21 | 128 / 128 / 133 |

### B. 1 shard / 16 shard

| Konfigürasyon | Strateji | Mod | Shard | VU | n | rezervasyon/sn | istek/sn | başarılı p99 ms | tüm istekler p99 ms | tekrarlar (rezervasyon/sn) |
|---|---|---|---|---|---|---|---|---|---|---|
| A-strategies | atomic | contention | 1 | 100 | 3 | 288 ± 41 | 289 ± 40 | 1,316 ± 226 | 1,315 ± 225 | 296 / 324 / 243 |
| A-strategies | atomic | contention | 1 | 1000 | 3 | 280 ± 26 | 603 ± 29 | 1,730 ± 116 | 1,562 ± 80 | 250 / 298 / 293 |
| A-strategies | atomic | sellout | 1 | 100 | 3 | 187 ± 39 | 3,736 ± 789 | 1,014 ± 89 | 415 ± 44 | 154 / 230 / 176 |
| A-strategies | atomic | sellout | 1 | 1000 | 3 | 118 ± 8 | 2,645 ± 150 | 1,353 ± 92 | 748 ± 28 | 110 / 124 / 120 |
| A-strategies | optimistic | contention | 1 | 100 | 3 | 293 ± 30 | 295 ± 30 | 1,272 ± 46 | 1,271 ± 45 | 266 / 289 / 325 |
| A-strategies | optimistic | contention | 1 | 1000 | 3 | 269 ± 21 | 594 ± 25 | 1,806 ± 111 | 1,609 ± 56 | 248 / 269 / 291 |
| A-strategies | optimistic | sellout | 1 | 100 | 3 | 156 ± 16 | 3,120 ± 317 | 1,200 ± 160 | 516 ± 48 | 139 / 158 / 170 |
| A-strategies | optimistic | sellout | 1 | 1000 | 3 | 114 ± 10 | 2,562 ± 187 | 1,424 ± 120 | 790 ± 96 | 103 / 122 / 117 |
| A-strategies | pessimistic | contention | 1 | 100 | 3 | 296 ± 33 | 297 ± 33 | 1,263 ± 145 | 1,260 ± 142 | 257 / 311 / 318 |
| A-strategies | pessimistic | contention | 1 | 1000 | 3 | 269 ± 28 | 593 ± 32 | 1,784 ± 156 | 1,617 ± 87 | 237 / 286 / 284 |
| A-strategies | pessimistic | sellout | 1 | 100 | 3 | 158 ± 23 | 3,155 ± 463 | 1,123 ± 155 | 496 ± 45 | 131 / 170 / 172 |
| A-strategies | pessimistic | sellout | 1 | 1000 | 3 | 113 ± 11 | 2,556 ± 197 | 1,613 ± 498 | 828 ± 138 | 100 / 120 / 117 |
| B-precheck1 | pessimistic-precheck | contention | 1 | 100 | 3 | 335 ± 15 | 336 ± 15 | 1,315 ± 25 | 1,316 ± 24 | 351 / 321 / 334 |
| B-precheck1 | pessimistic-precheck | contention | 1 | 1000 | 3 | 303 ± 3 | 636 ± 6 | 1,775 ± 39 | 1,610 ± 3 | 299 / 306 / 304 |
| B-precheck1 | pessimistic-precheck | sellout | 1 | 100 | 3 | 175 ± 7 | 3,507 ± 143 | 1,185 ± 50 | 461 ± 18 | 181 / 167 / 178 |
| B-precheck1 | pessimistic-precheck | sellout | 1 | 1000 | 3 | 126 ± 11 | 2,837 ± 248 | 1,425 ± 64 | 719 ± 48 | 122 / 139 / 119 |
| B-shards16 | atomic | contention | 16 | 100 | 3 | 1,433 ± 30 | 1,433 ± 30 | 282 ± 5 | 282 ± 5 | 1,467 / 1,422 / 1,410 |
| B-shards16 | atomic | contention | 16 | 1000 | 3 | 1,689 ± 51 | 2,020 ± 58 | 460 ± 15 | 450 ± 15 | 1,713 / 1,723 / 1,631 |
| B-shards16 | atomic | sellout | 16 | 100 | 3 | 287 ± 23 | 5,742 ± 469 | 169 ± 11 | 85.6 ± 6.4 | 311 / 264 / 286 |
| B-shards16 | atomic | sellout | 16 | 1000 | 3 | 176 ± 6 | 3,775 ± 138 | 353 ± 29 | 236 ± 6 | 183 / 170 / 176 |
| B-shards16 | optimistic | contention | 16 | 100 | 3 | 1,171 ± 73 | 1,171 ± 73 | 323 ± 19 | 323 ± 19 | 1,167 / 1,247 / 1,100 |
| B-shards16 | optimistic | contention | 16 | 1000 | 3 | 1,482 ± 63 | 1,818 ± 73 | 519 ± 13 | 505 ± 10 | 1,547 / 1,477 / 1,421 |
| B-shards16 | optimistic | sellout | 16 | 100 | 3 | 277 ± 22 | 5,539 ± 447 | 166 ± 20 | 96.1 ± 7.5 | 300 / 276 / 255 |
| B-shards16 | optimistic | sellout | 16 | 1000 | 3 | 175 ± 19 | 3,788 ± 408 | 405 ± 88 | 263 ± 27 | 196 / 159 / 171 |
| B-shards16 | pessimistic | contention | 16 | 100 | 3 | 1,135 ± 34 | 1,135 ± 34 | 337 ± 20 | 337 ± 20 | 1,154 / 1,155 / 1,096 |
| B-shards16 | pessimistic | contention | 16 | 1000 | 3 | 1,448 ± 45 | 1,791 ± 42 | 547 ± 22 | 531 ± 23 | 1,488 / 1,400 / 1,456 |
| B-shards16 | pessimistic | sellout | 16 | 100 | 3 | 315 ± 10 | 6,295 ± 205 | 170 ± 25 | 89.3 ± 5.8 | 306 / 312 / 326 |
| B-shards16 | pessimistic | sellout | 16 | 1000 | 3 | 188 ± 27 | 4,023 ± 579 | 407 ± 9 | 271 ± 3 | 176 / 169 / 218 |
| B-shards16 | pessimistic-precheck | contention | 16 | 100 | 3 | 1,092 ± 36 | 1,092 ± 36 | 353 ± 10 | 353 ± 10 | 1,074 / 1,069 / 1,134 |
| B-shards16 | pessimistic-precheck | contention | 16 | 1000 | 3 ¹ | 901 ± 51 | 1,234 ± 37 | 776 ± 65 | 764 ± 71 | 958 / 881 / 862 |
| B-shards16 | pessimistic-precheck | sellout | 16 | 100 | 3 | 290 ± 12 | 5,803 ± 243 | 172 ± 7 | 88.5 ± 1.7 | 277 / 300 / 293 |
| B-shards16 | pessimistic-precheck | sellout | 16 | 1000 | 3 | 167 ± 4 | 3,587 ± 86 | 411 ± 26 | 265 ± 18 | 166 / 172 / 164 |

### C. "Tükendi" kısayolları açık / kapalı (sellout)

| Konfigürasyon | Strateji | Mod | Shard | VU | n | rezervasyon/sn | istek/sn | başarılı p99 ms | tüm istekler p99 ms | tekrarlar (rezervasyon/sn) |
|---|---|---|---|---|---|---|---|---|---|---|
| A-strategies | atomic | sellout | 1 | 100 | 3 | 187 ± 39 | 3,736 ± 789 | 1,014 ± 89 | 415 ± 44 | 154 / 230 / 176 |
| A-strategies | atomic | sellout | 1 | 1000 | 3 | 118 ± 8 | 2,645 ± 150 | 1,353 ± 92 | 748 ± 28 | 110 / 124 / 120 |
| A-strategies | pessimistic | sellout | 1 | 100 | 3 | 158 ± 23 | 3,155 ± 463 | 1,123 ± 155 | 496 ± 45 | 131 / 170 / 172 |
| A-strategies | pessimistic | sellout | 1 | 1000 | 3 | 113 ± 11 | 2,556 ± 197 | 1,613 ± 498 | 828 ± 138 | 100 / 120 / 117 |
| A-strategies | redis | sellout | 1 | 100 | 3 | 175 ± 11 | 3,498 ± 228 | 1,201 ± 101 | 468 ± 33 | 166 / 171 / 188 |
| A-strategies | redis | sellout | 1 | 1000 | 3 | 130 ± 3 | 2,879 ± 54 | 1,480 ± 67 | 748 ± 21 | 128 / 128 / 133 |
| B-shards16 | atomic | sellout | 16 | 100 | 3 | 287 ± 23 | 5,742 ± 469 | 169 ± 11 | 85.6 ± 6.4 | 311 / 264 / 286 |
| B-shards16 | atomic | sellout | 16 | 1000 | 3 | 176 ± 6 | 3,775 ± 138 | 353 ± 29 | 236 ± 6 | 183 / 170 / 176 |
| B-shards16 | pessimistic | sellout | 16 | 100 | 3 | 315 ± 10 | 6,295 ± 205 | 170 ± 25 | 89.3 ± 5.8 | 306 / 312 / 326 |
| B-shards16 | pessimistic | sellout | 16 | 1000 | 3 | 188 ± 27 | 4,023 ± 579 | 407 ± 9 | 271 ± 3 | 176 / 169 / 218 |
| C-redis-verify-stale-only | redis | sellout | 1 | 100 | 3 | 118 ± 13 | 2,365 ± 264 | 1,364 ± 183 | 559 ± 53 | 133 / 114 / 107 |
| C-redis-verify-stale-only | redis | sellout | 1 | 1000 | 3 | 101 ± 7 | 2,328 ± 161 | 2,111 ± 103 | 1,173 ± 28 | 99 / 108 / 95 |
| C-redis-verify-stale-only | redis | sellout | 16 | 100 | 3 | 386 ± 140 | 7,711 ± 2,802 | 449 ± 139 | 116 ± 7 | 300 / 310 / 547 |
| C-redis-verify-stale-only | redis | sellout | 16 | 1000 | 3 | 203 ± 32 | 4,342 ± 687 | 497 ± 33 | 247 ± 16 | 241 / 185 / 184 |
| C-redis16-on | redis | sellout | 16 | 100 | 3 | 146 ± 3 | 2,919 ± 58 | 332 ± 13 | 168 ± 5 | 148 / 143 / 147 |
| C-redis16-on | redis | sellout | 16 | 1000 | 3 | 130 ± 12 | 2,926 ± 266 | 778 ± 36 | 536 ± 4 | 135 / 117 / 139 |
| C-shortcuts-off | atomic | sellout | 1 | 100 | 3 | 70.4 ± 4.9 | 1,408 ± 98 | 1,116 ± 38 | 493 ± 13 | 73 / 65 / 73 |
| C-shortcuts-off | atomic | sellout | 1 | 1000 | 3 | 73.8 ± 3.3 | 1,794 ± 80 | 1,502 ± 155 | 879 ± 44 | 74 / 77 / 70 |
| C-shortcuts-off | atomic | sellout | 16 | 100 | 3 | 87.8 ± 6.6 | 1,756 ± 133 | 168 ± 22 | 182 ± 9 | 92 / 80 / 91 |
| C-shortcuts-off | atomic | sellout | 16 | 1000 | 3 | 66.8 ± 3.2 | 1,659 ± 77 | 381 ± 15 | 486 ± 2 | 65 / 70 / 65 |
| C-shortcuts-off | pessimistic | sellout | 1 | 100 | 3 | 30.5 ± 1.3 | 610 ± 26 | 1,164 ± 68 | 541 ± 31 | 30 / 32 / 30 |
| C-shortcuts-off | pessimistic | sellout | 1 | 1000 | 3 | 30.7 ± 1.0 | 959 ± 16 | 1,408 ± 122 | 1,001 ± 44 | 32 / 30 / 30 |
| C-shortcuts-off | pessimistic | sellout | 16 | 100 | 3 | 41.4 ± 0.9 | 827 ± 17 | 243 ± 30 | 340 ± 19 | 40 / 42 / 41 |
| C-shortcuts-off | pessimistic | sellout | 16 | 1000 | 3 | 36.9 ± 0.2 | 1,082 ± 5 | 443 ± 45 | 878 ± 5 | 37 / 37 / 37 |
| C-shortcuts-off | redis | sellout | 1 | 100 | 3 | 62.3 ± 2.5 | 1,247 ± 50 | 1,202 ± 36 | 498 ± 43 | 60 / 63 / 64 |
| C-shortcuts-off | redis | sellout | 1 | 1000 | 3 | 67.6 ± 2.7 | 1,673 ± 73 | 1,813 ± 48 | 929 ± 18 | 66 / 71 / 66 |
| C-shortcuts-off | redis | sellout | 16 | 100 | 3 | 79.7 ± 6.2 | 1,594 ± 125 | 191 ± 39 | 189 ± 16 | 82 / 73 / 84 |
| C-shortcuts-off | redis | sellout | 16 | 1000 | 3 | 63.6 ± 2.8 | 1,588 ± 65 | 387 ± 18 | 512 ± 4 | 62 / 67 / 62 |

### C-ek. Redis kapısında SOLD_OUT yolu (her zaman / sadece bayatken doğrula)

| Konfigürasyon | Shard | VU | rezervasyon/sn | tekrarlar | kapıda SOLD_OUT (istek) | DB'de stok vardı |
|---|---|---|---|---|---|---|
| A-strategies | 1 | 100 | 175 ± 11 | 166 / 171 / 188 | 38.0 ± 1.0 | 24.0 ± 1.0 |
| A-strategies | 1 | 1000 | 130 ± 3 | 128 / 128 / 133 | 116 ± 16 | 35.3 ± 4.6 |
| C-redis-verify-stale-only | 1 | 100 | 118 ± 13 | 133 / 114 / 107 | 19.3 ± 1.5 | — (doğrulanmıyor) |
| C-redis-verify-stale-only | 1 | 1000 | 101 ± 7 | 99 / 108 / 95 | 87.7 ± 12.0 | — (doğrulanmıyor) |
| C-redis-verify-stale-only | 16 | 100 | 386 ± 140 | 300 / 310 / 547 | 26.3 ± 1.5 | — (doğrulanmıyor) |
| C-redis-verify-stale-only | 16 | 1000 | 203 ± 32 | 241 / 185 / 184 | 121 ± 9 | — (doğrulanmıyor) |
| C-redis16-on | 16 | 100 | 146 ± 3 | 148 / 143 / 147 | 44.7 ± 4.2 | 22.7 ± 5.1 |
| C-redis16-on | 16 | 1000 | 130 ± 12 | 135 / 117 / 139 | 165 ± 10 | 29.7 ± 6.5 |
| C-shortcuts-off | 1 | 100 | 62.3 ± 2.5 | 60 / 63 / 64 | 19,000 ± 0 | 28.3 ± 0.6 |
| C-shortcuts-off | 1 | 1000 | 67.6 ± 2.7 | 66 / 71 / 66 | 18,994 ± 3 | 35.0 ± 3.5 |
| C-shortcuts-off | 16 | 100 | 79.7 ± 6.2 | 82 / 73 / 84 | 19,000 ± 0 | 27.3 ± 2.1 |
| C-shortcuts-off | 16 | 1000 | 63.6 ± 2.8 | 62 / 67 / 62 | 18,999 ± 1 | 30.3 ± 2.9 |

"DB'de stok vardı": kapı `SOLD_OUT` dedi, doğrulamada DB'de birim bulundu ve satıldı. `stale-only` modunda doğrulama yapılmadığı için bu sayı ölçülemiyor (sayaç hep 0 kalır); yanlış "tükendi" olmadığına kanıt değildir.

### D. Transaction sırası: stok önce (A) / stok en sonda

| Konfigürasyon | Strateji | Mod | Shard | VU | n | rezervasyon/sn | istek/sn | başarılı p99 ms | tüm istekler p99 ms | tekrarlar (rezervasyon/sn) |
|---|---|---|---|---|---|---|---|---|---|---|
| A-strategies | atomic | contention | 1 | 100 | 3 | 288 ± 41 | 289 ± 40 | 1,316 ± 226 | 1,315 ± 225 | 296 / 324 / 243 |
| A-strategies | atomic | contention | 1 | 1000 | 3 | 280 ± 26 | 603 ± 29 | 1,730 ± 116 | 1,562 ± 80 | 250 / 298 / 293 |
| A-strategies | atomic | sellout | 1 | 100 | 3 | 187 ± 39 | 3,736 ± 789 | 1,014 ± 89 | 415 ± 44 | 154 / 230 / 176 |
| A-strategies | atomic | sellout | 1 | 1000 | 3 | 118 ± 8 | 2,645 ± 150 | 1,353 ± 92 | 748 ± 28 | 110 / 124 / 120 |
| A-strategies | pessimistic | contention | 1 | 100 | 3 | 296 ± 33 | 297 ± 33 | 1,263 ± 145 | 1,260 ± 142 | 257 / 311 / 318 |
| A-strategies | pessimistic | contention | 1 | 1000 | 3 | 269 ± 28 | 593 ± 32 | 1,784 ± 156 | 1,617 ± 87 | 237 / 286 / 284 |
| A-strategies | pessimistic | sellout | 1 | 100 | 3 | 158 ± 23 | 3,155 ± 463 | 1,123 ± 155 | 496 ± 45 | 131 / 170 / 172 |
| A-strategies | pessimistic | sellout | 1 | 1000 | 3 | 113 ± 11 | 2,556 ± 197 | 1,613 ± 498 | 828 ± 138 | 100 / 120 / 117 |
| D-stock-last | atomic | contention | 1 | 100 | 3 | 474 ± 8 | 474 ± 8 | 765 ± 24 | 768 ± 20 | 469 / 469 / 483 |
| D-stock-last | atomic | contention | 1 | 1000 | 3 | 441 ± 3 | 779 ± 3 | 1,211 ± 10 | 1,193 ± 8 | 440 / 438 / 445 |
| D-stock-last | atomic | sellout | 1 | 100 | 3 | 210 ± 3 | 4,199 ± 51 | 609 ± 22 | 292 ± 6 | 209 / 213 / 208 |
| D-stock-last | atomic | sellout | 1 | 1000 | 3 | 143 ± 16 | 3,162 ± 344 | 990 ± 67 | 591 ± 18 | 138 / 160 / 131 |
| D-stock-last | pessimistic | contention | 1 | 100 | 3 | 378 ± 10 | 379 ± 10 | 984 ± 23 | 988 ± 26 | 370 / 375 / 390 |
| D-stock-last | pessimistic | contention | 1 | 1000 | 3 | 350 ± 13 | 676 ± 20 | 1,403 ± 18 | 1,358 ± 31 | 334 / 358 / 357 |
| D-stock-last | pessimistic | sellout | 1 | 100 | 3 | 219 ± 39 | 4,379 ± 773 | 788 ± 50 | 347 ± 16 | 263 / 192 / 201 |
| D-stock-last | pessimistic | sellout | 1 | 1000 | 3 | 136 ± 12 | 3,019 ± 275 | 1,061 ± 108 | 633 ± 55 | 126 / 150 / 132 |

Dipnot:

- ¹ B-shards16 pessimistic-precheck contention, 16 shard, 1000 VU: tabloda **yeniden koşulan 3 tekrar** var. İlk 3 tekrar: rezervasyon/sn 393 / 1,337 / 1,403, Hikari zaman aşımı 2,303 / 49 / 60 (ilki aykırı değer). Bütün koşularda throughput, Hikari zaman aşımı sayısıyla ters yönde değişiyor.
