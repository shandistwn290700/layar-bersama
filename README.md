# Layar Bersama

Menampilkan presentasi, gambar, dan video **serentak di beberapa TV** lewat jaringan, sebagai pengganti splitter HDMI.

Cara kerjanya: setiap TV membuka satu halaman web dari server dan mengunduh filenya sendiri. Server hanya mengirim perintah ("tampilkan slide 3", "putar video dari detik 12"), sehingga gambar tetap tajam dan semua TV berpindah bersamaan. Video disinkronkan dengan jam server, biasanya selisihnya di bawah 0,1 detik.

- **Halaman kontrol** (`/`): unggah file, pindah slide, putar/jeda/geser video, hitamkan layar. Bisa dibuka dari laptop atau HP.
- **Halaman TV** (`/tv?name=TV1`): layar penuh, tanpa tombol apa pun.

## Menjalankan di server

### Pilihan A: Docker / Coolify

```
docker build -t layar-bersama .
docker run -d --name layar-bersama --restart unless-stopped -p 3000:3000 \
  -e CONTROL_PIN=1234 -v layar-data:/data layar-bersama
```

Di Coolify, buat aplikasi dari folder atau repo ini (build pack: Dockerfile, port 3000), tambahkan *persistent storage* ke `/data`, lalu set variabel `CONTROL_PIN`.

Kalau domain lewat **Cloudflare Tunnel**, isi domain di Coolify dengan `http://` (bukan `https://`). HTTPS sudah ditangani Cloudflare; memakai `https://` di Coolify membuat redirect berulang.

Untuk memperbarui setelah ada perubahan kode: push ke GitHub, lalu tekan **Redeploy** di Coolify. File yang sudah diunggah tetap aman di storage `/data`.

### Pilihan B: Node.js langsung

Butuh Node.js 18 atau lebih baru.

```
npm install
CONTROL_PIN=1234 npm start
```

### Pengaturan

| Variabel | Default | Fungsi |
|---|---|---|
| `PORT` | `3000` | Port HTTP |
| `CONTROL_PIN` | kosong | PIN untuk halaman kontrol dan unggah. **Sebaiknya diisi** jika server bisa diakses dari internet. Halaman TV tidak butuh PIN. |
| `DATA_DIR` | `./data` | Lokasi file unggahan dan urutan slide |

Kalau dipasang di belakang reverse proxy (Nginx, Traefik, Caddy), pastikan **WebSocket** di path `/ws` diteruskan dan **batas ukuran upload** cukup besar untuk video (misalnya `client_max_body_size 4g;` di Nginx).

## Menyiapkan Google TV

Google TV tidak punya browser bawaan, jadi pasang satu dulu di **setiap TV**:

1. Buka Play Store di TV, cari dan pasang **TV Bro** (gratis). Pilihan lain: **Fully Kiosk Browser**, yang bisa otomatis membuka halaman saat TV menyala.
2. Buka browser itu, lalu ketik alamat `https://domain-kamu/tv?name=TV1` (TV kedua pakai `name=TV2`).
3. Tekan **OK** sekali pada tombol di bawah layar. Ini mengaktifkan layar penuh dan suara video, karena browser memblokir suara sebelum ada tombol yang ditekan.
4. Matikan screensaver atau *Ambient mode*: Settings → System → Energy/Ambient mode. Kalau tidak, TV bisa beralih ke screensaver saat menampilkan gambar diam terlalu lama.

Halaman TV akan menyambung ulang sendiri kalau koneksi atau server sempat putus.

## Tips

- **Presentasi PowerPoint/Google Slides/Canva:** ekspor ke **PDF**, lalu unggah. Setiap halaman otomatis menjadi slide. Animasi dan transisi di dalam slide tidak ikut.
- **Video:** pakai **MP4 (H.264 + AAC)** agar pasti bisa diputar di TV. Video 1080p biasanya lebih lancar daripada 4K lewat Wi-Fi.
- **Clicker presentasi** bisa langsung dipakai di laptop yang membuka halaman kontrol (tombol Page Up/Page Down). Tombol `B` atau `.` menghitamkan layar.
- Koneksi TV memakai kabel LAN lebih stabil daripada Wi-Fi, terutama untuk video besar.
- Konten yang dilindungi DRM, seperti Netflix atau siaran TV kabel, tidak bisa ditampilkan lewat cara ini.
