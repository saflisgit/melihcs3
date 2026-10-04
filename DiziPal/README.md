# DiziPal oynatıcı çözümlemesi

Eklenti cihaz üzerinde çalışır; bilgisayar, ADB, Chrome veya yerel sunucu gerekmez.

1. Bölüm/film sayfasındaki gerçek iframe adresleri okunur. Şifreli `data-rm-k`
   yapılandırması varsa sayfanın güncel JavaScript paketleri incelenir.
2. Paketlenmiş scriptler Cloudstream `getAndUnpack` yardımcısıyla açılır.
   PBKDF2 iterasyon değeri ve anahtar adayları paketten alınır; sayfanın salt/IV
   değerleriyle PBKDF2-HMAC-SHA512 ve AES-CBC uygulanır. Anahtar veya sağlayıcı
   alan adı eklentiye gömülmez.
3. ContentX biçimi `openPlayer` çağrısından tanınır. Kaynak endpoint'i ve
   `m.php → master.m3u8` dönüşümü oynatıcı kodundan okunur. Popup açmak yerine
   oynatıcının kaynak isteği gönderilir. Dönen kaynaklar ve altyazılar korunur.
4. Diğer sağlayıcı adresleri Cloudstream'in `loadExtractor` sistemine aktarılır.

Referanslar: Dizilla/ContentXExtractor, DiziBox/DiziBoxUtils ve
FullHDFilmizlesene/RapidVidExtractor. DiziPal'in mevcut şifrelemesi DiziBox'taki
MD5 türetiminden farklı olduğu için PBKDF2 biçimi ayrıca uygulanır.

JSON-LD içindeki tek nesne/dizi biçimleri desteklenir. Bölüm verisi eksikse
`.season-lists` altındaki bağlantılar okunur; URL son ekleri korunur.

Site şifreleme algoritmasını veya oynatıcı protokolünü tamamen değiştirirse
uyarlama gerekir. Mevcut algoritma içinde anahtar, script URL'si ve sağlayıcı
alan adı değişiklikleri sayfadan yeniden çözülür. Siteye giriş adresi `mainUrl`
alanındadır; bu mekanizma yeni site alan adını internette aramaz.

## Cihaz doğrulaması — 4 Ekim 2026

DiziPal v33, ADB'ye bağlı Android 9 cihazında, yerel servis kapalıyken denendi:

| İçerik | Sonuç |
| --- | --- |
| Amsterdam Centraal 24/7 S1E1 | Cloudstream'de görüntü oluştu, 1920×1080, süre 00:38'e ilerledi. |
| Amsterdam filmi | Farklı `org` sağlayıcısından çözüldü; 1920×804, süre 00:21'e ilerledi. |
| Reacher S1E1 (`-c02`) | Ana/alt manifest HTTP 200 ve `#EXTM3U`; ilk video parçası HTTP 206. Oynatıcıda ayrıca denenmedi. |

Amsterdam dizisinin 6 bölümü listelendi. Amsterdam dizisinde 4, Reacher'da 2
altyazı kaynağı çıkarıldı. Testteki HLS adresleri/tokenlar kaynak koduna kaydedilmez.
