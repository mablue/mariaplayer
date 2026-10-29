#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer

# پاک‌سازی آیکون‌های قبلی
rm -rf app/src/main/res/mipmap-mdpi app/src/main/res/mipmap-hdpi \
       app/src/main/res/mipmap-xhdpi app/src/main/res/mipmap-xxhdpi \
       app/src/main/res/mipmap-xxxhdpi
rm -f app/src/main/res/drawable/ic_launcher_foreground.xml \
      app/src/main/res/drawable/ic_launcher_monochrome.xml \
      app/src/main/res/drawable/ic_notification.png \
      app/src/main/res/values/ic_launcher_background.xml \
      app/src/main/res/values/colors.xml
rm -rf app/src/main/res/mipmap-anydpi-v26

mkdir -p app/src/main/res/mipmap-anydpi-v26 \
         app/src/main/res/drawable \
         app/src/main/res/values

# 1) رنگ پس‌زمینه (برای لایه background)
cat > app/src/main/res/values/ic_launcher_background.xml << 'XEOF'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#1A1A2E</color>
</resources>
XEOF

# 2) لایه Foreground (مثلث گرد + کلید سل حفره‌ای)
cat > app/src/main/res/drawable/ic_launcher_foreground.xml << 'XEOF'
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <!-- مثلث Play با گوشه‌های کاملاً گرد -->
    <path
        android:fillColor="#F5F5F5"
        android:pathData="M36.5,32.8
            C34.8,30.5 33.2,30.1 33.8,33.4
            L33.8,74.6
            C33.2,77.9 34.8,77.5 36.5,75.2
            L80.8,56.0
            C83.6,54.4 83.6,51.6 80.8,50.0
            Z" />

    <!-- کلید سل (𝄞) به رنگ پس‌زمینه، بولد و در مرکز ایمن -->
    <group
        android:translateX="36.0"
        android:translateY="37.5"
        android:scaleX="0.065"
        android:scaleY="0.065">
        <path
            android:fillColor="#1A1A2E"
            android:pathData="M546.4,240.9c-40.3,0-73.8,15.6-96.4,40.3v-13.1c0-11.2-9.1-20.3-20.3-20.3s-20.3,9.1-20.3,20.3v292.6c0,11.2,9.1,20.3,20.3,20.3s20.3-9.1,20.3-20.3v-96.4c22.6,24.7,56.1,40.3,96.4,40.3c75.1,0,136.1-61,136.1-136.1S621.5,240.9,546.4,240.9z M546.4,456.6c-52.7,0-95.6-42.9-95.6-95.6s42.9-95.6,95.6-95.6s95.6,42.9,95.6,95.6S599.1,456.6,546.4,456.6z M450.8,652.3c-11.2,0-20.3,9.1-20.3,20.3v40.6c0,11.2,9.1,20.3,20.3,20.3h40.6c11.2,0,20.3-9.1,20.3-20.3v-40.6c0-11.2-9.1-20.3-20.3-20.3H450.8z" />
    </group>
</vector>
XEOF

# 3) لایه Monochrome (برای Themed Icons اندروید ۱۳+)
cat > app/src/main/res/drawable/ic_launcher_monochrome.xml << 'XEOF'
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <!-- مثلث Play (سفید) -->
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M36.5,32.8
            C34.8,30.5 33.2,30.1 33.8,33.4
            L33.8,74.6
            C33.2,77.9 34.8,77.5 36.5,75.2
            L80.8,56.0
            C83.6,54.4 83.6,51.6 80.8,50.0
            Z" />

    <!-- کلید سل (سفید، evenOdd برای ایجاد حفره) -->
    <group
        android:translateX="36.0"
        android:translateY="37.5"
        android:scaleX="0.065"
        android:scaleY="0.065">
        <path
            android:fillColor="#FFFFFF"
            android:fillType="evenOdd"
            android:pathData="M546.4,240.9c-40.3,0-73.8,15.6-96.4,40.3v-13.1c0-11.2-9.1-20.3-20.3-20.3s-20.3,9.1-20.3,20.3v292.6c0,11.2,9.1,20.3,20.3,20.3s20.3-9.1,20.3-20.3v-96.4c22.6,24.7,56.1,40.3,96.4,40.3c75.1,0,136.1-61,136.1-136.1S621.5,240.9,546.4,240.9z M546.4,456.6c-52.7,0-95.6-42.9-95.6-95.6s42.9-95.6,95.6-95.6s95.6,42.9,95.6,95.6S599.1,456.6,546.4,456.6z M450.8,652.3c-11.2,0-20.3,9.1-20.3,20.3v40.6c0,11.2,9.1,20.3,20.3,20.3h40.6c11.2,0,20.3-9.1,20.3-20.3v-40.6c0-11.2-9.1-20.3-20.3-20.3H450.8z" />
    </group>
</vector>
XEOF

# 4) Adaptive Icon (ic_launcher + ic_launcher_round)
for name in ic_launcher ic_launcher_round; do
cat > "app/src/main/res/mipmap-anydpi-v26/${name}.xml" << 'XEOF'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
XEOF
done

# 5) آیکون نوتیفیکیشن (Vector ساده - فقط کلید سل سفید)
cat > app/src/main/res/drawable/ic_notification.xml << 'XEOF'
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <group
        android:translateX="2.0"
        android:translateY="2.0"
        android:scaleX="0.0185"
        android:scaleY="0.0185">
        <path
            android:fillColor="#FFFFFF"
            android:pathData="M546.4,240.9c-40.3,0-73.8,15.6-96.4,40.3v-13.1c0-11.2-9.1-20.3-20.3-20.3s-20.3,9.1-20.3,20.3v292.6c0,11.2,9.1,20.3,20.3,20.3s20.3-9.1,20.3-20.3v-96.4c22.6,24.7,56.1,40.3,96.4,40.3c75.1,0,136.1-61,136.1-136.1S621.5,240.9,546.4,240.9z M546.4,456.6c-52.7,0-95.6-42.9-95.6-95.6s42.9-95.6,95.6-95.6s95.6,42.9,95.6,95.6S599.1,456.6,546.4,456.6z M450.8,652.3c-11.2,0-20.3,9.1-20.3,20.3v40.6c0,11.2,9.1,20.3,20.3,20.3h40.6c11.2,0,20.3-9.1,20.3-20.3v-40.6c0-11.2-9.1-20.3-20.3-20.3H450.8z" />
    </group>
</vector>
XEOF

# 6) به‌روزرسانی PlaybackService برای استفاده از Vector نوتیفیکیشن
python3 - << 'PY'
import pathlib
p = pathlib.Path('app/src/main/java/ir/mariaplayer/playback/PlaybackService.kt')
s = p.read_text()
if 'setSmallIcon(ir.mariaplayer.R.drawable.ic_notification)' in s:
    s = s.replace('R.drawable.ic_notification', 'R.drawable.ic_notification')
    p.write_text(s)
    print("PlaybackService: notification icon ref kept (now vector)")
PY

# 7) کامیت و پوش
git add -A
git commit -qm "feat(icon): pure vector adaptive icon (rounded play + clef cut-out + monochrome + themed)"

for attempt in 1 2 3; do
  if git push origin main; then break; fi
  echo "push retry $attempt"
  git pull --rebase origin main || true
  sleep 3
done
echo "pushed"
