#!/system/bin/sh
echo "===== NR BAND MANAGER DEVICE DIAGNOSTICS ====="
echo ""

echo "-- 1. DIAG port --"
ls -la /dev/diag 2>/dev/null || echo "  /dev/diag: NOT FOUND"
ls -la /dev/qmi* 2>/dev/null || echo "  /dev/qmi*: NOT FOUND"

echo ""
echo "-- 2. QRTR socket (modern Qualcomm QMI) --"
ls -la /dev/socket/qrtr* 2>/dev/null || echo "  /dev/socket/qrtr*: NOT FOUND"
ls -la /dev/qrtr* 2>/dev/null || echo "  /dev/qrtr*: NOT FOUND"

echo ""
echo "-- 3. Vendor radio binaries --"
ls /vendor/bin/hw/ | grep -i radio 2>/dev/null
ls /vendor/bin/ | grep -iE 'qmi|qcril|diag|modem|ril' 2>/dev/null || echo "  No vendor bins found"

echo ""
echo "-- 4. RIL libraries --"
ls /vendor/lib64/ | grep -iE 'ril|qmi|qcril|radio' 2>/dev/null | head -20

echo ""
echo "-- 5. Vendor properties (radio/modem/band) --"
getprop | grep -iE 'ril\.|radio\.|qmi\.|modem\.|band\.|nr\.|5g\.|nsa\.' 2>/dev/null | head -30

echo ""
echo "-- 6. Network mode setting test --"
settings get global preferred_network_mode 2>/dev/null
settings get global preferred_network_mode1 2>/dev/null

echo ""
echo "-- 7. cmd phone capabilities --"
cmd phone help 2>/dev/null | head -40 || echo "  cmd phone not available"

echo ""
echo "-- 8. Current band from telephony --"
dumpsys telephony.registry 2>/dev/null | grep -iE 'earfcn|arfcn|band|nrFreq|DataNetworkType|NrState|mServiceState' | head -20

echo ""
echo "-- 9. AT port test (try all ports) --"
for port in /dev/at_mdm0 /dev/at_usb0 /dev/smd8 /dev/smd11; do
    if [ -e "$port" ]; then
        echo "  Testing $port..."
        # Try to send ATI and read response with timeout
        (echo -e "ATI\r" > "$port" 2>/dev/null; timeout 2 head -c 512 < "$port" 2>/dev/null) | head -5
        echo "  ---"
    fi
done

echo ""
echo "-- 10. Check for vendor band config files --"
find /vendor/rfs /vendor/firmware -name "*band*" -o -name "*nv*" -o -name "*mbn*" 2>/dev/null | head -10
find /data/vendor -name "*band*" -o -name "*policyman*" 2>/dev/null | head -10

echo ""
echo "-- 11. Check for DIAG kernel module --"
lsmod 2>/dev/null | grep -i diag
cat /proc/devices 2>/dev/null | grep -i diag

echo ""
echo "-- 12. QRTR services --"
# Check if qrtr-lookup or similar exists
which qrtr-lookup 2>/dev/null && qrtr-lookup 2>/dev/null | head -20
cat /proc/net/qrtr 2>/dev/null | head -20 || echo "  /proc/net/qrtr not found"

echo ""
echo "===== DIAGNOSTICS COMPLETE ====="
