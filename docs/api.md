# API

Uç noktalar, istek örnekleri ve her durumun HTTP cevabı: rezervasyon, idempotent tekrar, tükendi, "işlemde", ödeme onayı ve süresi dolmuş tutum.

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
| Stok tükendi | `409 Conflict` (`urn:problem-type:sold-out`) |
| Aynı key'li başka bir istek şu an işleniyor | `409 Conflict` (`urn:problem-type:request-in-progress`) + `Retry-After` |
| Etkinlik yok | `404 Not Found` |
| Key başka bir etkinlik için kullanılmış | `422 Unprocessable Entity` |
| `Idempotency-Key` eksik / geçersiz gövde | `400 Bad Request` |
| Çekişme yüzünden vazgeçildi (optimistic, deneme hakkı bitti) | `503 Service Unavailable` + `Retry-After` |

Rezervasyon önce **`HELD`** olarak oluşur ve `expiresAt`'e kadar (varsayılan 10 dk, `HOLD_TTL`) ödeme bekler:

```bash
curl -X POST localhost:8080/reservations/{reservationId}/confirm   # ödeme callback'i
```

| Ödeme onayı | HTTP |
|---|---|
| Süre dolmadan onaylandı | `200 OK` (`CONFIRMED`) |
| Tekrarlanan callback | `200 OK` + `Idempotent-Replayed: true` |
| Süre dolduktan sonra geldi | `410 Gone` |
| Rezervasyon yok | `404 Not Found` |
