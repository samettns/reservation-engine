**500 oranı (yanıtların)**

| Strateji | Mod | before | no-shed-noretry | after-100-noretry |
|---|---|---|---|---|
| atomic | contention | 78.9% | 0.0% | 0.0% |
| naive | contention | 80.4% | 0.0% | 0.0% |
| optimistic | contention | 81.6% | 0.0% | 0.0% |
| pessimistic | contention | 78.2% | 0.0% | 0.0% |
| redis | contention | 82.7% | 0.0% | 0.0% |
| atomic | sellout | 8.8% | 0.0% | 0.0% |
| naive | sellout | 60.1% | 0.0% | 0.0% |
| optimistic | sellout | 12.3% | 0.0% | 0.0% |
| pessimistic | sellout | 13.1% | 0.0% | 0.0% |
| redis | sellout | 5.8% | 0.0% | 0.0% |

**503 oranı (yanıtların; tekrar denemeler dahil)**

| Strateji | Mod | before | no-shed-noretry | after-100-noretry |
|---|---|---|---|---|
| atomic | contention | 0.0% | 99.7% | 99.3% |
| naive | contention | 0.0% | 99.6% | 99.3% |
| optimistic | contention | 0.0% | 99.7% | 99.4% |
| pessimistic | contention | 0.0% | 99.7% | 99.4% |
| redis | contention | 0.0% | 99.7% | 99.4% |
| atomic | sellout | 0.0% | 99.3% | 97.0% |
| naive | sellout | 0.0% | 99.0% | 97.1% |
| optimistic | sellout | 0.0% | 99.3% | 97.9% |
| pessimistic | sellout | 0.0% | 99.2% | 97.8% |
| redis | sellout | 0.0% | 99.2% | 97.0% |

**Başarılı rezervasyon / sn**

| Strateji | Mod | before | no-shed-noretry | after-100-noretry |
|---|---|---|---|---|
| atomic | contention | 135.0 | 2.3 | 69.9 |
| naive | contention | 134.4 | 2.1 | 67.2 |
| optimistic | contention | 126.4 | 2.1 | 59.9 |
| pessimistic | contention | 148.6 | 2.3 | 59.7 |
| redis | contention | 116.8 | 0.9 | 54.7 |
| atomic | sellout | 97.6 | 13.0 | 213.0 |
| naive | sellout | 266.1 | 17.3 | 184.2 |
| optimistic | sellout | 94.1 | 12.6 | 144.0 |
| pessimistic | sellout | 90.5 | 13.3 | 174.7 |
| redis | sellout | 101.7 | 13.1 | 189.0 |

**Başarılı isteğin p99'u, ms (tek deneme)**

| Strateji | Mod | before | no-shed-noretry | after-100-noretry |
|---|---|---|---|---|
| atomic | contention | 14,868 | 15,002 | 6,190 |
| naive | contention | 12,740 | 14,814 | 6,729 |
| optimistic | contention | 12,718 | 14,729 | 7,401 |
| pessimistic | contention | 14,620 | 16,465 | 7,667 |
| redis | contention | 13,456 | 8,074 | 7,366 |
| atomic | sellout | 4,546 | 10,657 | 2,515 |
| naive | sellout | 14,283 | 11,329 | 2,275 |
| optimistic | sellout | 5,864 | 11,198 | 2,174 |
| pessimistic | sellout | 4,613 | 11,490 | 1,904 |
| redis | sellout | 5,358 | 12,061 | 2,620 |

**Başarılı isteğin p99'u, ms (ilk denemeden 201'e, beklemeler dahil)**

| Strateji | Mod | before | no-shed-noretry | after-100-noretry |
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

**10 denemede vazgeçen istemci**

| Strateji | Mod | before | no-shed-noretry | after-100-noretry |
|---|---|---|---|---|
| atomic | contention | 0 | 0 | 0 |
| naive | contention | 0 | 0 | 0 |
| optimistic | contention | 0 | 0 | 0 |
| pessimistic | contention | 0 | 0 | 0 |
| redis | contention | 0 | 0 | 0 |
| atomic | sellout | 0 | 0 | 0 |
| naive | sellout | 0 | 0 | 0 |
| optimistic | sellout | 0 | 0 | 0 |
| pessimistic | sellout | 0 | 0 | 0 |
| redis | sellout | 0 | 0 | 0 |

**Yanıt vermez olan instance (OOM)**

| Strateji | Mod | before | no-shed-noretry | after-100-noretry |
|---|---|---|---|---|
| atomic | contention | - | 8082 | - |
| naive | contention | - | 8082 | - |
| optimistic | contention | - | 8082 | - |
| pessimistic | contention | - | 8081 | - |
| redis | contention | - | 8082 | - |
| atomic | sellout | - | - | - |
| naive | sellout | - | - | - |
| optimistic | sellout | - | - | - |
| pessimistic | sellout | - | - | - |
| redis | sellout | - | - | - |

