# Bilinen sınırlamalar ve gelecek işler

Bilerek kapsam dışında bırakılanlar, ölçülmemiş maliyetler ve ölçümlerin ortaya çıkardığı ama bu projede yapılmayan işler.

## Bilinen sınırlamalar

| Sınırlama | Etki | Çözüm planı |
|---|---|---|
| **Idempotency claim'i her başarılı rezervasyona bir commit ekliyor.** | Yazma ağırlıklı senaryoda throughput'a etkisi ayrıca ölçülmedi: final benchmark'ta claim'i kapatan bir anahtar yok. Bugünkü 1 shard tavanı (~290/sn) Aşama 2'nin ~600'ünün yarısı, ama aradaki farka claim dışında başka değişiklikler de katkı veriyor. | Claim'i kapatan bir benchmark anahtarı ile ölçmek (açık). |
| **Zehirli mesaj partition'ı durdurabilir.** Consumer'da işlenemeyen bir mesaj yeniden denenirken aynı partition'daki sonraki mesajlar bekliyor (geliştirme sırasında bir NULL hatasıyla yaşandı). | Bir hata, bir partition'daki bütün olayları geciktirir. | **Aşama 7a'da çözüldü:** 3 deneme, sonra dead-letter topic. |
| **DLT'deki mesajları yeniden yayınlayan bir araç yok.** | Hata düzeltilince mesajlar elle yeniden yayınlanmalı (consumer idempotent olduğu için güvenli). | Kapsam dışı; `reservation.consumer.dead_lettered` sayacı ve ERROR log'u alarm için var. |
| **Yük atma limiti HTTP girişinde ve sabit (100).** DB adımında 20 denendi, daha kötü çıktı: "tükendi" okuması da bağlantı havuzunu kullandığı için havuz kuyruğu sınırsız kaldı (Aşama 7a, ikinci tur). | Contention'da p99 2,0–2,4 sn; limit 20 bunu yarıya indirebilirdi. | Aşama 8: "tükendi" işaretini havuz dışına almak (Redis/bellek) ya da okumalara ayrı havuz, sonra DB adımında ≈ havuz boyutu; ya da adaptive concurrency. |
| **nginx'in sağlık kontrolü pasif.** Donmuş bir instance ancak üzerindeki istekler zaman aşımına uğrayınca (15 sn) çıkarılıyor. | Bu pencerede her istemcinin bir isteği takılıyor (100 istemcide ~100 zaman aşımı). `max_fails=1` sorunu düzeltildi: aşırı yükte artık sağlıklı instance çıkarılmıyor. | Aktif sağlık kontrolü ve outlier detection (HAProxy/Envoy). |
| **Havuz zaman aşımında claim'in bırakılması da bağlantı alamayabilir.** | Aynı key ile tekrar deneme, claim'in kirası (30 sn) dolana kadar "işlemde" (409) alır. | Not edildi. |
| **Outbox tablosu büyüyor.** Yayınlanmış satırlar silinmiyor. | Disk kullanımı zamanla artar. | Saklama süresi dolanları silen bir temizlik işi (kapsam dışı, not edildi). |
| **Shard sayısı etkinlik oluşturulurken sabitleniyor.** | Yayındaki bir etkinliğin shard sayısı değiştirilemez (resharding yok). | Kapsam dışı. |
| **Redis düşüşü ile DB sonucu arasında çökme.** Redis'ten birim düşüldükten sonra, DB transaction'ı sonuçlanıp telafi (`INCR`) çalışmadan uygulama çökerse, düşülen birim Redis'e hiç iade edilmez. | Redis sayacı bir sonraki mutabakat turuna kadar (varsayılan 30 sn) DB'den düşük kalır: o birimler bu süre boyunca satılamaz. | **Aşama 6'da çözüldü:** fencing'li mutabakat işi. |

## Gelecek işler

Ölçümlerin ortaya çıkardığı ama bu projenin kapsamı dışında bırakılan işler:

| İş | Neden |
|---|---|
| **"Tükendi" işaretini bağlantı havuzunun dışına almak** (Redis'te ya da instance içinde kısa ömürlü önbellekte), sonra yük atma limitini DB adımına ≈ havuz boyutu (20) olarak taşımak | Aşama 7a: DB adımındaki limit 20 kaybetti, çünkü sınırsız "tükendi" okumaları aynı havuz kuyruğunda bekliyordu (ortalama 64–368, en yüksek 2.184 bekleyen thread). Okuma havuzu kullanmazsa Little yasasının öngördüğü kazanç (contention'da yarı gecikme) sellout'u yavaşlatmadan alınabilir. |
| **Okumalar için ayrı küçük bir bağlantı havuzu (bulkhead)** | Yukarıdakinin alternatifi: ucuz okumalar pahalı transaction'larla aynı kuyruğu paylaşmaz. |
| **Kendini ayarlayan eşzamanlılık limiti** (adaptive concurrency: Gradient/Vegas) | Sabit bir limit iş yükleri arasında taşınmıyor: contention için ~20, ucuz cevaplar için yüzler doğru. Limiti ölçülen gecikmeye göre ayarlamak ikisini birden karşılar. |
| **Aktif sağlık kontrolü ve outlier detection** (HAProxy/Envoy) | nginx'in pasif kontrolü donmuş bir instance'ı ancak istekler zaman aşımına uğrayınca fark ediyor (100 istemcide ~100 takılan istek). 10 sn'lik bağlantı zaman aşımı da bir takas (2 sn, aşırı yükte sağlıklı instance'ları çıkarıyordu). Ayrı yoklama isteği ikisini de çözer. |
| **Thundering-herd ölçümünde görülen 1 sn periyodikliğin kaynağı** | Jitter'la bozulmadığı için senkron tekrar denemelerden gelmiyor. Aday: saniyede bir çalışan hold süresi süpürücüsü ya da GC; süpürücü aralığı değiştirilerek test edilebilir. |
| **Yük üreticisini sistemden ayırmak** (k6 ayrı makinede ya da sınırlı CPU ile) | Bütün ölçümlerde k6, nginx ve üç JVM aynı makinede; yük üretici sistemle aynı CPU'yu paylaşıyor. Mutlak sayılar bu yüzden "bu laptop"a özgü; karşılaştırmalar aynı koşullarda. |
| **DLT'deki mesajları yeniden yayınlama aracı, outbox temizliği, resharding** | Bilinen sınırlamalar tablosunda; işlevsel olarak gerekli ama bu projenin konusu olan eşzamanlılık ve doğruluk sorularına bir şey eklemiyor. |
