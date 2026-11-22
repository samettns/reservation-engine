# Tasarım

Overselling'i önleyen çekirdek: beş kilitleme stratejisi, stoğu satırlara bölen sharding, ödeme bekleyen geçici rezervasyonlar, idempotency claim'i ve "tükendi" kısayolları, fencing token ve Redis–DB mutabakatı. Her bölümde kararın nedeni ve kanıtı (testler, mutasyonlar, canlı denemeler) var. Ölçümler [benchmarks.md](benchmarks.md)'de.

## Stratejiler

`RESERVATION_STRATEGY` ortam değişkeniyle seçilir (varsayılan `atomic`):

| Ad | Yöntem |
|---|---|
| `naive` | Oku, kontrol et, yaz. **Bilerek hatalı**; overselling'i göstermek için var |
| `atomic` | `UPDATE ... SET qty = qty - 1 WHERE ... AND qty > 0`, tek ifade |
| `optimistic` | Version kolonu ile compare-and-set, sınırlı yeniden deneme |
| `pessimistic` | `SELECT ... FOR UPDATE`, sonra yazma |
| `pessimistic-precheck` | Önce kilitsiz `SELECT`; stok 0 ise kilitlemeden "tükendi", değilse `FOR UPDATE` ile doğrulama |
| `redis` | Redis'te Lua script ile atomik kapı, ardından PostgreSQL'de atomik UPDATE. Kaynak doğruluk PostgreSQL |

## Stock sharding (Aşama 3)

Bir etkinliğin stoğu N satıra (shard) bölünebiliyor: `POST /events {"name":..., "stock":..., "shards": 16}`.
İstek rastgele bir shard'dan başlıyor, boşsa sıradakine geçiyor; **"tükendi" cevabı ancak bütün shard'lar
denendikten sonra veriliyor.** Sharding kilitleme stratejisinden bağımsız bir katman: her strateji sadece
"tek satırdan bir birim al" işini yapıyor, hangi satırın deneneceğine `StockShards` karar veriyor.


Ölçüm sonuçları: [benchmarks.md, sharding](benchmarks.md#aşama-3-sharding-tek-koşu).

**Bu aşamada bulunan iki hata:**
- **Deadlock (pessimistic + sharding):** İstekler shard'ları farklı sırayla gezip boş shard'ların kilidini
  tutarken birbirini bekliyordu. Boş shard'ın kilidi artık bir savepoint'e geri dönülerek hemen bırakılıyor.
  PostgreSQL'in `ROLLBACK TO SAVEPOINT` ile satır kilidini bıraktığı ayrı bir deneyle doğrulandı. Düzeltme
  kaldırılınca testlerde 174 deadlock çıkıyor.
- **Önbellek hatası:** Shard sayısı etkinlik ID'sine göre önbelleğe alınıyordu. Benchmark ID'leri yeniden
  kullanınca (`RESTART IDENTITY`) 16 shard'lı etkinlikler tek shard'lı sanıldı ve stok varken "tükendi"
  denildi. Önbellek kaldırıldı. Benchmark artık her koşudan sonra "satılmamış stok kaldı mı?" ve "bütün
  shard'lar kullanıldı mı?" diye kendini de kontrol ediyor.

## Geçici rezervasyon ve süre dolumu (Aşama 4)

```
HELD --(ödeme, süre dolmadan)--> CONFIRMED
HELD --(süre doldu, sweeper)----> EXPIRED   (birim, alındığı shard'a geri döner)
```

- **İki geçiş de koşullu:** `UPDATE ... WHERE status = 'HELD'`. Ödeme ile süre dolumu aynı anda olursa satır
  kilidi altında yalnızca biri kazanır; kaybeden 0 satır günceller.
- **Tek saat:** Son tarih de, karşılaştırmalar da PostgreSQL'in `now()`'ı ile yapılıyor; instance'ların JVM
  saatleri arasındaki kayma sonucu etkilemiyor.
- **Sweeper her instance'ta çalışıyor**, lider seçimi yok. Süresi dolan satırlar `FOR UPDATE SKIP LOCKED` ile
  alınıyor: instance'lar birbirini beklemiyor ve aynı satırı iki kez almıyor. EXPIRED işaretlemesi ile stok
  iadesi aynı transaction'da. Stok satırları sabit sırayla `(event_id, shard_no)` güncelleniyor; iki sweeper
  birbirini kilitleyemiyor.
- **Invariant:** `held + confirmed + kalan = toplam`.

**Kanıtlar:**
- Deterministik yarış testleri iki kazanma sırasını da sınıyor:
  - **Süre dolumu önce kilitler:** Ödeme, kilidi bekler ve sonunda 0 satır günceller.
  - **Ödeme önce kilitler:** Sweeper kilitli satırı beklemeden atlar.
- Stres testinde 300 ödeme rastgele zamanlarda son tarih etrafında geldi (151 kazandı, 149 geç kaldı). Her
  yanıt, son durumla birebir uyuştu.
- Mutasyon testleri: `SKIP LOCKED`'ı ya da durum koşullarını kaldırınca testler kırmızıya dönüyor. İki koruma
  birden kaldırılınca 3.000 rezervasyonun 250'si iki kez sona erdirildi.
- **Canlı, 3 süreçle** (`bash bench/verify-expiry-live.sh`): 3 container, hiç ödenmeyen 3.000 rezervasyon,
  3 sn TTL. Hepsi tam bir kez sona erdi (instance başına 1.054 / 607 / 1.339) ve her shard yeniden 750 birime
  döndü.

## Idempotency "işlemde" durumu ve "tükendi" kısayolları (Aşama 5b)

**Idempotency claim.** İstek, stoğa dokunmadan **önce** `(kullanıcı, Idempotency-Key)` çiftini `IN_PROGRESS`
olarak ayrı bir transaction'da commit ediyor. `COMPLETED` durumu, rezervasyonla aynı transaction'da yazılıyor.
Yan etkisi olmayan istekler (tükendi, 503, hata) key'i serbest bırakıyor. Aynı anda gelen ikiz istek, stok için
yarışmak yerine **`409` + `urn:problem-type:request-in-progress`** ve `Retry-After` alıyor. Tekrar denediğinde
asıl sonucu görüyor. Sahibi çöken bir claim, kira süresi (`IDEMPOTENCY_LEASE`, 30 sn) dolunca devralınabiliyor.
Bu, Aşama 1'deki açığı **bütün stratejiler için** kapattı: aynı key'li 100 eşzamanlı istekten "tükendi" alan sayısı
0 (önceden Redis'te 8'e kadar).

**"Tükendi" kısayolları** (stok satırına ve transaction'a dokunmadan cevap):
- **Redis:** Kapı sayacı 0 ise tek bir Redis `GET` yetiyor.
- **Versiyonlu "tükendi" işareti (DB stratejileri):** Bütün shard'ları boş bulan istek, etkinlik satırına
  "şu stok versiyonunda tükendi" yazıyor (compare-and-set). Sonraki istekler bunu tek bir PK okumasıyla görüyor.
- **Stok iadesinde temizleniyor:** Süresi dolan bir rezervasyon birim iade edince etkinliğin stok versiyonu artıyor
  (işaret kendiliğinden geçersizleşiyor) ve Redis sayacı artıyor (kısayol kapanıyor). İkisi de testli.
- **Kısayol bile idempotency'ye bakıyor:** Başarılı bir isteğin tekrarı "tükendi" değil, kendi sonucunu alıyor.

## Dağıtık kilit, fencing token ve Redis–DB mutabakatı (Aşama 6)

**Neden kilit tek başına yetmez (Redlock eleştirisi).** Kira tabanlı bir kilidin sahibi ölmeden sadece
**duraklayabilir**: GC duraklaması, yavaş disk, ağ gecikmesi. Kira dolar, başka biri kilidi alır, eski sahip uyanır
ve hâlâ kilidin kendisinde olduğunu sanarak yazar. Kilit servisi bunu engelleyemez, çünkü eski sahip kilide hiç
sormadan devam ediyor. Martin Kleppmann'ın Redis'in çok düğümlü kilit algoritması Redlock'a yönelik eleştirisi tam
olarak bu:
1. Redlock, doğruluk için sınırlı saat kayması ve sınırlı süreç duraklaması varsayıyor; gerçek sistemler bu
   varsayımları ihlal eder.
2. Redlock **fencing token** üretmiyor, yani korunan kaynak eski sahibi tanıyamıyor.

Çözüm: kilidi her alışta kesin olarak artan bir token üretmek ve **korunan kaynağın** her yazmada bu token'ı
kontrol etmesi. Bu projede kilit sadece verimlilik için var (aynı işi iki instance yapmasın). **Doğruluk fencing'den
geliyor.** Bu yüzden Redlock değil, tek düğümlü `SET NX PX` + `INCR` yeterli.

Bu projede aynı problem iki kez çıktı ve iki farklı depolamada çözüldü:

| | **Idempotency claim (PostgreSQL)** | **Mutabakat kilidi (Redis)** |
|---|---|---|
| Kira | `idempotency_keys.locked_until` | `SET lock:... NX PX 10000` |
| Token kaynağı | Devralmada `fence_token = fence_token + 1` (aynı upsert ifadesinde) | Kilidi alınca `INCR fence:...` |
| Korunan yazma | Rezervasyon transaction'ının başı (`SELECT ... FOR UPDATE` + token), `COMPLETED`, key'i bırakma | Redis kapı sayacına yazma |
| Kontrol nerede | SQL `WHERE fence_token = :token` | Lua script: `GET fence == token` ise `SET` (atomik) |
| Eski sahip ne yapar | Transaction'ı stoğa dokunmadan geri alınır; yeni sahibin durumunu döner | Yazması reddedilir; sayaç değişmez |
| Kanıt | Unique constraint kaldırılınca fencing'siz 2 rezervasyon oluştu; 3 mutasyon yakalandı | Eski sahip sayacı 8'den 7'ye çekemedi; 3 mutasyon yakalandı |

**Mutabakat işi.** Redis kapı sayacı, Redis'te düşürülüp DB transaction'ı sonuçlanmadan süreç çökerse DB'den
düşük kalır: var olan birimler satılamaz. Bu iş her instance'ta zamanlanmış çalışıyor; kilit, her turda yalnızca
birinin çalışmasını sağlıyor. İş her sayacı DB'deki gerçek kalan stoğa eşitliyor; her yazma fencing'li.
- Canlı doğrulama (`bash bench/verify-reconciler-live.sh`): 3 container, saniyede bir tur. Redis'e 7 birimlik kayma
  enjekte edildi (90 → 83). 3 saniye içinde düzeltildi (token 14, tek düzeltme).

**Kilidi meşru olarak tutan turun canlı trafikle yarışı.** Fencing yalnızca eski sahibi durdurur. Kilidi meşru
olarak tutan tur da DB'yi `t_okuma` anında okuyup Redis'e `t_yazma` anında yazar. Arada diğer instance'lar
çalışmaya devam eder. Turun yazdığı değer bu aradaki Redis değişikliklerini siler:

| Yarışan işlem | Sıra | Sonuç | Yön |
|---|---|---|---|
| Rezervasyon (önce Redis −1, sonra DB commit) | Redis −1 yazmadan önce, DB commit okumadan sonra | Silinen −1 yüzünden Redis, DB'den **yüksek** | Güvenli: son sözü DB veriyor |
| Rollback telafisi (Redis −1, sonra +1) | −1 yazmadan önce, +1 yazmadan sonra | Redis **yüksek** | Güvenli |
| Stok iadesi (önce DB commit, sonra Redis +n) | DB commit okumadan sonra, Redis +n yazmadan önce | Silinen +n yüzünden Redis, DB'den **düşük** | **Zararlı:** var olan birimler reddediliyor; sayaç 0'a inerse "tükendi" kısayolu yanlış cevap veriyor |

- **Yüksek gösterme nasıl kapanıyor:** Fazla birim kadar istek kapıdan geçip DB'den "tükendi" alıyor; sayaç bu
  isteklerle düşüyor. Ya da bir sonraki tur sayacı indiriyor. Kimseye yanlış cevap verilmiyor; tek bedel fazladan
  DB işi.
- **Düşük gösterme nasıl kapanıyor:** Kendiliğinden kapanmıyor, çünkü sayacı artıran tek işlemler iade ve
  rollback. Bir sonraki tura kadar sürüyor (varsayılan 30 sn). O turda da bir iade yarışırsa yeniden oluşabiliyor.
- **Uygulanan çözüm (tek Lua koşulu ve bir sürüm anahtarı):**
  - Düşük gösterme ancak sayacı **indiren** bir yazmayla oluşabiliyor.
  - Her iade, atomik olarak `stock-returns:<id>` sürüm anahtarını artırıyor.
  - Tur bu sürümü DB'den **önce** okuyor.
  - Sayacı artıran yazmaya her zaman izin var, çünkü bu yazma düşük göstermeyi yalnızca azaltabilir.
  - Sayacı indiren yazma yalnızca sürüm değişmemişse yapılıyor. Sürüm değiştiyse yazma atlanıyor ve bir sonraki
    tur yeniden deniyor.
  - Aynı yarış lazy initialization'da da vardı (DB okunur, iade sayaç yokken commit eder, eski değer yazılır). Aynı
    sürüm kontrolüyle kapatıldı.
  - Rollback telafisi artık sayacı yalnızca varsa artırıyor. Redis yeniden başladıktan sonra düz `INCR`, sayacı `1`
    değeriyle yeniden yaratıyordu.
- **Neden mutlak değer yerine fark (delta) uygulanmadı:** Delta da yarışıyor. Redis'i DB'den önce okursak, araya
  giren ve DB'de commit edilen bir rezervasyon iki kez sayılıyor ve sayaç düşük kalıyor. Redis'i DB'den sonra
  okursak, aynı pencere iadeler için açılıyor. Sayacın kendisi üzerinde karşılaştır-ve-yaz (CAS) da işe yaramıyor:
  ABA problemi var (bir iade +1, bir rezervasyon −1 yapınca değer aynı kalıyor), ayrıca sıcak etkinlikte sürekli
  başarısız oluyor. Sadece iadelerin artırdığı bir sürüm ABA'sız ve sıcak yolda maliyetsiz.
- Kanıt: `RedisReconcilerTest`, deterministik:
  - Turun okuması ile yazması arasına bir iade sokuldu. Sayaç 1'de kaldı ve rezervasyon başarılı oldu. Koruma
    yokken sayaç 0'a iniyor.
  - Uçuştaki rezervasyon sayacı 1 fazla bıraktı. Bir sonraki tur farkı kapattı; turu beklemeden gelen istekler de
    farkı tüketti.
  - 6 mutasyonun hepsi yakalandı.
