# CF VPN Client — ساختار پروژه

این پروژه یک اسکلت کامل و قابل‌کامپایل (با تکمیل چند بخش مشخص‌شده با TODO) برای اپلیکیشنی است که:

1. از طریق یک Cloudflare Worker (به عنوان outbound WebSocket/TLS) با هسته‌ی **Xray-core** یک تانل VPN برقرار می‌کند.
2. یک پنل تنظیمات برای دیپلوی خودکار Worker با استفاده از Cloudflare API دارد.

## ساختار فایل‌ها

```
app/src/main/java/com/example/cfvpn/
  data/
    ConfigModels.kt          -> WorkerConfig, CloudflareCredentials, AppConfig, DeployResult
    SettingsDataStore.kt      -> DataStore + EncryptedFile برای ذخیره‌ی امن توکن
    CloudflareRepository.kt   -> interface + پیاده‌سازی OkHttp برای deployWorkerToCloudflare
  vpn/
    XrayConfigBuilder.kt       -> ساخت JSON کانفیگ Xray (VLESS+WS+TLS) از فیلدهای ورودی
    XrayCoreManager.kt          -> کپی باینری xray از assets، chmod، اجرا با ProcessBuilder
    CfVpnService.kt              -> android.net.VpnService: برپاسازی TUN + اجرای xray
  viewmodel/
    MainViewModel.kt            -> StateFlow برای Config/ConnectionState/Deploy
  ui/
    MainActivity.kt              -> Scaffold + BottomNavigation (Home/Settings)
    HomeScreen.kt                  -> دکمه Connect، کارت سرعت، فرم Worker
    SettingsScreen.kt               -> فرم Cloudflare + دکمه Deploy Worker
```

## نکات معماری مهم که باید قبل از اجرا روی دستگاه واقعی تکمیل شوند

### ۱. باینری Xray
باینری کامپایل‌شده‌ی `xray` (خروجی پروژه‌ی متن‌باز XTLS/Xray-core، متناسب با ABI گوشی —
معمولاً `arm64-v8a`) را در مسیر زیر قرار دهید:

```
app/src/main/assets/xray
```

### ۲. لایه‌ی tun2socks — اکنون کاملاً خودکار
`VpnService` در اندروید فقط یک فایل‌دیسکریپتور TUN خام (بسته‌های IP) در اختیار می‌گذارد؛
خودش بسته‌ها را به SOCKS محلی که xray باز کرده هدایت نمی‌کند. این پروژه از
[`hev-socks5-tunnel`](https://github.com/heiher/hev-socks5-tunnel) (کتابخانه‌ی سبک و
متن‌باز tun2socks) از طریق JNI استفاده می‌کند:

- `vpn/Tun2SocksManager.kt` — کلاس Kotlin که کانفیگ YAML را می‌سازد و توابع native را صدا می‌زند
- `cpp/tun2socks_jni.c` — پل JNI که مستقیماً روی API عمومیِ مستندشده‌ی hev-socks5-tunnel
  (`hev_socks5_tunnel_main_from_str` / `_quit` / `_stats`) پیاده شده
- `cpp/CMakeLists.txt` — **با `ExternalProject_Add` سورس hev-socks5-tunnel را در همان لحظه‌ی
  build از GitHub می‌گیرد** و با همان کامپایلر/آرشیوکننده‌ی NDK که Android Gradle Plugin
  برای هر ABI انتخاب کرده (`CMAKE_C_COMPILER` / `CMAKE_AR` / `CMAKE_STRIP`) می‌سازد، سپس
  `libhev-socks5-tunnel.a` نتیجه را مستقیماً به `libcfvpn_tun2socks.so` لینک می‌کند
- `CfVpnService.kt` — ترتیب صحیح را اجرا می‌کند: ابتدا xray، سپس TUN، سپس tun2socks

**یعنی دیگر لازم نیست خودتان چیزی clone/کامپایل/کپی کنید** — کافی است پروژه را در
Android Studio باز کنید و Run بزنید؛ در اولین build:

1. Gradle چون `ndkVersion` در `app/build.gradle.kts` مشخص شده، در صورت نبودِ NDK آن را
   خودش از SDK Manager دانلود می‌کند (به دسترسی اینترنتِ خودِ دستگاه/سرورِ build نیاز دارد،
   نه محیط sandbox من).
2. CMake حین build، سورس hev-socks5-tunnel را با `git clone --recursive` می‌گیرد.
3. با `make static` و کامپایلر NDK همان ABI، `libhev-socks5-tunnel.a` ساخته می‌شود.
4. نتیجه به‌صورت خودکار به `libcfvpn_tun2socks.so` لینک و در APK نهایی بسته‌بندی می‌شود.

**پیش‌نیازهای ماشینی که Gradle روی آن اجرا می‌شود** (این‌ها بخشی از خودِ اندروید استودیو
نیستند و باید روی سیستم‌عامل موجود باشند):
- `git` (برای کلون‌کردن hev-socks5-tunnel)
- `make` (GNU Make — برای اجرای Makefile خودِ آن پروژه؛ روی لینوکس/مک پیش‌فرض هست، روی
  ویندوز از طریق Git Bash یا WSL در دسترس است)

> اگر می‌خواهید کنترل بیشتری روی نسخه داشته باشید، `GIT_TAG main` را در
> `cpp/CMakeLists.txt` به یک شماره نسخه‌ی مشخص (مثلاً یک تگ ریلیز از
> https://github.com/heiher/hev-socks5-tunnel/releases) تغییر دهید تا build شما
> با آپدیت‌های آینده‌ی upstream غافلگیر نشود.

### ۳. کد Worker
`SettingsScreen` یک نمونه‌ی placeholder برای کد جاوااسکریپت Worker می‌فرستد. کد واقعی Worker
(که ترافیک VLESS/WebSocket را روی Cloudflare اجرا می‌کند) را باید در پروژه‌ی خودتان قرار داده
و در `deployWorker(...)` جایگزین کنید — این کد از استاندارد عمومی و مستندِ Cloudflare Workers
API برای آپلود اسکریپت (`PUT /accounts/{id}/workers/scripts/{name}`) استفاده می‌کند.

### ۴. آماری واقعی سرعت
`MainViewModel.startFakeStatsTicker()` محل مناسبی برای اتصال به آمار واقعی ترافیک است
(مثلاً با خواندن شمارنده‌های `TrafficStats` مربوط به UID اپ، یا آماری که خود xray از طریق
API داخلی گزارش می‌دهد).

## ساخت (Build) خودکار با GitHub Actions

چون در محیط sandbox من `dl.google.com` بلاک است ولی ران‌رهای گیت‌هاب دسترسی کامل دارند،
یک workflow آماده اضافه شده: `.github/workflows/build.yml`

- با هر `push`/`pull_request` به شاخه‌ی `main`، یا دستی از تب **Actions** (دکمه‌ی
  *Run workflow*)، اجرا می‌شود.
- Android SDK و NDK را خودش نصب می‌کند (نیازی به نصب چیزی روی سیستم شما نیست).
- `./gradlew assembleDebug` را اجرا می‌کند — که همان‌طور که در بخش قبل توضیح داده شد،
  hev-socks5-tunnel را هم خودکار clone/کامپایل می‌کند.
- فایل APK نهایی را به‌عنوان **Artifact** در همان صفحه‌ی اجرای workflow قابل‌دانلود می‌گذارد
  (تب Actions → روی اجرای مربوطه کلیک کنید → پایین صفحه بخش Artifacts).

برای استفاده:
1. این پروژه را در یک ریپازیتوری گیت‌هاب push کنید (شامل پوشه‌ی `.github/workflows/`).
2. باینری `xray` را در `app/src/main/assets/xray` قرار دهید (وگرنه build موفق می‌شود ولی
   یک هشدار در لاگ workflow می‌بینید که یادآوری می‌کند در زمان اجرا کرش خواهد کرد).
3. به تب **Actions** بروید و منتظر بمانید تا build سبز شود، سپس APK را از Artifacts دانلود کنید.

ساخت نسخه‌ی release امضاشده هم به‌صورت job کامنت‌شده در همان فایل آماده است؛ فقط باید
چهار secret امضا (`SIGNING_KEYSTORE_BASE64` و…) را در تنظیمات ریپازیتوری اضافه کرده و
job را از کامنت خارج کنید — جزئیات دقیقاً به‌صورت کامنت در خودِ `build.yml` نوشته شده.

## امنیت توکن‌ها
`SettingsRepository` مقدار `apiToken` را **در DataStore ذخیره نمی‌کند** (چون DataStore
Preferences رمزنگاری‌شده نیست)؛ به‌جای آن با `androidx.security.crypto.EncryptedFile`
(بر پایه‌ی Tink/Keystore) در یک فایل جدا رمزنگاری می‌شود. توصیه می‌شود به‌جای Global API Token
از یک **Scoped API Token** با دسترسی محدود به `Workers Scripts: Edit` استفاده کنید تا در صورت
افشا، آسیب محدود بماند.
