# Reservation Engine

Yüksek eşzamanlılık altında sınırlı stoğu satan (konser bileti, flaş indirim) ve
**overselling'i önleyen** çekirdek servis. Arayüz yok; odak doğruluk ve ölçülebilir performans.

> 🚧 Geliştirme aşamasında. Tam README (mimari, benchmark grafikleri, trade-off'lar) son aşamada eklenecek.

## Hızlı başlangıç

Gereksinimler: Java 21, Docker (Compose v2).

```bash
docker compose up -d --wait      # PostgreSQL, Redis, Kafka
./mvnw spring-boot:run           # uygulama: http://localhost:8080
./mvnw verify                    # testler (Testcontainers ile gerçek PostgreSQL)
```

## API

```bash
# Etkinlik oluştur
curl -X POST localhost:8080/events -H 'Content-Type: application/json' \
     -d '{"name":"konser","stock":100}'

# Rezervasyon (aynı Idempotency-Key ile tekrar gönderilirse aynı sonuç döner)
curl -X POST localhost:8080/events/1/reserve -H 'Content-Type: application/json' \
     -H 'Idempotency-Key: 7f1c...' -d '{"userId":"alice"}'

# Stok durumu
curl localhost:8080/events/1/stock
```

| Durum | HTTP |
|---|---|
| Rezervasyon oluşturuldu | `201 Created` |
| Aynı istek tekrar geldi (replay) | `200 OK` + `Idempotent-Replayed: true` |
| Stok tükendi | `409 Conflict` |
| Etkinlik yok | `404 Not Found` |
| Key başka bir etkinlik için kullanılmış | `422 Unprocessable Entity` |
| `Idempotency-Key` eksik / geçersiz gövde | `400 Bad Request` |
| Çekişme yüzünden vazgeçildi (optimistic, deneme hakkı bitti) | `503 Service Unavailable` + `Retry-After` |

## Stratejiler

`RESERVATION_STRATEGY` ortam değişkeniyle seçilir (varsayılan `atomic`):

| Ad | Yöntem |
|---|---|
| `naive` | Oku, kontrol et, yaz. **Bilerek hatalı**; overselling'i göstermek için var |
| `atomic` | `UPDATE ... SET qty = qty - 1 WHERE ... AND qty > 0`, tek ifade |
| `optimistic` | Version kolonu ile compare-and-set, sınırlı yeniden deneme |
| `pessimistic` | `SELECT ... FOR UPDATE`, sonra yazma |
| `redis` | Redis'te Lua script ile atomik kapı, ardından PostgreSQL'de atomik UPDATE. Kaynak doğruluk PostgreSQL |

## Bilinen sınırlamalar

| Sınırlama | Etki | Çözüm planı |
|---|---|---|
| **Redis stratejisinde eşzamanlı idempotent tekrarlar.** Aynı `Idempotency-Key` ile gelen kopyalar, ilk istek henüz commit edilmemişken Redis sayacını geçici olarak 0 görüp `409` alabilir. | Stok doğru kalır (overselling yok), ama başarılı olmuş bir isteğin kopyasına "tükendi" denir. Ölçüm: stok 1'de 100 eşzamanlı kopyadan ~8'i. DB stratejileri etkilenmez. | Aşama 5: istek işlenirken idempotency kaydını "işlemde" olarak tutmak. |
| **Redis düşüşü ile DB sonucu arasında çökme.** Redis'ten birim düşüldükten sonra, DB transaction'ı sonuçlanıp telafi (`INCR`) çalışmadan uygulama çökerse, düşülen birim Redis'e hiç iade edilmez. | Redis sayacı DB'den düşük kalır: o birimler satılamaz (undersell). Overselling'e yol açmaz, çünkü son kararı DB veriyor. | Aşama 7: Redis sayacını DB'deki kalan stokla eşitleyen bir mutabakat (reconciliation) işi. |
