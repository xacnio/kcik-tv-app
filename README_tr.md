# KcikTV - Android TV İstemcisi

Kick.com platformu için geliştirilmiş Android TV istemcisi. D-Pad navigasyonu ve düşük gecikmeli oynatma özellikleriyle TV ekranları için tasarlanmıştır.

[Click here for English Version](README.md)

[![Build and Release](https://github.com/xacnio/kcik-tv-app/actions/workflows/release.yml/badge.svg)](https://github.com/xacnio/kcik-tv-app/actions/workflows/release.yml)
[![Website](https://img.shields.io/badge/Web_Sitesi-KcikTV-53FC18)](https://xacnio.github.io/kcik-tv-app/)


## 📺 Özellikler

- **Ultra Düşük Gecikme**: En az gecikme ile en hızlı HLS akışı için Amazon IVS ve Media3 ile optimize edilmiştir.
- **Tam Uzaktan Kumanda Optimizasyonu**: D-Pad navigasyonu ile TV deneyimi.
- **Gelişmiş Sohbet (Chat) Sistemi**:
    - Kullanıcı rozetleri ile gerçek zamanlı etkileşim.
    - Tam Emote Desteği: Sabit ve **hareketli (GIF/WebP)** ifadeler.
    - TV donanımları için optimize edilmiş, kasmayan akıcı chat akışı.
- **Çift Giriş Yöntemi**:
    - **QR Kod ile Giriş**: Mobil cihazınızdan QR kodu taratarak saniyeler içinde giriş yapın.
    - **Manuel Giriş**: Kullanıcı adı/e-posta ve şifre ile giriş, tam **2FA (OTP)** desteği.
- **Dinamik Arayüz**:
    - **Odaklanma Sistemi**: Yarı saydam arka planlar ve kenarlıklar ile belirgin odak durumu.
    - **Tema Motoru**: Tüm arayüzü etkileyen çoklu tema renkleri (Elektrik Mavisi, Gece Yarısı Mavisi, Okyanus Mavisi vb.).
    - **Ayarlanabilir Boşluklar**: TV izleme mesafesine göre optimize edilmiş düzen.
- **Küresel Yayın Keşfi**:
    - **Dil Seçenekleri**: Tek bir tuş altında toplanmış çoklu dil seçim sidebar'ı.
    - **Dinamik Sıralama**: Öne Çıkanlar, İzleyici Sayısı (Çok/Az).
- **Yayın İstatistikleri**: Gerçek zamanlı teknik bilgiler (Çözünürlük, FPS, Bit hızı, Gecikme, Tampon durumu).
- **Hızlı Kanal Navigasyonu**: CH+/CH- tuşları veya doğrudan numara tuşlayarak kanallar arası geçiş.
- **Resim içinde Resim (PIP) Desteği (Mobil)**: Diğer uygulamaları kullanırken yayını izlemeye devam edin. Oynat/Duraklat ve "Canlı" kontrol butonlarını içerir.
- **Arka Plan Ses Modu (Mobil)**: 
    - Sistem medya bildirimi ile tam arka plan oynatma desteği.
    - **Otomatik Veri Tasarrufu**: Arka plan modunda video kalitesini dinamik olarak düşürür (360p veya altı).
    - Android Media Session ile müzik çalar benzeri kontroller.
- **Gelişmiş Hareket Motoru (Mobil)**:
    - **Kenar Ölü Bölgeleri**: Sistem navigasyon hareketleriyle çakışmayı önlemek için 48dp kenar koruması.
    - **İki Parmakla Pan**: Ekranı kapla (FILL) modunda videoyu sürükleyip taşıyabilme.
    - **Otomatik Sohbet Gizleme**: Sol menü açıldığında sohbet paneli kullanım kolaylığı için otomatik olarak kapanır.

## 🎮 Navigasyon ve Kontroller

| Tuş | İşlem |
|-----|-------|
| **Yukarı (D-Pad)** | Sonraki kanal (Zap) |
| **Aşağı (D-Pad)** | Önceki kanal (Zap) |
| **Sol (D-Pad)** | Kanal Listesini açar (Tekrar basınca Ana Menü) |
| **Sağ (D-Pad)** | Chat panelini açar/kapatır |
| **Orta Tuş (OK)** | Bilgi Ekranını (Kanal Bilgisi / İstatistikler) açar |
| **OK (Info açıkken)** | Oynatma Ayarlarını (Hızlı Menü) açar |
| **Back (Geri)** | Açık menüyü / Arama panelini kapatır / Uygulamadan çıkar |
| **Numerik (0-9)** | Doğrudan o sıradaki kanala atlar |
| **CH+ / CH-** | Sonraki / Önceki kanal |

### 📱 Mobil Dokunmatik Kontroller

| Hareket | İşlem |
|---------|-------|
| **Yukarı Kaydır** | Sonraki kanal |
| **Aşağı Kaydır** | Önceki kanal |
| **Sola Kaydır** | Kanal Listesi / Menü açar |
| **Sağa Kaydır** | Chat aç/kapat (sadece izlerken) |
| **Sağa Kaydır** | Geri (menü açıkken) |
| **Tek Dokunuş** | Bilgi Ekranını göster |
| **Çift Dokunuş** | Video formatı değiştir (Sığdır/DOLDUR) |
| **Pinch Zoom** | Video formatı değiştir (Sığdır/DOLDUR) |
| **İki Parmakla Kaydır**| Videoyu taşı (sadece DOLDUR modunda) |
| **Ana Ekran Tuşu** | PIP moduna girer (yayın açıkken) |

## 🛠️ Teknolojiler

- **Kotlin** - %100 Kotlin kod tabanı.
- **Media3 / Amazon IVS** - Yüksek performanslı video oynatma.
- **Retrofit 2** - REST API entegrasyonu.
- **Pusher Client** - Sohbet için gerçek zamanlı WebSocket bağlantısı.
- **Glide** - Hareketli WebP/GIF destekli görsel yükleme.
- **Coroutines & Flow** - Modern reaktif asenkron yönetim.
- **Material Components** - TV için optimize edilmiş tasarım bileşenleri.

## 🏗️ Proje Yapısı

```
app/src/main/java/dev/xacnio/kciktv/
├── data/
│   ├── api/          # Retrofit Servis Tanımları
│   ├── chat/         # WebSocket ve Chat Mantığı
│   ├── model/        # Veri Yapıları (Kick API Varlıkları)
│   ├── prefs/        # Yerel Ayarlar ve Auth Depolama
│   └── repository/   # Veri Katmanı / API Soyutlamaları
└── ui/
    ├── activity/     # PlayerActivity (Ana Arayüz Kontrolcü)
    └── adapter/      # Optimize Edilmiş Adapterlar (Chat, Kanallar, Ayarlar)
```

## 📦 Başlangıç

### Gereksinimler
- Android Studio Ladybug veya üzeri.
- Android SDK 21+ (Çoğu TV Box ve Stick ile uyumlu).
- Gradle 8.2+.

### Kurulum
1. Projeyi klonlayın:
   ```bash
   git clone https://github.com/xacnio/kcik-tv-app.git
   ```
2. Android Studio ile projeyi açın.
3. Gradle dosyalarını senkronize edin.
4. Android TV cihazınızda veya Emülatörde çalıştırın.

## 🔧 Derleme Komutları

```bash
# Debug APK Üret
./gradlew assembleDebug

# Release APK Üret
./gradlew assembleRelease
```

## 📋 Hedef Cihazlar

- Android TV Box / Stick (Xiaomi Mi Box, Shield TV, vb.)
- Akıllı TV'ler (Sony, Philips, TCL, vb.)
- Amazon Fire TV / FireStick.
- Google TV.

## 📝 Lisans

MIT Lisansı ile korunmaktadır.

## 📸 Ekran Görüntüleri

Uygulama içi ekran görüntülerine [docs/screenshots/](/docs/screenshots) dizininden ulaşabilirsiniz.

## 🌐 Web Sitesi ve Yayınlar

- **Tanıtım Sayfası:** [xacnio.github.io/kcik-tv-app](https://xacnio.github.io/kcik-tv-app/)
- **Son Sürüm:** [APK İndir](https://github.com/xacnio/kcik-tv-app/releases/latest)


## ☕ Destek
Projeyi beğendiyseniz bir kahve ısmarlayarak destek olabilirsiniz!

<a href="https://buymeacoffee.com/xacnio"><img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" width="200" ></a>


## 👤 Geliştirici

Geliştirici: **xacnio** (Alperen Çetin)

---
*Bu proje Yapay Zeka (AI) desteği ile geliştirilmiştir.*