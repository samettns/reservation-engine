# Olaylar: outbox, Kafka ve dead-letter topic

Her durum değişikliği bir Kafka olayı üretir. Olay, durum değişikliğiyle aynı transaction'da outbox tablosuna yazılır (dual write yok); relay'ler paralel ve sıralı yayınlar, consumer etkiyi tam bir kez uygular. İşlenemeyen (zehirli) mesajlar 3 denemeden sonra dead-letter topic'e gider.

## Olaylar: transactional outbox ve Kafka (Aşama 5a)

Her durum değişikliği (`ReservationHeld`, `ReservationConfirmed`, `ReservationExpired`) bir Kafka olayı üretir.

**Problem (dual write).** Veritabanına ve Kafka'ya ayrı ayrı yazmak iki şekilde bozulur; ikisi de testle
gösteriliyor:
- **Commit edildi, Kafka'ya gönderilmeden çökme:** Olay sonsuza kadar kayboldu.
- **Kafka'ya gönderildi, sonra DB transaction'ı geri alındı:** Hiç olmamış bir değişiklik için "hayalet" olay.

**Çözüm: transactional outbox.** Olay, durum değişikliğiyle **aynı transaction'da** `outbox` tablosuna bir satır
olarak yazılıyor. İkisi birlikte commit ya da rollback oluyor. Her instance'ta çalışan bir **polling relay**
yayınlanmamış satırları alıp Kafka'ya gönderiyor ve broker onayından sonra yayınlandı olarak işaretliyor.

| Garanti | Nasıl |
|---|---|
| Olay kaybı yok | Olay DB'de; relay gönderemezse satır yayınlanmamış kalır ve tekrar denenir |
| Paralel relay'ler aynı satırı aynı anda yayınlamaz | `FOR UPDATE SKIP LOCKED` |
| Bir rezervasyonun olayları sırayla gider | Kafka key = rezervasyon ID'si (aynı partition) **ve** bir rezervasyonun bir olayı, ancak daha eski olayları yayınlandıktan sonra seçilebilir. Aksi halde iki relay olayları ters sırayla gönderebilir ve key yanlış sırayı korur. |
| Teslimat **at-least-once** (kabul edildi) | Gönderim ile "yayınlandı" işareti atomik olamaz; arada çökme olursa olay tekrar gönderilir |
| Etki **tam bir kez** | Consumer, mesaj ID'sini etkiyle aynı transaction'da `consumer_inbox`'a yazar; gördüğü mesajı atlar |

**Kanıtlar:**
- 3 paralel relay 500 olayı tam bir kez yayınladı (160 / 180 / 160).
- Kasıtlı tekrar gönderimde Kafka'da her mesaj 2 kez vardı, etki yine 1 kez uygulandı.
- Deterministik test: A relay'i bir rezervasyonun `Held` olayını gönderirken, B relay'i aynı rezervasyonun
  `Confirmed` olayına dokunmuyor.
- Mutasyonlar: sıra koruması, inbox ve Kafka key'i ayrı ayrı kaldırılınca testler kırmızıya dönüyor.
- **Canlı, 3 container ve gerçek Kafka** (`bash bench/verify-events-live.sh`): 3.000 olayın hepsi yayınlandı
  (relay başına 1.215 / 1.083 / 702). 3.000 bildirim oluştu (consumer başına 1.019 / 998 / 983). Sırası bozuk
  rezervasyon 0.

## Zehirli mesajlar ve dead-letter topic (Aşama 7a)

**Kafka dead-letter topic (`reservation-events.DLT`).**

| Hata türü | Davranış |
|---|---|
| Mesaj kaynaklı (bug, constraint ihlali) | 3 deneme (200 ms arayla), sonra DLT. Gerçek hata DLT başlıklarında. Partition devam ediyor. |
| Bozuk içerik (eksik başlık, bozuk UUID/sayı) | Tekrar denenmeden DLT. |
| Ortam kaynaklı (DB'ye ulaşılamıyor) | DLT'ye **gitmiyor**. Üstel bekleme ile (en fazla 5 sn) kesinti bitene kadar tekrar deneniyor. Mesajın suçu yok; DLT'ye atmak bütün topic'i DLT'ye taşırdı. |

- Takas: DLT'ye düşen mesaj, o rezervasyonun sıralamasını bozuyor (sonraki olaylar onsuz işleniyor).
  Alternatifi, partition'daki bütün rezervasyonların durması.
- Aşama 5a'daki senaryo testle yeniden üretildi (`DeadLetterTest`): tek mesajı reddeden bir CHECK constraint.
  Mesaj 3 kez denendi ve DLT'ye gitti; arkasındaki mesaj işlendi. 5a'daki gerçek NULL hatasının istisnası da
  poison olarak sınıflanıyor.
- Kesinti testi: havuzdaki bütün bağlantılar tutuldu. Mesaj 3'ten fazla denendi, DLT'ye gitmedi ve bağlantılar
  bırakılınca işlendi.
- Mutasyon kontrolü: 6 mutasyonun hepsi yakalandı.
