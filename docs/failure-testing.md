# Hata enjeksiyonu

Bağımlılıklar bozulduğunda sistem ne yapıyor (Aşama 7b ve 8): Toxiproxy ile Redis kapalı / cevap vermiyor, DB yavaş, Kafka kesik; ödeme sırasında instance ölümü; gerçek konteyner öldürme. Her senaryoda invariant otomatik kontrol ediliyor. Redis kesintiden döndüğünde bayat sayaç penceresi ve devre kesici de burada. Sonda: testlerin kendi
hataları (WSL saat atlaması yüzünden flaky test, CI'da ortaya çıkan test sırası bağımlılığı).

## Hata enjeksiyonu (Aşama 7b)

**Nasıl test edildi:**
- Uygulama PostgreSQL, Redis ve Kafka'ya sadece Toxiproxy üzerinden ulaşıyor (`src/test/.../chaos/`, kendi
  Spring context'i, üretim zaman aşımları). Test bir bağlantıyı kesiyor ya da gecikme ekliyor; servislerin
  kendisi çalışmaya devam ediyor.
- Kafka için bir adım daha var: broker'ın istemcilere duyurduğu adres de proxy'nin adresi. Yoksa ilk istekten
  sonra istemci proxy'yi atlayıp doğrudan broker'a bağlanırdı.
- Her senaryoda invariant (`held + confirmed + remaining = total`) otomatik olarak kontrol ediliyor.

| Senaryo | Sistem ne yapıyor | Ölçülen | Test |
|---|---|---|---|
| **Redis kapalı** (redis-gate stratejisi) | **Bekletir, sonra DB'ye düşer.** Her Redis çağrısı 500 ms'de pes ediyor; kapı "fail open" çalışıyor ve istek doğrudan DB'nin atomik UPDATE'ine gidiyor. Hiçbir istek reddedilmiyor. | 50 eşzamanlı istek 727 ms'de cevaplandı; 20 birimlik stokta 20 satış, 0 fazla satış. Redis dönünce eski sayaç mutabakat ile DB'ye eşitlendi. | `RedisOutageTest` |
| **DB ağı yavaş** (+100 ms gidiş-dönüş) | Bağlantı alabilen istek **bekletilir** (yavaşlar); havuzdan 500 ms içinde bağlantı alamayan **reddedilir** (503 + Retry-After). 500 yok. | 80 istemci: 20 × 201, 60 × 503, 9,7 sn. Ağ düzelince 503 alanlar aynı key ile tekrar denedi; tam 30 satış, fazlası yok. | `DatabaseLatencyTest` |
| **Kafka bağlantısı kesik** | **Ne reddeder ne bekletir.** Kafka istek yolunda değil; olay rezervasyonla aynı transaction'da outbox'a yazılıyor. Relay 5 sn'de pes ediyor (`max.block.ms`), satırlar yayınlanmamış kalıyor; Kafka dönünce hepsi yayınlanıp tam bir kez işleniyor. | 10 rezervasyon 37 ms'de; relay 5.035 ms'de pes etti; Kafka dönünce 10 olay, 10 bildirim. | `KafkaOutageTest` |
| **Ödeme sırasında instance ölüyor, commit'ten önce** | Açık transaction bağlantıyla birlikte geri alınıyor. Ödeme sağlayıcısının tekrar denemesi başka bir instance'ta onaylıyor. | Ölümden sonra durum HELD, olay 0; tekrar denemede CONFIRMED, tek olay. | `PaymentCrashTest` |
| **Ödeme sırasında instance ölüyor, commit'ten sonra** (cevap kayboldu) | Tekrar deneme idempotent: `AlreadyConfirmed`, ikinci onay da ikinci olay da yok. | Tek `ReservationConfirmed`. | `PaymentCrashTest` |
| **Gerçek konteyner öldürme** (3 instance, nginx) | Ölen instance'ın IP'si cevap vermeyi bırakıyor. nginx'in ona bağlanamayan istekleri 10 sn'lik bağlantı zaman aşımından sonra başka instance'a gidiyor (istek hiç ulaşmadığı için güvenli). | 3.000 onayın %10'u bittiğinde app2'ye SIGKILL. 3.000/3.000 onaylandı, her biri tek olay, invariant tuttu, istemci hiç hata görmedi. 50 istek 10 sn gecikti. | `bash bench/verify-kill-during-payment.sh` |

Bu aşamada eklenenler:
- **Redis komut zaman aşımı 500 ms.** Lettuce'un varsayılanı 60 sn'ydi; her istek Redis kesintisinde 60 sn
  beklerdi.
- **Kafka üreticisi `max.block.ms` 5 sn.** Varsayılan 60 sn'de relay'in transaction'ı ve DB bağlantısı o kadar
  tutulurdu.
- **Redis kapısı "fail open".** Kapı sadece bir optimizasyon ve son sözü DB veriyor; bu yüzden Redis yokken
  reddetmek yerine DB'ye gitmek hem doğru hem daha erişilebilir. Bedeli: kapının DECR'ı çalışıp cevabı
  kaybolursa Redis bir eksik kalır; mutabakat düzeltir.
- **Dört mutasyonun dördü de yakalandı:** Fallback'i kaldırmak, Redis zaman aşımını 60 sn'ye çekmek,
  `max.block.ms`'i 60 sn'ye çekmek ve onayı durum kontrolsüz yapmak.

**Redis'in dönüşü ve devre kesici (Aşama 8).**

*Dönüşteki pencere:*
- **Sorun:** Redis kesintisi sırasında süresi dolan bir rezervasyon birimi DB'ye geri veriyor, ama Redis'teki
  `INCR` başarısız oluyor. Redis dönünce sayaç DB'den **düşük** kalıyor.
- **Ölçülen (`RedisRecoveryTest`, düzeltmeden önce):** Stok 1 iken sayaç 0'dı ve dönüşten hemen sonraki istek
  `SoldOut` aldı. Pencere mutabakat turuna kadar sürüyordu (varsayılan 30 sn).
- **Daha kötüsü:** Kısayolu geçen bir istek kapıda `SOLD_OUT` alırsa servis bunu DB'deki "tükendi" işaretine
  yazıyordu. Bu işareti bütün stratejiler kullanıyor ve sadece bir sonraki stok iadesinde siliniyordu. Yalan
  söyleyen bir sayaç kalıcı bir yalana dönüşebiliyordu.

*Kapatma:*
- **Stale işareti:** Redis'e erişilemeyen her çağrıdan sonra, ilk başarılı çağrı Redis'e `gate:stale` yazıyor;
  bu, o çağrının kendi işinden **önce** yapılıyor. İşaret varken hiçbir instance sayacın "0"ına güvenmiyor ve
  DB'ye soruyor. İşaret instance'ta değil Redis'te, çünkü bayat sayaç bütün instance'ların ortak sayacı.
- **Kapının `SOLD_OUT` cevabı DB'ye doğrulatılıyor:** Bayat bir sayaç DB'deki "tükendi" işaretine
  dönüşemiyor. Bu doğrulama **her zaman** yapılıyor, ama sadece kapıda; asıl "tükendi" kısayolu
  (`knownSoldOut`) bayatlık işareti yoksa Redis'in 0'ına güvenmeye devam ediyor, 5b'nin faydası korunuyor.
  - İki ayrı yol var. Kısayol, claim'den önce bir Redis okumasıyla cevap veriyor; sellout'taki isteklerin
    neredeyse hepsi burada bitiyor. Kapıya sadece kısayolu geçip son birimi kaybeden istekler ulaşıyor.
  - Kapıda "sadece bayatken doğrula" yeterli değil: sayaç kesinti olmadan da DB'den düşük kalabilir. Örneğin
    süreç, bir stok iadesinin DB commit'i ile Redis `INCR`'ı arasında ölürse; bunu hiçbir şey bayat diye
    işaretlemiyor.
  - Kısayolun yanlış "tükendi"si hiçbir şey yazmıyor ve mutabakatla düzeliyor. Kapının `SOLD_OUT`'u ise DB'deki
    "tükendi" işaretine yazılıyor; bütün stratejiler bu işarete güveniyor ve mutabakat onu onarmıyor. Kalıcı ve
    paylaşılan bir yalanın girdisi doğrulanmalı.
  - Bedeli: kapıya ulaşan nadir istek başına bir DB çağrısı, zaten açık olan transaction'ın içinde. "Her zaman"
    ile "sadece bayatken" final benchmark'ta ölçüldü; fark bu kadar az isteğin maliyetiyle açıklanamıyor
    ([final benchmark, 5](benchmarks.md#5-redis-kapısında-tükendi-doğrulaması-her-zaman--sadece-bayatken)).
- **Dönüşte hemen mutabakat:** Bir olay tetikleniyor, 30 sn beklenmiyor. İşareti sadece başarılı bir tur
  siliyor (karşılaştır-ve-sil).
- **Sonuç:** Dönüşten hemen sonraki istek birimi satıyor. Dönüş anında Redis DB'den asla düşük değil; bir
  fazla olabilir (güvenli yön), bir sonraki tur düzeltiyor.
- **Mutasyon kontrolü:** 4 mutasyonun hepsi yakalandı. Bunlardan biri, testin bulduğu sıralama hatası: işareti
  çağrıdan sonra yazmak.

*Devre kesici* (`CircuitBreaker`, 3 ardışık hata → 2 sn açık → tek deneme isteği):

| Redis'in durumu, 30 ardışık istek | Devre kesici yok | Devre kesici var |
|---|---|---|
| Bağlantıyı reddediyor (kapalı) | İlk istek ~540 ms, sonrası ~5 ms (Lettuce zaten hemen pes ediyor) | Aynı |
| Cevap vermiyor (ağ kara deliği, donmuş süreç) | **Her istek ~1.010 ms** (iki çağrı × 500 ms) | İlk iki istek 1.050 / 513 ms, sonrası **3–6 ms** |

Devre kesicinin değeri "cevap vermeyen" durumda; "kapalı" durumda Lettuce zaten hızlı pes ediyor. Zaman
monoton saatten (`nanoTime`) okunuyor; duvar saatinin geri atlaması devreyi gereğinden uzun açık tutamaz.

Konteyner öldürme denemesinden iki not:
- `docker kill` sonrası restart policy çalışmıyor; Docker bunu elle durdurma sayıyor. Kendi kendine çıkan bir
  süreç (OOM testindeki gibi) yeniden başlıyor.
- 10 sn'lik bağlantı zaman aşımı bir takas. 2 sn, aşırı yükte sağlıklı instance'ları çıkarıyordu; 10 sn, ölü
  instance'a gönderilen isteği 10 sn geciktiriyor. Aktif sağlık kontrolü bu takası ortadan kaldırır.

## Testlerin kendi hataları

İki kez kod doğruydu, test yanlıştı. İkisinde de önce hata yeniden üretildi, kök neden kanıtlandı, sonra test
düzeltildi.

**Flaky lease testi: WSL'de duvar saati geri atlıyor (Aşama 7a).**
- **Belirti:** `ClaimFencingTest` yaklaşık 50 koşuda bir kaldı: devralınması gereken claim devralınamadı.
- **Kök neden:** WSL2'de duvar saati yaklaşık 32 saniyede bir ~500 ms **geri** atlıyor (10 dakikalık bir izleyiciyle
  ölçüldü: 479–605 ms). Lease, DB'nin duvar saatiyle (`now()`) değerlendiriliyor. Test, 200 ms'lik lease'in
  350 ms'lik `sleep` sonunda dolduğunu varsayıyordu; saat geri atlayınca lease DB açısından hâlâ geçerliydi.
- **Düzeltme:** Testler lease'i beklemek yerine açıkça bitiriyor (`locked_until = now() - 1s`); lease'ler uzun
  (30 sn). 4 senaryo × 50 tekrar = 200 koşuda ve 3 tam pakette hata yok.
- Aynı ölçüm devre kesicinin zamanını monoton saatten (`nanoTime`) okumasının nedeni.

**Test sınıflarının sırasına bağımlılık: ilk GitHub CI koşusunda bulundu (Aşama 8).**
- **Belirti:** Yerelde ve temiz klonda yeşil olan iki test, GitHub runner'ında kaldı. Surefire sınıfları dosya
  sistemi sırasıyla koşuyor; runner'ın sırası WSL'inkinden farklıydı.
- **`RedisRecoveryTest`:** `RedisOutageTest`'in hemen arkasından koşunca uygulamanın Lettuce bağlantısı hâlâ
  yeniden kuruluyordu ve devre kesici açıktı. İlk istek Redis'i atladı (fail open, doğru davranış); test hiç
  oluşmayacak bir sayacı 20 sn bekledi. `ChaosIntegrationTest` artık her testten sonra uygulamanın kendi
  bağlantısının `PING`'e cevap vermesini ve devre kesicinin kapanmasını bekliyor.
- **`OutboxTest`:** Başka bir testin bıraktığı süresi dolmuş tutum, global süpürme sayısını 51 yaptı (50
  bekleniyordu). Test artık başlamadan önce artıkları süpürüyor.
- **Doğrulama:** İki hata da `-Dsurefire.runOrder=alphabetical` ile yerelde üretildi. Düzeltmeden sonra tam paket
  üç sırada (dosya sistemi, alfabetik, ters alfabetik) 149/149 yeşil.
