# Aşırı yük: 503, yük atma ve fail fast

Kapasitenin üstündeki yükte sistemin nasıl davrandığı (Aşama 7a): havuz dolunca 500 yerine 503 + Retry-After, instance başına uçuştaki istek limiti (yük atma), limitin nerede ve kaç olacağı kararı (Little yasası ve ölçümle), nginx'in sağlık kontrolü düzeltmesi, benchmark'ın bellek ayak izi ve "healthy ama ölü" instance'lar için fail fast.

## Aşırı yük ve zehirli mesajlar (Aşama 7a)

**Aşırı yükte 500 yerine 503.**
- Havuzdan bağlantı alınamazsa (havuz dolu ya da DB yok) veya Redis'e ulaşılamazsa artık `503` dönüyor:
  `Retry-After: 1` başlığıyla, tipi `urn:problem-type:dependency-unavailable`.
- Havuz beklemesi 5 sn'den 500 ms'ye indi (`DB_CONNECTION_TIMEOUT_MS`).

**Yük atma (load shedding).**
- Instance başına en fazla `SHED_MAX_IN_FLIGHT` (varsayılan 100) rezervasyon isteği aynı anda işleniyor.
- Fazlası hiç iş yapılmadan, mikrosaniyeler içinde `503` alıyor (`urn:problem-type:overloaded`).
- Reddetme idempotency claim'inden önce oluyor; aynı key ile tekrar deneme normal işleniyor.
- `POST /reservations/{id}/confirm` hiç reddedilmiyor. Ödeme onayı gecikirse, ödenmiş bir rezervasyonun süresi
  dolabilir.

**5.000 kullanıcı: yük atmasız / limit 100 / limit 20** (`bash bench/run-overload.sh`, tek koşu).
- **Ortak koşullar:** Üç varyant da aynı kodla ve aynı yeni nginx ayarlarıyla koşuldu.
- **İstemci:** `Retry-After`'a uyuyor: 503 alınca bekleyip aynı key ile tekrar deniyor, en fazla 10 deneme.
- **Tam tablolar:** [`docs/benchmarks/overload/comparison.md`](benchmarks/overload/comparison.md).
- `naive` sonuçlara katılmadı: kayıp güncellemeler yüzünden stok sayıları anlamsız.

**Önce bir bozucu etken: nginx'in pasif sağlık kontrolü aşırı yükte sağlıklı instance'ları da çıkarıyor.**
- 5.000 kullanıcıda uygulamaların TCP kabul kuyrukları doluyor; nginx'in yeni bağlantıları 2 sn'de zaman aşımına
  uğruyor. nginx log'u: "upstream timed out ... while **connecting** to upstream".
- `max_fails=1` olduğu için tek bir bağlantı hatası, sağlıklı bir instance'ı 10 sn trafikten çıkarıyor. Üçü
  birden çıkınca her istek anında 502 alıyor; istemci 502'yi tekrar denemiyor.
- Etkilenen koşular: yük atmasızda 10'da 8, limit 20'de 10'da 4 (atomic ve optimistic), limit 100'de 10'da 0.
  Limit 20'de daha sık olmasının olası nedeni: daha çok istek reddediliyor ve her reddedilen istemci tam 1 sn
  sonra tekrar geliyor (`Retry-After: 1`, rastgele sapma yok). Eşzamanlı tekrar dalgaları bağlantı patlaması
  yaratıyor. Bu bir hipotez; ayrıca ölçülmedi.
- **Bu yüzden karşılaştırmada sadece 502/504 almayan koşular kullanıldı.** nginx ayarının düzeltilmesi açık iş
  ([limitations.md](limitations.md)); ikinci turda düzeltildi (aşağıda).

Temiz koşularda aynı strateji, iki limit:

| | Limit 100 | Limit 20 |
|---|---|---|
| Contention, başarılı rezervasyon/sn (pessimistic / redis) | 220 / 199 | 221 / 239 |
| Contention, başarılı isteğin p99'u (tek deneme) | 2,2 / 2,6 sn | 1,1 / 1,0 sn |
| Contention, p99 (ilk denemeden 201'e, beklemeler dahil) | 11,3 / 10,8 sn | 9,4 / 9,5 sn |
| Contention, 10 denemede vazgeçen istemci (5.000'den) | 8.568 / 8.666 | 12.180 / 10.810 |
| Sellout, başarılı rezervasyon/sn (pessimistic / redis) | 106 / 122 | **72 / 67** |
| Sellout, satılmadan kalan stok | 0 / 0 | 0 / 0 |
| Sellout, vazgeçen istemci | 0 / 0 | 138 / 242 |
| Toplam 503 (contention, tekrar denemeler dahil) | 114.713 / 113.397 | 136.660 / 127.010 |
| Ölen ya da yeniden başlayan instance | 0 | 0 |

Yük atmasız varyantta da instance ölmedi; ilk koşudaki OOM, `Retry-After`'a uymayan istemcinin fırtınasıyla
olmuştu. Ama 10 koşunun 8'inde nginx instance'ları trafikten çıkardı ve contention'da başarılı rezervasyon
16–69/sn'de kaldı (limitli varyantlarda 196–239). Sonuç: yük atma olmadan aşırı yük, ölmeyen ama trafikten
çıkarılan instance'lara dönüşüyor.

**İlk turun kararı: varsayılan limit 100** (ikinci turda doğrulandı, aşağıda). Little yasası (L = X × W: uçuştaki
istek = throughput × gecikme) ile gerekçe:
- **Sıcak satırda throughput limitten bağımsız.** Darboğaz satır kilidi; X ≈ 200–240/sn iki limitte de aynı.
  - Limit 100 → 3 × 100 = 300 istek uçuşta → W ≈ 300 / 210 ≈ **1,4 sn** ortalama.
  - Limit 20 → 60 istek uçuşta → W ≈ 60 / 220 ≈ **0,27 sn** ortalama.
  - Ölçülen p99'lar (2,2–2,6 sn ve 1,0–1,1 sn) bununla uyumlu. Yani contention için limit 20 daha iyi: aynı
    throughput, yarı gecikme. Fazla limit sadece kuyruk ekliyor.
- **Sellout'ta isteklerin çoğu ucuz.** "Tükendi" kısayolu bir Redis `GET` ya da bir satır okuması. Bunların
  throughput'u saniyede binlerce; aynı W hedefi için L ≈ X × W, 20'nin çok üstünde.
  - Limit 20 bu ucuz istekleri de reddediyor: satış %32–45 yavaşladı (106 → 72, 122 → 67/sn) ve 138–242 istemci
    vazgeçti.
- **Tek bir sabit limit iki iş yükünü birden karşılayamıyor.** Projenin ana senaryosu flaş satış ve limit 100'de
  nginx bozucu etkisi hiç görülmedi; bu yüzden varsayılan 100 kaldı. Contention'da bedeli p99 2,2–2,6 sn
  (önceki ~13 sn'ye göre yine çok iyi).
- **Doğru çözüm (önerilen sonraki adım):** Limiti HTTP girişine değil, kıt kaynağın kullanıldığı yere koymak:
  "tükendi" kısayollarından sonraki DB transaction adımına. O zaman Little yasasına göre ≈ havuz boyutu (20)
  doğru değer olur ve ucuz cevaplar sınırlanmaz. Alternatifi, gecikmeye göre kendini ayarlayan limit (adaptive
  concurrency).

**İkinci tur: nginx düzeltildi, limit DB adımında denendi.**

*nginx: "yavaş" ile "ölü" ayrıldı.*
- **Ayarlar:** `max_fails=10` (1 idi), bağlantı zaman aşımı 10 sn (2 idi), Tomcat kabul kuyruğu 2048 (100 idi).
- **Canlı kanıt** (`bash bench/verify-failfast-live.sh`):
  - 5.000 kullanıcı, yük atma kapalı: hiçbir instance çıkarılmadı, 0 tane 502/504. Eski ayarla bu yük 10 koşunun
    8'inde 502 fırtınası üretiyordu.
  - `SIGSTOP` ile dondurulan instance yine 14 sn'de "unhealthy" oldu ve nginx onu çıkardı (log'da "upstream
    server temporarily disabled").
- **Benchmark:** İkinci turun 20 koşusunun hiçbirinde 502/504 yok.

*Retry-After jitter ve "thundering herd" hipotezi* (`bash bench/measure-retry-herd.sh`).
- **Hipotez desteklenmedi.** Reddedilen istemcilerin geri dönüşleri jitter'la ve jitter'sız aynı derecede
  düzensiz (değişim katsayısı 0,38 / 0,42). 1 sn'lik periyodiklik iki durumda da var (otokorelasyon +0,72 /
  +0,84); kaynağı senkron tekrarlar değil.
- **Jitter'ın ölçülen etkisi:** tekrar deneme sayısı yarıya iniyor (141k → 74k) ve tepeler düşüyor (en yüksek /
  ortalama 2,57 → 1,95). Bu yüzden 503'ler artık 1–3 sn arası rastgele `Retry-After` veriyor.

*Limit: HTTP girişinde 100 mü, DB adımında 20 mi?*
- **Fark:** `SHED_MODE=db` sadece claim + stok transaction'ını sınırlıyor; "tükendi" kısayolları sınırsız.
- **Koşullar:** İki varyantta istemci, nginx ve jitter aynı.
- `naive` hariç, 4 strateji:

| | HTTP girişinde 100 | DB adımında 20 |
|---|---|---|
| Contention, başarılı rezervasyon/sn | 218–237 | **169–191** (%18–23 düşük) |
| Contention, başarılı isteğin p99'u | 2,0–2,4 sn | 2,3–3,4 sn |
| Sellout, başarılı rezervasyon/sn | 102–114 | **87–98** (%11–16 düşük) |
| Sellout, başarılı isteğin p99'u | 1,5–1,9 sn | 1,2–1,7 sn |
| Sellout, satılmadan kalan stok | 0 | 0 |
| Toplam 503 (contention / sellout) | 58–60 bin / 14–17 bin | 55–58 bin / 14–18 bin |
| 10 denemede vazgeçen istemci (contention) | 3.010–3.250 | 2.874–3.231 |
| Ölen instance, 502/504 | 0, 0 | 0, 0 |

**Neden DB adımındaki limit beklendiği gibi çalışmadı (ölçüldü).** Pessimistic ve redis koşularında saniyede bir,
Hikari ve PostgreSQL'den örnek alındı:
- **DB süresinin dağılımı:** PostgreSQL'deki meşgul oturumların **%93–98'i rezervasyon transaction'ı**.
  "Tükendi" okuması %0–0,8, claim %0,6–4,9, arka plan işleri (sweeper; relay ve mutabakat benchmark'ta kapalı)
  %0. Yani başka işler DB süresini çalmıyor.
- **Bekleyenler:** Hikari'de bağlantı bekleyen thread sayısı ortalama **64–368, en yüksek 2.184**. DB adımında
  instance başına en fazla 20 izin var; bu yüzden bekleyen yüzlercesi neredeyse tamamen **sınırsız "tükendi"
  okumaları**. Her okuma kısa sürüyor ama aşırı yükte sınırlı adımla aynı bağlantı kuyruğunda bekliyor. Ortalama
  bağlantı bekleme 89–114 ms; sellout'ta havuz zaman aşımı 1.780–6.248 (HTTP limitinde 250–400).
- **Sonuç:** "Ucuz" yol aslında ucuz değil, çünkü darboğaz kaynağı, yani bağlantı havuzunu paylaşıyor. Limit
  havuzun bir kısmını sınırladı; kuyruk sınırsız kaldı.

**Nihai varsayılan: HTTP girişinde, limit 100 (`SHED_MODE=http`).** Little yasasıyla:
- **Doğru soru:** "Hangi işin limiti?" değil, "Hangi kaynağın kuyruğu?". Limit, doygun kaynağı kullanan *bütün*
  işleri kapsamalı.
- **Bu sistemde kaynak bağlantı havuzu,** ve her rezervasyon isteği ona en az bir kez uğruyor ("tükendi"
  okuması dahil). HTTP girişindeki limit bu kuyruğu sınırlıyor: instance başına en fazla 100 istek → havuz
  kuyruğu ≤ 80.
- **Değer:** Sıcak satırda X ≈ 230/sn ile L = 300 → W ≈ 1,3 sn ortalama (ölçülen p99 2,0–2,4 sn). İlk turdaki
  limit 20 bunu yarıya indiriyordu ama sellout'u %32–45 yavaşlatmıştı. 100, ana senaryo olan flaş satışın
  bedelini ödetmeden contention gecikmesini ~13 sn'den ~2 sn'ye indiriyor.
- **Limit 20'yi kullanılabilir yapacak değişiklik:** Ucuz yolun havuzu hiç kullanmaması. Örneğin "tükendi"
  işaretini Redis'te ya da bellekte tutmak, okumalar için ayrı küçük bir havuz açmak, ya da okumayı da limitin
  içine almak. Aşama 8'e not edildi.

Ölçüm notu: DB adımı varyantının 6 koşusu bellek limitleri ve havuz örneklemesi olmadan, son 4'ü onlarla koşuldu
(bir bellek kesintisinden sonra). Dört stratejide de fark aynı yönde ve benzer büyüklükte. Tablolar:
[`docs/benchmarks/overload/comparison-round2.md`](benchmarks/overload/comparison-round2.md).

**Benchmark'ın bellek ayak izi** (`bash bench/measure-memory.sh`): Üç koşu, Windows'un belleği azaldığı için
durduruldu (16 GB makine, boştayken 3 GB boş).

| | Önce | Sonra |
|---|---|---|
| Windows'un gördüğü WSL (vmmem), koşu tepesi | 5,7 GB | **4,1 GB** |
| k6 (5.000 VU) | 1,36 GB | 1,13 GB |

Önlemler:
- **WSL'in belleği geri vermesi:** `%USERPROFILE%\.wslconfig` içinde `[experimental] autoMemoryReclaim=gradual`
  (ve `[wsl2] memory=8GB` üst sınırı). Ölçüm (`bash bench/measure-reclaim.sh`):
  - Koşu sonrası vmmem 4,9 GB.
  - Konteynerler durunca 30 sn'de 3,5 GB.
  - Sayfa önbelleği ~2 dk boşta bekleyip geri veriliyor: 3,5 dk sonra 1,1 GB, 10 dk sonra 0,9 GB (koşudan önceki
    3,1 GB'ın da altında).
- **İsteğe bağlı: önbelleği hemen boşaltmak (`BENCH_DROP_CACHES=1`, varsayılan kapalı):**
  - Ne yapıyor: Her stratejiden önce `sync; echo 3 > /proc/sys/vm/drop_caches` (ölçüm: vmmem 3,0 → 1,2 GB).
  - Neden privileged: `/proc/sys/vm` bütün konteynerlerin paylaştığı çekirdeğe ait. Normal bir konteyner buraya
    yazamıyor, bu adım `docker run --privileged` ile çalışıyor.
  - Neden kapalı: Projeyi klonlayan biri farkında olmadan privileged bir konteyner çalıştırmasın.
    `autoMemoryReclaim` varken gerek yok.
- **k6:** `GOGC=50` ve 2 GB sınır.
- **Konteynerler:** Hepsinde bellek sınırı (uygulama 768 MB, PostgreSQL 1 GB, Redis 256 MB, nginx 384 MB, Kafka
  1,5 GB).

**Tarihsel referans: Aşama 6 hali ("önce") ve ilk koşu.** Bunlar kontrollü bir karşılaştırma değil.
- **"Önce" farklı koşullarda ölçüldü:**
  - Uygulama: 5 sn havuz beklemesi, 500 dönüyor, yük atma yok.
  - **nginx:** pasif sağlık kontrolü yok, 60 sn zaman aşımları.
  - İstemci: `Retry-After` beklemiyor; zaten 503 de almıyordu.
- **Farkın bir kısmı nginx değişikliğinden geliyor** ve bu ölçümden ayrıştırılamıyor. Örneğin yeni nginx
  aşırı yükte instance'ları trafikten çıkarabiliyor (yukarıda); eski nginx bunu hiç yapmıyordu.
- Tablo: [`comparison-first-run-no-retry.md`](benchmarks/overload/comparison-first-run-no-retry.md).

| Varyant (eski nginx, istemci `Retry-After`'a uymuyor) | 500 oranı | Başarılı rezervasyon/sn (contention) | Başarılı p99 (contention) | Başarılı p99 (sellout) | Ölen instance |
|---|---|---|---|---|---|
| Önce (Aşama 6: 5 sn bekleme, 500) | %78–83 contention, %6–60 sellout | 117–149 | 12,7–14,9 sn | 4,5–14,3 sn | yok |
| Yük atmasız (500 ms bekleme, 503) | %0 | **0,9–2,3** | 8,1–16,5 sn | 10,7–12,1 sn | **her contention koşusunda 1 instance (OOM)** |
| Limit 100 | %0 | 55–70 | 6,2–7,7 sn | 1,9–2,6 sn | yok |

İlk koşudan çıkanlar:
- **500'ler tamamen kalktı.**
- **Bekleme süresini kısaltmak tek başına zararlı.**
  - Bekleme 5 sn'den 500 ms'ye inince başarısız istekler 10 kat hızlı geri dönüyor; istemci de aynı hızla yeniden
    deniyor.
  - Bu çalkantı 512 MB heap'i doldurdu. `OutOfMemoryError`, Tomcat'in Acceptor ve Poller thread'lerini de
    öldürdü: instance yaşıyor ama bir daha bağlantı kabul etmiyor.
  - Bu, her contention koşusunda bir instance'ta oldu. Benchmark sürücüsü artık yanıt vermeyen instance'ı satıra
    yazıp konteynerleri yeniden başlatıyor.
- **Yük atma instance'ları ayakta tutuyor ve başarılı isteklerin gecikmesini yarıya, sellout'ta 2–6 kata indiriyor.**
- **Ama contention'da başarılı throughput yarıya düştü (≈135 → ≈65/sn).** İki neden var:
  1. `Retry-After`'a uymayan istemciler saniyede ~10.000 reddetme üretiyor. nginx, 3 JVM ve k6 aynı makinede;
     bu yük kabul edilen isteklerin CPU'sunu yiyor.
  2. 100 limiti sıcak satır için fazla yüksek. Ortalama havuz beklemesi hâlâ ~380 ms. Little yasası: 300 istek
     uçuşta, ~60/sn throughput → ~5 sn gecikme (ölçülen p99 6–7 sn).
- **Sellout'ta stok satılmadan kaldı (1.000 biriminin 396–984'ü, shed'siz ve sonra).** 20.000 isteğin %97'si 503
  aldı ve bu ölçümdeki istemci 503'ten sonra vazgeçiyor. 503 "tükendi" değil, "sonra tekrar dene" demek; tekrar
  denemeyen istemciyle stok satılamaz. Bu yüzden yukarıdaki asıl ölçüm `Retry-After`'a uyan istemciyle yapıldı.

**"Healthy ama ölü" instance düzeltildi (fail fast, sürecin kendisi için de).**

| Önce | Şimdi |
|---|---|
| OOM sonrası JVM yaşamaya devam ediyordu; Tomcat'in Acceptor thread'i ölmüştü | `-XX:+ExitOnOutOfMemoryError`: süreç kapanıyor; `restart: unless-stopped` temiz bir instance başlatıyor |
| Healthcheck `/actuator/health` (DB dahil), 30 başarısızlık = 90 sn sonra "unhealthy" | `/actuator/health/liveness` (DB'siz: aşırı yüklü ama çalışan instance ölü sayılmıyor), 2 sn timeout, 3 başarısızlık |
| nginx `proxy_next_upstream off`: hiçbir hata sayılmıyordu, ölü instance trafik almaya devam ediyordu | Pasif sağlık kontrolü: `max_fails=1 fail_timeout=10s`, paylaşılan `zone`, `proxy_read_timeout 15s`. Ulaşmış bir POST yine hiç yeniden gönderilmiyor |

Canlı deneme (`bash bench/verify-failfast-live.sh`):
- **128 MB heap ve 3.000 kullanıcı:** üç instance da OOM ile kapandı ve yeniden başladı (5 yeniden başlatma),
  sonunda hepsi sağlıklı.
- **app2'nin JVM'i `SIGSTOP` ile donduruldu:** 13 sn'de "unhealthy" oldu. 100 istemcili 40 sn'lik koşuda 102
  zaman aşımı oldu (8.003 başarılıya karşı).

Bu 102 zaman aşımı pasif kontrolün bedeli: nginx bir instance'ın öldüğünü ancak bir istek zaman aşımına
uğrayınca anlıyor; o sürede her istemcinin bir isteği takılıyor. Aktif sağlık kontrolü (HAProxy, Envoy, nginx
Plus) bu bedeli düşürür. Docker "unhealthy" bir konteyneri kendiliğinden yeniden başlatmıyor; bu bir
orkestratörün işi (ör. Kubernetes liveness probe).
