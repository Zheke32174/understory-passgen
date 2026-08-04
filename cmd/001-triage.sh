# READ-ONLY triage. Deletes nothing. Tells the cloud side the ground truth:
# free space, biggest consumers, and whether uid-2000 (rish) can touch Operit.
echo "--- df ---"; df -h /storage/emulated/0 /data 2>/dev/null | sed 's/  */ /g'
echo "--- top dirs under primary storage (GB) ---"
du -x -d2 -B1G /storage/emulated/0 2>/dev/null | sort -rn | head -25
echo "--- Operit dir present? (the 97GB models) ---"
for d in "/storage/emulated/0/Android/data/com.ai.assistance.operit" \
         "/storage/emulated/0/Operit" "/sdcard/Operit"; do
  [ -e "$d" ] && du -x -d1 -B1G "$d" 2>/dev/null | tail -5
done
echo "--- can uid-2000 unlink an Operit file? (dry probe, no delete) ---"
probe="$(find /storage/emulated/0 -path '*operit*' -type f 2>/dev/null | head -1)"
if [ -n "$probe" ]; then
  echo "sample: $probe"; run_priv "test -w '$probe' && echo 'WRITABLE-by-uid-2000 -> deletable' || echo 'not writable'"
else
  echo "no operit file found under primary storage"
fi
echo "--- precious folders present? ---"
for p in Camera Recordings AudioForensics Download/PCAPdroid Documents Pictures DCIM; do
  d="/storage/emulated/0/$p"; [ -e "$d" ] && echo "$p: $(find "$d" -type f 2>/dev/null | wc -l) files, $(du -sh "$d" 2>/dev/null | cut -f1)"
done
