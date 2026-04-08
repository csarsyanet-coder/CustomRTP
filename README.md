# TopUpPlugin

Sistem **Arsya Premium Coin** berbasis **proxy + bridge HTTP** untuk Minecraft.

Repository ini berisi **dua plugin utama**:

1. **ProxyCoinsHttpCore** *(Velocity / Proxy side)*
2. **ArsyaPremiumBridgeHttp** *(Paper / Purpur / Server side)*

Tujuan sistem ini adalah membuat **saldo coin premium terpusat di proxy**, lalu bisa dipakai di semua server yang terhubung.

---

## Arsitektur Singkat

### 1) ProxyCoinsHttpCore
Plugin inti yang berjalan di **Velocity**.

Fungsi utamanya:
- menyimpan saldo premium coin di **SQLite**
- memproses top up dari webstore/API
- menyimpan transaksi yang sudah diproses agar tidak dobel
- menyediakan **HTTP API internal** untuk server lain
- menangani tambah, kurang, set, dan cek saldo

### 2) ArsyaPremiumBridgeHttp
Plugin bridge yang berjalan di **Paper/Purpur**.

Fungsi utamanya:
- mengambil saldo dari proxy lewat HTTP
- menyediakan command `/c`
- menyimpan cache saldo di server
- bisa dipakai untuk **PlaceholderAPI / scoreboard**

---

## Struktur Repository

```text
TopUpPlugin/
├─ .github/workflows/
├─ proxycoins-http-core-1.0.0/
│  └─ proxycoins-http-core/
└─ arsya-premium-bridge-http-1.0.0/
   └─ arsya-premium-bridge-http/
```

---

# Bagian A — ProxyCoinsHttpCore

## Informasi Dasar

- **Nama plugin:** ProxyCoinsHttpCore
- **Platform:** Velocity
- **Group ID:** `dev.arsyadev`
- **Artifact ID:** `proxycoins-http-core`
- **Versi:** `1.0.0`
- **Java:** `21`

## Fungsi

Plugin ini adalah pusat seluruh sistem coin.
Semua saldo premium disimpan di proxy, bukan di server Paper/Purpur.

## Fitur Utama

- database SQLite untuk saldo player
- sinkronisasi username + UUID saat player login ke proxy
- cek top up otomatis saat login proxy *(jika diaktifkan)*
- API internal untuk server
- proteksi transaksi dobel lewat `processed_transactions`

## Endpoint Internal API

Plugin proxy menyediakan endpoint berikut:

- `GET /internal/balance`
- `POST /internal/check-topup`
- `POST /internal/add-balance`
- `POST /internal/take-balance`
- `POST /internal/set-balance`

Semua endpoint memakai token internal lewat header:

```text
X-APC-Token
```

## File Config Default

Contoh config bawaan:

```yml
database:
  file: "coins.db"

webstore:
  endpoint: "https://arsyanet.site/api.php"
  user-agent: "Bedrock-Server"
  timeout-ms: 5000
  check-on-proxy-login: true

internal-api:
  host: "127.0.0.1"
  port: 37841
  token: "ganti-token-internal-ini"

messages:
  currency-name: "Arsya Premium Coin"

logging:
  verbose: false
```

## Penjelasan Config Proxy

### `database.file`
Nama file database SQLite.

Contoh:
```yml
database:
  file: "coins.db"
```

### `webstore.endpoint`
URL API webstore / top up.

Plugin proxy akan mengakses endpoint ini untuk:
- cek transaksi baru
- confirm transaksi yang sudah diproses

### `webstore.user-agent`
User-Agent HTTP saat proxy mengakses API top up.

### `webstore.timeout-ms`
Timeout request ke webstore.

### `webstore.check-on-proxy-login`
Kalau `true`, setiap kali player login ke proxy, plugin akan otomatis cek top up.

### `internal-api.host`
Host tempat API internal dibuka.

### `internal-api.port`
Port API internal.

### `internal-api.token`
Token keamanan untuk komunikasi antara proxy dan server.

### `messages.currency-name`
Nama mata uang yang ditampilkan ke player.

### `logging.verbose`
Kalau `true`, log debug akan lebih banyak.

## Database

Plugin proxy menyimpan data ke SQLite.

File yang biasanya muncul:

- `coins.db`
- `coins.db-wal`
- `coins.db-shm`

### Fungsi file SQLite
- `coins.db` = file database utama
- `coins.db-wal` = write-ahead log
- `coins.db-shm` = shared memory untuk WAL mode

Kalau proxy masih hidup, perubahan terbaru bisa berada di `coins.db-wal`, jadi snapshot database paling aman adalah mengambil **ketiga file** sekaligus.

## Tabel Database

### `players`
Menyimpan data player:
- `uuid`
- `last_name`
- `first_seen`
- `last_seen`
- `balance`

### `processed_transactions`
Menyimpan ID transaksi yang sudah pernah diproses.

Tujuannya agar top up tidak dihitung dua kali.

---

# Bagian B — ArsyaPremiumBridgeHttp

## Informasi Dasar

- **Nama plugin:** ArsyaPremiumBridgeHttp
- **Platform:** Paper / Purpur
- **Group ID:** `dev.arsyadev`
- **Artifact ID:** `arsya-premium-bridge-http`
- **Versi:** `1.0.0`
- **Java:** `21`
- **Softdepend:** `PlaceholderAPI`

## Fungsi

Plugin ini adalah bridge dari server menuju proxy coin system.

Fungsinya:
- ambil saldo dari proxy lewat HTTP
- menyediakan command `/c`
- menyimpan cache saldo player di server
- menyediakan placeholder untuk scoreboard

## Config Default Bridge

```yml
proxy-api:
  base-url: "http://127.0.0.1:18080"
  token: "mbud"
  timeout-ms: 5000

messages:
  currency-name: "Arsya Premium Coin"
```

## Penjelasan Config Bridge

### `proxy-api.base-url`
Alamat API proxy.

Contoh:
```yml
base-url: "http://127.0.0.1:18080"
```

### `proxy-api.token`
Token internal. Harus sama dengan token di proxy.

### `proxy-api.timeout-ms`
Timeout request dari server ke proxy.

### `messages.currency-name`
Nama mata uang yang ditampilkan.

---

# Command Bridge

Command utama bridge adalah:

```text
/c
```

## Subcommand

### `/c saldo`
Melihat saldo sendiri.

### `/c cektopup`
Cek top up sendiri dari proxy/webstore.

### `/c refresh`
Refresh saldo sendiri dari proxy.

### `/c check <player>`
Cek saldo player lain.

### `/c addcoin <player> <amount>`
Tambah saldo player.

### `/c takecoin <player> <amount>`
Kurangi saldo player.

### `/c setcoin <player> <amount>`
Set saldo player.

### `/c reload`
Reload config plugin bridge.

---

# Permission Bridge

```text
arsyacoin.command.saldo
arsyacoin.command.cektopup
arsyacoin.command.refresh
arsyacoin.command.check
arsyacoin.command.add
arsyacoin.command.take
arsyacoin.command.set
arsyacoin.command.reload
arsyacoin.admin
```

## Penjelasan Permission

### `arsyacoin.command.saldo`
Izin pakai `/c saldo`

### `arsyacoin.command.cektopup`
Izin pakai `/c cektopup`

### `arsyacoin.command.refresh`
Izin pakai `/c refresh`

### `arsyacoin.command.check`
Izin pakai `/c check`

### `arsyacoin.command.add`
Izin pakai `/c addcoin`

### `arsyacoin.command.take`
Izin pakai `/c takecoin`

### `arsyacoin.command.set`
Izin pakai `/c setcoin`

### `arsyacoin.command.reload`
Izin pakai `/c reload`

### `arsyacoin.admin`
Akses admin penuh.

## Contoh LuckPerms

### Admin full access
```text
/lp group admin permission set arsyacoin.admin true
```

### Staff hanya cek dan refresh
```text
/lp group staff permission set arsyacoin.command.check true
/lp group staff permission set arsyacoin.command.refresh true
```

### Default player
```text
/lp group default permission set arsyacoin.command.saldo true
/lp group default permission set arsyacoin.command.refresh true
/lp group default permission set arsyacoin.command.cektopup true
```

---

# PlaceholderAPI

Plugin bridge memiliki softdepend PlaceholderAPI.

Placeholder yang umum dipakai:

```text
%apc_balance%
%apc_saldo%
```

Biasanya dipakai untuk scoreboard/sidebar.

---

# Cara Kerja Sistem

## Saat player login ke proxy
1. player login ke Velocity
2. proxy update data UUID + username ke database
3. jika `check-on-proxy-login: true`, proxy akan cek top up otomatis

## Saat top up masuk dari webstore
1. proxy minta data transaksi ke endpoint webstore
2. proxy baca transaksi baru
3. kalau `trx_id` belum pernah diproses, saldo ditambah
4. `trx_id` disimpan ke `processed_transactions`
5. proxy melakukan confirm transaksi ke webstore

## Saat server ingin cek saldo
1. bridge kirim request ke proxy API
2. proxy baca saldo dari database
3. hasil dikirim balik ke bridge
4. bridge cache saldo untuk dipakai command / placeholder

---

# Build

## ProxyCoinsHttpCore
Build dari folder:

```text
proxycoins-http-core-1.0.0/proxycoins-http-core
```

Command:

```bash
mvn clean package
```

Hasil jar biasanya ada di folder:

```text
target/
```

## ArsyaPremiumBridgeHttp
Build dari folder:

```text
arsya-premium-bridge-http-1.0.0/arsya-premium-bridge-http
```

Command:

```bash
mvn clean package
```

---

# Instalasi

## 1) Install ProxyCoinsHttpCore ke Velocity
- build plugin proxy
- upload jar ke folder plugin Velocity
- start proxy sekali agar config dibuat
- edit `config.yml`
- isi endpoint webstore, host, port, dan token
- restart proxy

## 2) Install ArsyaPremiumBridgeHttp ke Server
- build plugin bridge
- upload jar ke folder `plugins/` server Paper/Purpur
- start server sekali agar config dibuat
- edit `plugins/ArsyaPremiumBridgeHttp/config.yml`
- isi `base-url` ke alamat proxy API
- samakan token dengan proxy
- restart server

---

# Contoh Setup

## Proxy
```yml
internal-api:
  host: "0.0.0.0"
  port: 18080
  token: "mbud"
```

## Bridge
```yml
proxy-api:
  base-url: "http://n.arsyanet.my.id:18080"
  token: "mbud"
  timeout-ms: 5000
```

---

# Troubleshooting

## Saldo tidak update di scoreboard
- cek apakah bridge bisa konek ke proxy
- cek token sama atau tidak
- jalankan:

```text
/c refresh
```

## `/c cektopup` gagal
- cek `webstore.endpoint`
- cek apakah API webstore aktif
- cek apakah token / URL salah

## Proxy jalan tapi server tidak bisa cek saldo
- cek `internal-api.host`
- cek `internal-api.port`
- cek firewall / port exposure
- cek token harus sama

## Top up masuk dobel
Normalnya tidak boleh dobel karena plugin menyimpan `trx_id` ke tabel `processed_transactions`.

## File `coins.db` terlihat tidak update
Kalau proxy masih hidup, data terbaru bisa ada di:
- `coins.db`
- `coins.db-wal`
- `coins.db-shm`

Jadi jangan hanya ambil `coins.db` saat ingin snapshot live.

---

# Catatan

- sistem ini didesain agar saldo premium coin terpusat di proxy
- server tidak menyimpan saldo utama
- bridge hanya menjadi penghubung dan cache
- shop/plugin lain nantinya bisa memakai API proxy yang sama

---

# Author

**ArsyaDev**
